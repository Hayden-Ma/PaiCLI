package com.paicli.rag;

/**
 * 测试桩：跳过真实 embedding API，按文本长度生成可区分的向量。
 */
class StubEmbeddingClient extends EmbeddingClient {

    StubEmbeddingClient() {
        super("ollama", "stub", "http://localhost:0", "");
    }

    @Override
    public float[] embed(String text) {
        return new float[]{text == null ? 0f : text.length(), 1.0f};
    }
}
