package com.paicli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalStoresTest {

    private String previous;

    @AfterEach
    void restore() {
        if (previous != null) {
            System.setProperty("RAG_STORE", previous);
        } else {
            System.clearProperty("RAG_STORE");
        }
        System.clearProperty("ES_URL");
        System.clearProperty("ES_EMBEDDING_DIMS");
    }

    @Test
    void defaultsToSqliteStore(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        System.clearProperty("RAG_STORE");
        previous = null;

        try (RetrievalStore store = RetrievalStores.create(tempDir.toString())) {
            assertInstanceOf(VectorStore.class, store);
        }
    }

    @Test
    void esStoreSelectedWhenRagStoreSetToEs() throws Exception {
        previous = System.setProperty("RAG_STORE", "es");
        System.setProperty("ES_URL", "http://localhost:9200");

        try (RetrievalStore store = RetrievalStores.create("/tmp/paicli-factory-test")) {
            assertInstanceOf(ElasticsearchStore.class, store);
        }
    }

    @Test
    void esStoreNormalizesProjectPathLikeSqlite() throws Exception {
        previous = System.setProperty("RAG_STORE", "es");

        try (RetrievalStore a = RetrievalStores.create("src/test/resources/rag");
             RetrievalStore b = RetrievalStores.create("src/test/resources/rag/..\\rag")) {
            // 构造即成功且都是 ES 后端（懒连接，无需网络）；路径归一化一致性由
            // ElasticsearchStore 内部 toAbsolutePath().normalize() 保证
            assertInstanceOf(ElasticsearchStore.class, a);
            assertInstanceOf(ElasticsearchStore.class, b);
        }
    }
}
