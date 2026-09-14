package com.paicli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeRetrieverTest {

    private static final String TEST_PROJECT = "/tmp/paicli-code-retriever";
    private VectorStore store;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("paicli.rag.dir", "/tmp/paicli-test-rag-retriever");
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
    void hybridSearchBoostsCodeKeywordsFromNaturalLanguageQuery() throws Exception {
        CodeChunk getterChunk = CodeChunk.methodChunk(
                "src/main/java/com/example/Task.java",
                "Task.getId()",
                "public String getId() { return id; }",
                10, 12
        );
        CodeChunk agentChunk = CodeChunk.methodChunk(
                "src/main/java/com/example/Agent.java",
                "Agent.run(String userInput)",
                "ReAct 循环：读取用户输入，思考，必要时调用工具，再继续下一轮。",
                20, 40
        );

        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(getterChunk, new float[]{1.0f, 0.0f}),
                new VectorStore.CodeChunkEntry(agentChunk, new float[]{0.80f, 0.20f})
        ));

        EmbeddingClient stubClient = new EmbeddingClient("ollama", "stub", "http://localhost", "") {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };

        try (CodeRetriever retriever = new CodeRetriever(TEST_PROJECT, stubClient)) {
            List<VectorStore.SearchResult> results = retriever.hybridSearch("Agent的ReAct循环是怎么实现的", 5);

            assertFalse(results.isEmpty());
            assertEquals("Agent.run(String userInput)", results.get(0).name());
        }
    }

    @Test
    void markStaleFlagsFilesWhoseContentChangedAfterIndexing(@TempDir Path tempDir) throws Exception {
        String previous = System.setProperty("paicli.rag.dir", tempDir.resolve("rag").toString());
        try {
            Path project = tempDir.resolve("proj");
            Files.createDirectories(project);
            Path tracked = project.resolve("Tracked.java");
            Files.writeString(tracked, "class Tracked {}\n");

            try (VectorStore writer = new VectorStore(project.toString())) {
                writer.upsertFileHash(tracked.toString(), FileHashes.sha256Hex(tracked));
            }

            try (CodeRetriever retriever = new CodeRetriever(project.toString(), new StubEmbeddingClient())) {
                List<VectorStore.SearchResult> results = List.of(
                        new VectorStore.SearchResult(tracked.toString(), "file", "Tracked.java",
                                "class Tracked {}", 0.9));

                List<VectorStore.SearchResult> fresh = retriever.markStale(results);
                assertFalse(fresh.get(0).stale(), "内容未变不应标记 stale");

                Files.writeString(tracked, "class Tracked { int changed = 1; }\n");
                List<VectorStore.SearchResult> stale = retriever.markStale(results);
                assertTrue(stale.get(0).stale(), "内容变更应标记 stale");

                List<VectorStore.SearchResult> ghost = List.of(
                        new VectorStore.SearchResult(project.resolve("Ghost.java").toString(), "file",
                                "Ghost", "x", 0.5));
                assertTrue(retriever.markStale(ghost).get(0).stale(), "文件不存在应视为 stale");
            }
        } finally {
            if (previous != null) {
                System.setProperty("paicli.rag.dir", previous);
            } else {
                System.clearProperty("paicli.rag.dir");
            }
        }
    }
}
