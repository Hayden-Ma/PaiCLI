package com.paicli.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex();
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        System.setProperty("paicli.rag.dir", "/tmp/paicli-test-rag-index");
        CodeIndex indexer = new CodeIndex();
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag");

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    @Test
    void incrementalIndexSkipsUnchangedAndReindexesChanged(@TempDir Path tempDir) throws Exception {
        String previous = System.setProperty("paicli.rag.dir", tempDir.resolve("rag").toString());
        try {
            Path project = tempDir.resolve("proj");
            Files.createDirectories(project);
            Path demo = project.resolve("Demo.java");
            Files.writeString(demo, "public class Demo {\n    void hi() {\n        System.out.println(1);\n    }\n}\n");

            CodeIndex indexer = new CodeIndex(new StubEmbeddingClient(), CodeIndex.ProgressListener.noop());

            // 首次索引：全部重嵌
            CodeIndex.IndexResult first = indexer.index(project.toString());
            assertEquals(1, first.reindexedFiles(), "首次索引应重嵌 1 个文件");
            assertEquals(0, first.skippedFiles());
            assertEquals(0, first.removedFiles());
            assertTrue(first.chunkCount() > 0);

            // 二次索引：内容未变，全部跳过
            CodeIndex.IndexResult second = indexer.index(project.toString());
            assertEquals(1, second.skippedFiles(), "未变更文件应跳过");
            assertEquals(0, second.reindexedFiles());
            assertEquals(first.chunkCount(), second.chunkCount(), "跳过不应改变块数");

            // 修改文件后：仅重嵌该文件
            Files.writeString(demo, "public class Demo {\n    void hi() {\n        System.out.println(2);\n    }\n}\n");
            CodeIndex.IndexResult third = indexer.index(project.toString());
            assertEquals(1, third.reindexedFiles(), "变更文件应重嵌");
            assertEquals(0, third.skippedFiles());
            assertEquals(first.chunkCount(), third.chunkCount(), "结构未变，块数应一致");

            // 删除文件后：清理其索引数据
            Files.delete(demo);
            CodeIndex.IndexResult fourth = indexer.index(project.toString());
            assertEquals(1, fourth.removedFiles(), "已删除文件应被清理");
            assertEquals(0, fourth.chunkCount());
        } finally {
            if (previous != null) {
                System.setProperty("paicli.rag.dir", previous);
            } else {
                System.clearProperty("paicli.rag.dir");
            }
        }
    }

    @Test
    void forceRebuildIgnoresExistingHashes(@TempDir Path tempDir) throws Exception {
        String previous = System.setProperty("paicli.rag.dir", tempDir.resolve("rag").toString());
        try {
            Path project = tempDir.resolve("proj");
            Files.createDirectories(project);
            Files.writeString(project.resolve("Solo.java"), "public class Solo {\n    void go() {\n    }\n}\n");

            CodeIndex indexer = new CodeIndex(new StubEmbeddingClient(), CodeIndex.ProgressListener.noop());
            indexer.index(project.toString());

            CodeIndex.IndexResult rebuilt = indexer.index(project.toString(), true);
            assertEquals(1, rebuilt.reindexedFiles(), "全量重建应重嵌所有文件");
            assertEquals(0, rebuilt.skippedFiles(), "全量重建不应跳过任何文件");
            assertTrue(rebuilt.chunkCount() > 0);

            CodeIndex.IndexResult afterRebuild = indexer.index(project.toString());
            assertEquals(1, afterRebuild.skippedFiles(), "重建后增量应恢复跳过");
        } finally {
            if (previous != null) {
                System.setProperty("paicli.rag.dir", previous);
            } else {
                System.clearProperty("paicli.rag.dir");
            }
        }
    }
}
