package com.paicli.rag;

import java.io.IOException;
import java.util.List;

/**
 * 测试桩：跳过真实 embedding API，按文本长度生成可区分的向量。
 * /index 的批量路径走 embedAll，这里一并覆盖，避免真实网络调用。
 */
class StubEmbeddingClient extends EmbeddingClient {

    StubEmbeddingClient() {
        super("ollama", "stub", "http://localhost:0", "");
    }

    @Override
    public float[] embed(String text) {
        return new float[]{text == null ? 0f : text.length(), 1.0f};
    }

    @Override
    public List<float[]> embedAll(List<String> texts) throws IOException {
        return texts.stream().map(text -> new float[]{text == null ? 0f : text.length(), 1.0f}).toList();
    }
}
