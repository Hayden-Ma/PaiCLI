package com.paicli.rag;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 向量/关键词/关系检索的存储后端抽象。
 * <p>
 * 当前实现：{@link VectorStore}（本地 SQLite，零配置离线默认）、
 * {@link ElasticsearchStore}（远程 ES8，HNSW kNN + BM25，RAG_STORE=es 启用）。
 * 两后端共用 {@link VectorStore.SearchResult} 等数据模型；
 * 关键词腿的相似度统一归一化到 [0.3, 0.8] 伪相似度区间，保证混合融合语义一致。
 * <p>
 * 批量接口（{@link #indexedFiles} / {@link #replaceFiles} / {@link #removeFiles}）是
 * /index 的主路径；单文件接口（{@link #getFileHashes} / {@link #upsertFileHash} /
 * {@link #deleteFileData} / {@link #insertChunks} / {@link #insertRelations}）保留给
 * CodeRetriever 新鲜度判断和细粒度操作。
 */
public interface RetrievalStore extends AutoCloseable {

    void clearProject() throws SQLException;

    void insertChunks(List<VectorStore.CodeChunkEntry> entries) throws SQLException;

    void insertRelations(List<CodeRelation> relations) throws SQLException;

    /** 当前项目已索引文件的 content hash（file_path → sha256） */
    Map<String, String> getFileHashes() throws SQLException;

    void upsertFileHash(String filePath, String contentHash) throws SQLException;

    /** 删除单文件的全部索引痕迹：代码块、以该文件为起点的关系、hash 记录 */
    void deleteFileData(String filePath) throws SQLException;

    /** 当前项目已索引的文件及其内容指纹（内容 + Embedding 模型）；没有指纹的文件增量索引时重嵌 */
    Map<String, VectorStore.IndexedFile> indexedFiles() throws SQLException;

    /** 整体替换一批文件的索引：删旧块和关系、写新的、记指纹；失败时这批文件的旧索引保持原样 */
    void replaceFiles(List<VectorStore.FileIndex> files) throws SQLException;

    /** 删除一批文件的代码块、关系和指纹（文件已被删除或不再索引） */
    void removeFiles(Collection<String> filePaths) throws SQLException;

    /** 语义检索：按查询向量返回最相似的 TopK（相似度 = cosine） */
    List<VectorStore.SearchResult> search(float[] queryEmbedding, int topK) throws SQLException;

    /** 关键词检索：按相关性排序，相似度为 [0.3, 0.8] 归一化伪分 */
    List<VectorStore.SearchResult> searchByKeyword(String keyword) throws SQLException;

    /** 图谱检索：与指定名称相关的所有关系 */
    List<CodeRelation> getRelations(String name) throws SQLException;

    VectorStore.IndexStats getStats() throws SQLException;

    @Override
    void close() throws SQLException;
}
