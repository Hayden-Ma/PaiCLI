package com.paicli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES8 后端集成测试：需要本机可访问的 ES（隧道到 localhost:9200）。
 * 默认跳过，RAG_IT_ES=1 时启用。store 级用 2 维向量桩；e2e 用真实 bge-m3（1024 维）。
 */
@EnabledIfEnvironmentVariable(named = "RAG_IT_ES", matches = "true")
class ElasticsearchStoreTest {

    private static final String ES_URL = "http://localhost:9200";
    private static final String TEST_PROJECT = "paicli-es-it-project";

    private ElasticsearchStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new ElasticsearchStore(TEST_PROJECT, ES_URL, null, null, null, 2);
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.clearProject();
            store.close();
        }
    }

    private VectorStore.CodeChunkEntry entry(String file, String name, String content,
                                             int startLine, int endLine, float[] vec) {
        return new VectorStore.CodeChunkEntry(
                CodeChunk.methodChunk(file, name, content, startLine, endLine), vec);
    }

    @Test
    void knnSearchRanksByCosineSimilarity() throws Exception {
        store.insertChunks(List.of(
                entry("A.java", "A.run", "run method", 10, 20, new float[]{1.0f, 0.0f}),
                entry("B.java", "B.stop", "stop method", 5, 8, new float[]{0.0f, 1.0f})));

        List<VectorStore.SearchResult> results = store.search(new float[]{1.0f, 0.0f}, 2);
        assertEquals(2, results.size());
        assertEquals("A.run", results.get(0).name());
        assertTrue(results.get(0).similarity() > 0.99, "精确同向向量应得 ~1.0 余弦分");
        assertEquals(10, results.get(0).startLine());
        assertEquals(20, results.get(0).endLine());
    }

    @Test
    void keywordSearchNormalizesScoresIntoPseudoSimilarityRange() throws Exception {
        store.insertChunks(List.of(
                entry("A.java", "HybridSearch", "hybrid retrieval with bm25 scoring", 1, 2, new float[]{1.0f, 0.0f}),
                entry("B.java", "Unrelated", "nothing here", 3, 4, new float[]{0.0f, 1.0f})));

        List<VectorStore.SearchResult> results = store.searchByKeyword("HybridSearch");
        assertEquals(1, results.size());
        assertTrue(results.get(0).similarity() >= 0.3 && results.get(0).similarity() <= 0.8,
                "关键词伪相似度必须落在 [0.3, 0.8]，实际 " + results.get(0).similarity());
    }

    @Test
    void fileHashLifecycleAndFileDataDeletion() throws Exception {
        store.insertChunks(List.of(
                entry("A.java", "A.run", "run", 1, 2, new float[]{1.0f, 0.0f}),
                entry("B.java", "B.run", "run", 1, 2, new float[]{0.0f, 1.0f})));
        store.insertRelations(List.of(new CodeRelation("A.java", "A", "B.java", "B", "extends")));
        store.upsertFileHash("A.java", "hash-a1");
        store.upsertFileHash("B.java", "hash-b1");

        assertEquals(2, store.getFileHashes().size());
        store.upsertFileHash("A.java", "hash-a2");
        assertEquals("hash-a2", store.getFileHashes().get("A.java"));

        store.deleteFileData("A.java");
        assertEquals(1, store.getStats().chunkCount());
        assertEquals(0, store.getStats().relationCount());
        assertFalse(store.getFileHashes().containsKey("A.java"));
        assertTrue(store.getFileHashes().containsKey("B.java"));

        store.clearProject();
        assertEquals(0, store.getStats().chunkCount());
        assertTrue(store.getFileHashes().isEmpty());
    }

    @Test
    void dimensionMismatchFailsWithRebuildHint() throws Exception {
        store.insertChunks(List.of(entry("A.java", "A.run", "run", 1, 2, new float[]{1.0f, 0.0f})));
        try {
            store.search(new float[]{1.0f, 0.0f, 0.0f}, 2);
            assertTrue(false, "维度不匹配应抛出异常");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("--full"), "错误信息应提示 /index --full 重建");
        }
    }

    /**
     * 端到端：真实 bge-m3（1024 维）索引 src/test/resources/rag 并混合检索，
     * 验证 CodeIndex → ES → CodeRetriever → markStale 全链路。
     */
    @Test
    void endToEndIndexAndHybridSearchWithRealEmbedding() throws Exception {
        String previousStore = System.setProperty("RAG_STORE", "es");
        try {
            CodeIndex indexer = new CodeIndex();
            CodeIndex.IndexResult indexed = indexer.index("src/test/resources/rag");
            assertTrue(indexed.chunkCount() > 0, "应至少索引一个代码块");

            try (CodeRetriever retriever = new CodeRetriever("src/test/resources/rag")) {
                assertEquals("es", System.getProperty("RAG_STORE"));
                List<VectorStore.SearchResult> results =
                        retriever.hybridSearch("增量索引如何跳过未变更文件", 5);
                results = retriever.markStale(results);
                assertFalse(results.isEmpty(), "混合检索应命中已索引代码");
                assertTrue(results.get(0).similarity() > 0);
            }
        } finally {
            if (previousStore != null) {
                System.setProperty("RAG_STORE", previousStore);
            } else {
                System.clearProperty("RAG_STORE");
            }
        }
    }
}
