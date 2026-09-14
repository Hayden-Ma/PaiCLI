package com.paicli.rag;

import java.nio.file.Paths;
import java.sql.SQLException;

/**
 * 检索后端工厂：按 RAG_STORE 选择实现。
 * <ul>
 *   <li>sqlite（默认）：本地零配置，离线可用</li>
 *   <li>es：远程 ElasticsearchStore，RAG_STORE=es 启用；ES_URL / ES_USERNAME / ES_PASSWORD /
 *       ES_API_KEY / ES_EMBEDDING_DIMS（默认 1024，对应 bge-m3）可配</li>
 * </ul>
 */
public final class RetrievalStores {

    private RetrievalStores() {
    }

    public static RetrievalStore create(String projectPath) throws SQLException {
        String store = env("RAG_STORE", "sqlite").trim().toLowerCase();
        if ("es".equals(store) || "elasticsearch".equals(store)) {
            int dims;
            try {
                dims = Integer.parseInt(env("ES_EMBEDDING_DIMS", "1024").trim());
            } catch (NumberFormatException e) {
                dims = 1024;
            }
            return new ElasticsearchStore(
                    Paths.get(projectPath).toAbsolutePath().normalize().toString(),
                    env("ES_URL", "http://localhost:9200"),
                    env("ES_USERNAME", null),
                    env("ES_PASSWORD", null),
                    env("ES_API_KEY", null),
                    dims);
        }
        return new VectorStore(projectPath);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            value = System.getProperty(key);
        }
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value;
    }
}
