package com.paicli.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrieverTest {
    @TempDir Path tempDir;
    private LongTermMemory longTerm;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        longTerm = new LongTermMemory(tempDir.toFile());
        retriever = new MemoryRetriever(longTerm);
    }

    @Test
    void shouldRetrieveFromLongTerm() {
        longTerm.store(new MemoryEntry("f1", "用户偏好：喜欢用Spring Boot", MemoryEntry.MemoryType.FACT, null, 10));
        assertFalse(retriever.retrieve("Spring Boot", 5).isEmpty());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp", MemoryEntry.MemoryType.FACT, null, 10));
        String context = retriever.buildContextForQuery("项目路径", 200);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("/home/dev/myapp"));
    }

    @Test
    void shouldNotTreatCurrentConversationAsRetrievableMemory() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用中文交流", MemoryEntry.MemoryType.FACT, null, 10));
        assertTrue(retriever.buildContextForQuery("新建一个第六期的文件夹，里面有一个test.txt文件", 200).isEmpty());
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        assertTrue(retriever.retrieve("Spring Boot", 5).isEmpty());
    }

    @Test
    void shouldRetrieveChineseByPhraseFragments() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));
        var results = retriever.retrieve("偏好设置", 5);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.get(0).getId());
    }

    @Test
    void shouldInjectOnlyGlobalAndCurrentProjectLongTermMemory() {
        longTerm.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));
        String context = retriever.buildContextForQuery("项目 使用", 300, "/repo/current");
        assertTrue(context.contains("当前项目使用 Java 17"));
        assertFalse(context.contains("其他项目使用 Python"));
    }

    @Test
    void shouldRankHigherTermFrequencyFirst() {
        longTerm.store(new MemoryEntry("frequent", "redis 缓存服务，缓存层依赖 redis，redis 集群",
                MemoryEntry.MemoryType.FACT, null, 10));
        longTerm.store(new MemoryEntry("once", "redis 是一个键值数据库",
                MemoryEntry.MemoryType.FACT, null, 10));
        var results = retriever.retrieve("redis", 5);
        assertEquals("frequent", results.get(0).getId());
    }

    @Test
    void shouldNormalizeByDocumentLength() {
        longTerm.store(new MemoryEntry("short", "部署使用 docker",
                MemoryEntry.MemoryType.FACT, null, 10));
        longTerm.store(new MemoryEntry("long", "docker 是一个容器运行时平台，可以帮助开发者构建、发布和运行"
                        + "分布式应用，团队也用它做持续集成和自动化测试环境",
                MemoryEntry.MemoryType.FACT, null, 10));
        var results = retriever.retrieve("docker", 5);
        assertEquals("short", results.get(0).getId());
    }

    @Test
    void shouldUpweightRareTermsOverCommonOnes() {
        longTerm.store(new MemoryEntry("common1", "测试 工具", MemoryEntry.MemoryType.FACT, null, 10));
        longTerm.store(new MemoryEntry("common2", "测试 框架", MemoryEntry.MemoryType.FACT, null, 10));
        longTerm.store(new MemoryEntry("common3", "测试 环境", MemoryEntry.MemoryType.FACT, null, 10));
        longTerm.store(new MemoryEntry("rare", "kubernetes 部署脚本", MemoryEntry.MemoryType.FACT, null, 10));
        var results = retriever.retrieve("测试 kubernetes", 5);
        assertEquals("rare", results.get(0).getId());
    }
}
