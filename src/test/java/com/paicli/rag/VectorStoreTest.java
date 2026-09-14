package com.paicli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorStoreTest {

    private VectorStore store;
    private static final String TEST_PROJECT = "/tmp/test-project";

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("paicli.rag.dir", "/tmp/paicli-test-rag");
        store = new VectorStore(TEST_PROJECT);
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testInsertAndSearch() throws Exception {
        CodeChunk chunk1 = CodeChunk.classChunk("Test.java", "TestClass",
                "public class TestClass {}", 1, 1);
        CodeChunk chunk2 = CodeChunk.methodChunk("Test.java", "TestClass.main",
                "public static void main(String[] args) {}", 2, 4);

        float[] emb1 = {1.0f, 0.0f, 0.0f};
        float[] emb2 = {0.0f, 1.0f, 0.0f};

        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(chunk1, emb1),
                new VectorStore.CodeChunkEntry(chunk2, emb2)
        ));

        VectorStore.IndexStats stats = store.getStats();
        assertEquals(2, stats.chunkCount());

        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorStore.SearchResult> results = store.search(query, 2);
        assertEquals(2, results.size());
        assertEquals("TestClass", results.get(0).name());
        assertTrue(results.get(0).similarity() > 0.99);
    }

    @Test
    void testSearchByKeyword() throws Exception {
        CodeChunk chunk = CodeChunk.classChunk("Foo.java", "FooService",
                "public class FooService { public void bar() {} }", 1, 3);
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{0.5f, 0.5f})));

        List<VectorStore.SearchResult> results = store.searchByKeyword("FooService");
        assertEquals(1, results.size());
        assertEquals("FooService", results.get(0).name());
    }

    @Test
    void testRelationStorage() throws Exception {
        CodeRelation rel = new CodeRelation("A.java", "A", "B.java", "B", "extends");
        store.insertRelations(List.of(rel));

        List<CodeRelation> results = store.getRelations("A");
        assertEquals(1, results.size());
        assertEquals("extends", results.get(0).relationType());
    }

    @Test
    void testClearProject() throws Exception {
        CodeChunk chunk = CodeChunk.fileChunk("readme.md", "# Hello");
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{1.0f})));
        assertEquals(1, store.getStats().chunkCount());

        store.clearProject();
        assertEquals(0, store.getStats().chunkCount());
    }

    @Test
    void testSearchReturnsLineRange() throws Exception {
        CodeChunk chunk = CodeChunk.methodChunk("Demo.java", "Demo.run",
                "void run() {}", 12, 34);
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{1.0f, 0.0f})));

        List<VectorStore.SearchResult> results = store.searchByKeyword("Demo.run");
        assertEquals(1, results.size());
        assertEquals(12, results.get(0).startLine());
        assertEquals(34, results.get(0).endLine());
    }

    @Test
    void testFileHashLifecycle() throws Exception {
        assertTrue(store.getFileHashes().isEmpty());

        store.upsertFileHash("A.java", "hash-a1");
        store.upsertFileHash("B.java", "hash-b1");
        assertEquals(2, store.getFileHashes().size());

        // 同文件重复 upsert 覆盖旧值
        store.upsertFileHash("A.java", "hash-a2");
        assertEquals("hash-a2", store.getFileHashes().get("A.java"));
        assertEquals(2, store.getFileHashes().size());

        // deleteFileData 清掉该文件的 hash 记录
        store.deleteFileData("A.java");
        assertFalse(store.getFileHashes().containsKey("A.java"));
        assertTrue(store.getFileHashes().containsKey("B.java"));

        // clearProject 清掉全部 hash
        store.clearProject();
        assertTrue(store.getFileHashes().isEmpty());
    }

    @Test
    void testDeleteFileDataRemovesChunksAndRelations() throws Exception {
        CodeChunk chunkA = CodeChunk.classChunk("A.java", "A", "class A {}", 1, 1);
        CodeChunk chunkB = CodeChunk.classChunk("B.java", "B", "class B {}", 1, 1);
        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(chunkA, new float[]{1.0f}),
                new VectorStore.CodeChunkEntry(chunkB, new float[]{0.0f, 1.0f})
        ));
        store.insertRelations(List.of(new CodeRelation("A.java", "A", "B.java", "B", "extends")));

        store.deleteFileData("A.java");

        assertEquals(1, store.getStats().chunkCount());
        assertEquals(0, store.getStats().relationCount());
    }
}
