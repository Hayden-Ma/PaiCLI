package com.paicli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ES8 检索后端：HNSW kNN（dense_vector/cosine）+ BM25 关键词 + 关系/hash 文档。
 * <p>
 * 直连 REST API（OkHttp + Jackson），不引入官方 elasticsearch-java 依赖。
 * 两个索引：paicli-rag-chunks-{proj8}（代码块+向量）、paicli-rag-meta-{proj8}（hash 记录+关系图）。
 * BM25 分数在返回前 min-max 归一化到 [0.3, 0.8]，与 SQLite 关键词腿的伪相似度契约一致。
 */
public class ElasticsearchStore implements RetrievalStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /** 进程内已确认存在的索引，避免每次构造都重复 PUT mapping */
    private static final Set<String> ENSURED_INDICES = ConcurrentHashMap.newKeySet();

    private static final int KEYWORD_RESULT_SIZE = 50;
    private static final int MAX_HASH_ENTRIES = 10000;

    private final String baseUrl;
    private final String authHeader;
    private final String projectPath;
    private final String chunksIndex;
    private final String metaIndex;
    private final int dims;

    public ElasticsearchStore(String projectPath, String baseUrl,
                              String username, String password, String apiKey, int dims) {
        this.projectPath = projectPath;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authHeader = buildAuthHeader(username, password, apiKey);
        this.dims = dims;
        String proj8 = md5Hex(projectPath).substring(0, 8);
        this.chunksIndex = "paicli-rag-chunks-" + proj8;
        this.metaIndex = "paicli-rag-meta-" + proj8;
    }

    private static String buildAuthHeader(String username, String password, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return "ApiKey " + apiKey;
        }
        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + (password == null ? "" : password);
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    // ---------- 索引管理 ----------

    private void ensureIndices() throws SQLException {
        if (ENSURED_INDICES.contains(chunksIndex) && ENSURED_INDICES.contains(metaIndex)) {
            return;
        }
        ensureIndex(chunksIndex, chunksMapping());
        ensureIndex(metaIndex, metaMapping());
        ENSURED_INDICES.add(chunksIndex);
        ENSURED_INDICES.add(metaIndex);
    }

    private void ensureIndex(String index, ObjectNode mapping) throws SQLException {
        if (http("HEAD", "/" + index, null).isSuccessful()) {
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.set("mappings", mapping);
        Response response = http("PUT", "/" + index, body.toString());
        if (!response.isSuccessful()) {
            throw new SQLException("创建 ES 索引失败 " + index + ": " + safeBody(response));
        }
    }

    private ObjectNode chunksMapping() {
        ObjectNode embedding = MAPPER.createObjectNode();
        embedding.put("type", "dense_vector");
        embedding.put("dims", dims);
        embedding.put("index", true);
        embedding.put("similarity", "cosine");

        ObjectNode name = MAPPER.createObjectNode();
        name.put("type", "keyword");
        ObjectNode nameText = name.putObject("fields");
        ObjectNode nameTextField = nameText.putObject("text");
        nameTextField.put("type", "text");

        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("embedding", embedding);
        properties.set("name", name);
        properties.set("project", projectProperty());
        properties.set("file_path", keywordProperty("file_path"));
        properties.set("chunk_type", keywordProperty("chunk_type"));
        properties.set("content", textProperty("content"));
        properties.set("start_line", integerProperty("start_line"));
        properties.set("end_line", integerProperty("end_line"));

        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.set("properties", properties);
        return mapping;
    }

    private ObjectNode metaMapping() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("project", projectProperty());
        properties.set("doc_type", keywordProperty("doc_type"));
        properties.set("file_path", keywordProperty("file_path"));
        properties.set("content_hash", keywordProperty("content_hash"));
        properties.set("from_file", keywordProperty("from_file"));
        properties.set("from_name", keywordProperty("from_name"));
        properties.set("to_file", keywordProperty("to_file"));
        properties.set("to_name", keywordProperty("to_name"));
        properties.set("relation_type", keywordProperty("relation_type"));

        ObjectNode mapping = MAPPER.createObjectNode();
        mapping.set("properties", properties);
        return mapping;
    }

    private ObjectNode projectProperty() {
        return keywordProperty("project");
    }

    private ObjectNode keywordProperty(String field) {
        ObjectNode prop = MAPPER.createObjectNode();
        prop.put("type", "keyword");
        return prop;
    }

    private ObjectNode textProperty(String field) {
        ObjectNode prop = MAPPER.createObjectNode();
        prop.put("type", "text");
        return prop;
    }

    private ObjectNode integerProperty(String field) {
        ObjectNode prop = MAPPER.createObjectNode();
        prop.put("type", "integer");
        return prop;
    }

    // ---------- 写入 ----------

    @Override
    public void clearProject() throws SQLException {
        ensureIndices();
        String query = termProjectQuery().toString();
        deleteByQuery(chunksIndex, query);
        deleteByQuery(metaIndex, query);
    }

    @Override
    public void insertChunks(List<VectorStore.CodeChunkEntry> entries) throws SQLException {
        ensureIndices();
        StringBuilder ndjson = new StringBuilder();
        for (VectorStore.CodeChunkEntry entry : entries) {
            if (entry.embedding().length != dims) {
                throw new SQLException("向量维度不匹配：索引映射为 " + dims + " 维，实际 "
                        + entry.embedding().length + " 维（更换 Embedding 模型后请执行 /index --full 重建）");
            }
            ndjson.append("{\"index\":{}}\n");
            ndjson.append(chunkDoc(entry.chunk(), entry.embedding()).toString()).append('\n');
        }
        bulk(chunksIndex, ndjson.toString());
    }

    @Override
    public void insertRelations(List<CodeRelation> relations) throws SQLException {
        ensureIndices();
        StringBuilder ndjson = new StringBuilder();
        for (CodeRelation rel : relations) {
            ndjson.append("{\"index\":{}}\n");
            ndjson.append(relationDoc(rel).toString()).append('\n');
        }
        bulk(metaIndex, ndjson.toString());
    }

    private void bulk(String index, String ndjson) throws SQLException {
        Response response = http("POST", "/" + index + "/_bulk?refresh=true", ndjson);
        if (!response.isSuccessful()) {
            throw new SQLException("ES 批量写入失败: " + safeBody(response));
        }
    }

    private ObjectNode chunkDoc(CodeChunk chunk, float[] embedding) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("project", projectPath);
        doc.put("file_path", chunk.filePath());
        doc.put("chunk_type", chunk.chunkType());
        doc.put("name", chunk.name());
        doc.put("content", chunk.content());
        ArrayNode vec = doc.putArray("embedding");
        for (float v : embedding) {
            vec.add(v);
        }
        doc.put("start_line", chunk.startLine());
        doc.put("end_line", chunk.endLine());
        return doc;
    }

    private ObjectNode relationDoc(CodeRelation rel) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("project", projectPath);
        doc.put("doc_type", "relation");
        doc.put("from_file", rel.fromFile());
        doc.put("from_name", rel.fromName());
        doc.put("to_file", rel.toFile());
        doc.put("to_name", rel.toName());
        doc.put("relation_type", rel.relationType());
        return doc;
    }

    // ---------- hash ----------

    @Override
    public Map<String, String> getFileHashes() throws SQLException {
        ensureIndices();
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode hashBool = boolQuery();
        hashBool.set("filter", filterArray(
                termDoc("project", projectPath),
                termDoc("doc_type", "file_hash")));
        body.set("query", wrapBool(hashBool));
        body.put("size", MAX_HASH_ENTRIES);

        JsonNode hits = search(metaIndex, body);
        Map<String, String> hashes = new HashMap<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            hashes.put(source.path("file_path").asText(), source.path("content_hash").asText());
        }
        return hashes;
    }

    @Override
    public void upsertFileHash(String filePath, String contentHash) throws SQLException {
        upsertFileHash(filePath, contentHash, "unknown");
    }

    private void upsertFileHash(String filePath, String contentHash, String embeddingModel) throws SQLException {
        ensureIndices();
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("project", projectPath);
        doc.put("doc_type", "file_hash");
        doc.put("file_path", filePath);
        doc.put("content_hash", contentHash);
        doc.put("embedding_model", embeddingModel);

        // 确定性 _id：同文件重复 upsert 覆盖旧值
        String docId = md5Hex(projectPath + "|" + filePath);
        Response response = http("PUT", "/" + metaIndex + "/_doc/" + docId + "?refresh=true", doc.toString());
        if (!response.isSuccessful()) {
            throw new SQLException("ES 写入 hash 失败: " + safeBody(response));
        }
    }

    @Override
    public Map<String, VectorStore.IndexedFile> indexedFiles() throws SQLException {
        ensureIndices();
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode hashBool = boolQuery();
        hashBool.set("filter", filterArray(
                termDoc("project", projectPath),
                termDoc("doc_type", "file_hash")));
        body.set("query", wrapBool(hashBool));
        body.put("size", MAX_HASH_ENTRIES);

        JsonNode hits = search(metaIndex, body);
        Map<String, VectorStore.IndexedFile> files = new HashMap<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            files.put(source.path("file_path").asText(),
                    new VectorStore.IndexedFile(
                            source.path("content_hash").asText(),
                            source.path("embedding_model").asText("unknown")));
        }
        return files;
    }

    @Override
    public void replaceFiles(List<VectorStore.FileIndex> files) throws SQLException {
        ensureIndices();
        // ES 没有跨文档事务：先删后写，中途失败抛出后由调用方整批回退逐文件重试，
        // 失败文件保留旧索引（deleteFileData 已删的会重新写入）
        for (VectorStore.FileIndex file : files) {
            deleteFileData(file.filePath());
        }
        List<VectorStore.CodeChunkEntry> entries = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        for (VectorStore.FileIndex file : files) {
            entries.addAll(file.entries());
            relations.addAll(file.relations());
        }
        if (!entries.isEmpty()) {
            insertChunks(entries);
        }
        if (!relations.isEmpty()) {
            insertRelations(relations);
        }
        for (VectorStore.FileIndex file : files) {
            upsertFileHash(file.filePath(), file.contentSha256(), file.embeddingModel());
        }
    }

    @Override
    public void removeFiles(Collection<String> filePaths) throws SQLException {
        for (String filePath : filePaths) {
            deleteFileData(filePath);
        }
    }

    @Override
    public void deleteFileData(String filePath) throws SQLException {
        ensureIndices();
        ObjectNode chunkBool = boolQuery();
        chunkBool.set("filter", filterArray(
                termDoc("project", projectPath),
                termDoc("file_path", filePath)));
        deleteByQuery(chunksIndex, wrapBool(chunkBool).toString());

        // hash 记录按 file_path；关系按 from_file
        ObjectNode metaBool = boolQuery();
        ArrayNode shouldArr = metaBool.putArray("should");
        shouldArr.add(termDoc("file_path", filePath));
        shouldArr.add(termDoc("from_file", filePath));
        metaBool.put("minimum_should_match", 1);
        ArrayNode metaFilter = metaBool.putArray("filter");
        metaFilter.add(termDoc("project", projectPath));
        deleteByQuery(metaIndex, wrapBool(metaBool).toString());
    }

    // ---------- 检索 ----------

    @Override
    public List<VectorStore.SearchResult> search(float[] queryEmbedding, int topK) throws SQLException {
        ensureIndices();
        if (queryEmbedding.length != dims) {
            throw new SQLException("查询向量维度 " + queryEmbedding.length
                    + " 与索引映射 " + dims + " 维不一致（更换 Embedding 模型后请 /index --full）");
        }

        ObjectNode knn = MAPPER.createObjectNode();
        knn.put("field", "embedding");
        ArrayNode vec = knn.putArray("query_vector");
        for (float v : queryEmbedding) {
            vec.add(v);
        }
        knn.put("k", Math.max(1, topK));
        knn.put("num_candidates", Math.max(100, topK * 10));
        knn.set("filter", filterArray(termDoc("project", projectPath)));

        ObjectNode body = MAPPER.createObjectNode();
        body.set("knn", knn);

        List<VectorStore.SearchResult> results = new ArrayList<>();
        for (JsonNode hit : search(chunksIndex, body)) {
            JsonNode source = hit.path("_source");
            results.add(new VectorStore.SearchResult(
                    source.path("file_path").asText(),
                    source.path("chunk_type").asText(),
                    source.path("name").asText(),
                    source.path("content").asText(),
                    hit.path("_score").asDouble(0.0),
                    source.path("start_line").asInt(0),
                    source.path("end_line").asInt(0),
                    false));
        }
        return results;
    }

    @Override
    public List<VectorStore.SearchResult> searchByKeyword(String keyword) throws SQLException {
        ensureIndices();

        ObjectNode bool = boolQuery();
        ArrayNode shouldArr = bool.putArray("should");
        // 精确 symbol 命中最强信号，加权置顶
        shouldArr.addObject().putObject("term").putObject("name")
                .put("value", keyword).put("boost", 2.0);
        shouldArr.addObject().putObject("match").putObject("name.text").put("query", keyword);
        shouldArr.addObject().putObject("match").putObject("content").put("query", keyword);
        bool.put("minimum_should_match", 1);

        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode filter = bool.putArray("filter");
        filter.add(termDoc("project", projectPath));
        body.set("query", wrapBool(bool));
        body.put("size", KEYWORD_RESULT_SIZE);

        List<VectorStore.SearchResult> hits = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (JsonNode hit : search(chunksIndex, body)) {
            JsonNode source = hit.path("_source");
            hits.add(new VectorStore.SearchResult(
                    source.path("file_path").asText(),
                    source.path("chunk_type").asText(),
                    source.path("name").asText(),
                    source.path("content").asText(),
                    0.0,
                    source.path("start_line").asInt(0),
                    source.path("end_line").asInt(0),
                    false));
            scores.add(hit.path("_score").asDouble(0.0));
        }

        // min-max 归一化到 [0.3, 0.8]，与 SQLite 关键词腿契约一致
        return normalizeKeywordScores(hits, scores);
    }

    private static List<VectorStore.SearchResult> normalizeKeywordScores(
            List<VectorStore.SearchResult> hits, List<Double> scores) {
        if (hits.isEmpty()) {
            return hits;
        }
        double min = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        List<VectorStore.SearchResult> normalized = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            VectorStore.SearchResult hit = hits.get(i);
            double pseudo;
            if (max - min < 1e-9) {
                pseudo = 0.55;
            } else {
                pseudo = 0.3 + 0.5 * (scores.get(i) - min) / (max - min);
            }
            normalized.add(new VectorStore.SearchResult(
                    hit.filePath(), hit.chunkType(), hit.name(), hit.content(),
                    pseudo, hit.startLine(), hit.endLine(), hit.stale()));
        }
        return normalized;
    }

    @Override
    public List<CodeRelation> getRelations(String name) throws SQLException {
        ensureIndices();

        ObjectNode should = MAPPER.createObjectNode();
        ArrayNode shouldArr = should.putArray("should");
        shouldArr.add(termDoc("from_name", name));
        shouldArr.add(termDoc("to_name", name));
        should.put("minimum_should_match", 1);

        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode bool = boolQuery();
        ArrayNode filter = bool.putArray("filter");
        filter.add(termDoc("project", projectPath));
        filter.add(termDoc("doc_type", "relation"));
        bool.set("should", shouldArr);
        body.set("query", wrapBool(bool));
        body.put("size", 1000);

        List<CodeRelation> relations = new ArrayList<>();
        for (JsonNode hit : search(metaIndex, body)) {
            JsonNode source = hit.path("_source");
            relations.add(new CodeRelation(
                    source.path("from_file").asText(),
                    source.path("from_name").asText(),
                    source.path("to_file").asText(),
                    source.path("to_name").asText(),
                    source.path("relation_type").asText()));
        }
        return relations;
    }

    @Override
    public VectorStore.IndexStats getStats() throws SQLException {
        ensureIndices();
        int chunks = countDocs(chunksIndex, null);
        int relations = countDocs(metaIndex, "relation");
        return new VectorStore.IndexStats(chunks, relations);
    }

    private int countDocs(String index, String docType) throws SQLException {
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode countBool = boolQuery();
        countBool.set("filter", filterArray(
                termDoc("project", projectPath),
                docType == null ? null : termDoc("doc_type", docType)));
        body.set("query", wrapBool(countBool));
        JsonNode response = postJson("/" + index + "/_count", body);
        return response.path("count").asInt(0);
    }

    // ---------- HTTP / JSON 基础 ----------

    private JsonNode search(String index, ObjectNode body) throws SQLException {
        return postJson("/" + index + "/_search", body).path("hits").path("hits");
    }

    private void deleteByQuery(String index, String query) throws SQLException {
        ObjectNode body = MAPPER.createObjectNode();
        try {
            body.set("query", MAPPER.readTree(query));
        } catch (IOException e) {
            throw new SQLException("delete_by_query 查询构造失败", e);
        }
        JsonNode response = postJson("/" + index + "/_delete_by_query?refresh=true", body);
        if (response.path("failures").size() > 0) {
            throw new SQLException("ES delete_by_query 部分失败: " + response.path("failures").toString());
        }
    }

    private JsonNode postJson(String path, ObjectNode body) throws SQLException {
        Response response = http("POST", path, body.toString());
        String responseBody = safeBody(response);
        if (!response.isSuccessful()) {
            throw new SQLException("ES 请求失败 [" + path + "]: " + responseBody);
        }
        try {
            return MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new SQLException("ES 响应解析失败: " + e.getMessage(), e);
        }
    }

    private Response http(String method, String path, String jsonBody) throws SQLException {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .header("Content-Type", "application/json");
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        switch (method) {
            case "HEAD" -> builder.head();
            case "PUT" -> builder.put(RequestBody.create(jsonBody == null ? "{}" : jsonBody, JSON));
            case "POST" -> builder.post(RequestBody.create(jsonBody == null ? "{}" : jsonBody, JSON));
            default -> builder.get();
        }
        try {
            return HTTP_CLIENT.newCall(builder.build()).execute();
        } catch (IOException e) {
            throw new SQLException("无法连接 Elasticsearch（" + baseUrl + "）: " + e.getMessage()
                    + "。检查 ES 服务/隧道是否可达，或临时切换 RAG_STORE=sqlite 并用 grep_code 兜底。", e);
        }
    }

    private static String safeBody(Response response) {
        try (ResponseBody body = response.body()) {
            return body == null ? "无响应体" : body.string();
        } catch (IOException e) {
            return "响应体读取失败";
        }
    }

    private ObjectNode boolQuery() {
        // 返回 bool 容器本身，调用方填 filter/should 后须用 wrapBool 包壳
        return MAPPER.createObjectNode();
    }

    private static ObjectNode wrapBool(ObjectNode bool) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("bool", bool);
        return root;
    }

    private ArrayNode filterArray(JsonNode... clauses) {
        ArrayNode filter = MAPPER.createArrayNode();
        for (JsonNode clause : clauses) {
            if (clause != null) {
                filter.add(clause);
            }
        }
        return filter;
    }

    private ObjectNode termProjectQuery() {
        ObjectNode bool = boolQuery();
        bool.set("filter", filterArray(termDoc("project", projectPath)));
        return wrapBool(bool);
    }

    private static ObjectNode termDoc(String field, String value) {
        ObjectNode term = MAPPER.createObjectNode();
        term.putObject("term").put(field, value);
        return term;
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("JVM 缺少 MD5 实现", e);
        }
    }

    @Override
    public void close() {
        // 共享静态 HTTP 客户端，无需关闭
    }
}
