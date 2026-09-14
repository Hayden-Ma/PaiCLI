package com.paicli.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultFormatterTest {

    @Test
    void cliFormatIncludesReadableSummaryBeforeResults() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "/Users/itwanger/Documents/GitHub/paicli/src/main/java/com/paicli/agent/Agent.java",
                        "method",
                        "Agent.run(String userInput)",
                        "ReAct 循环：读取用户输入，思考，调用工具，再继续下一轮。",
                        1.42
                )
        );

        String output = SearchResultFormatter.formatForCli("Agent的ReAct循环是怎么实现的", results);

        assertTrue(output.contains("搜索摘要:"));
        assertTrue(output.contains("最相关的入口是 [method:Agent.run(String userInput)]"));
        assertTrue(output.contains("1. [method:Agent.run(String userInput)]"));
    }

    @Test
    void toolFormatReturnsPointerAndRequiresReadFile() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "src/main/java/com/paicli/agent/Agent.java",
                        "method",
                        "Agent.run(String userInput)",
                        "ReAct 循环：读取用户输入，思考，调用工具，再继续下一轮。",
                        0.88
                )
        );

        String output = SearchResultFormatter.formatForTool("ReAct循环怎么实现", results);

        assertTrue(output.contains("read_file"), "应引导 Agent 用 read_file 读取当前内容");
        assertTrue(output.contains("1. [method:Agent.run(String userInput)]"));
        // 无行号（0）时退化为纯路径指针
        assertTrue(output.contains("→ src/main/java/com/paicli/agent/Agent.java"));
    }

    @Test
    void toolFormatRendersLineRangePointer() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "src/main/java/com/paicli/agent/Agent.java",
                        "method",
                        "Agent.run(String userInput)",
                        "ReAct 循环主体。",
                        0.88,
                        42,
                        68,
                        false
                )
        );

        String output = SearchResultFormatter.formatForTool("ReAct循环", results);

        assertTrue(output.contains("→ src/main/java/com/paicli/agent/Agent.java:42-68"));
    }

    @Test
    void toolFormatMarksStaleResults() {
        List<VectorStore.SearchResult> results = List.of(
                new VectorStore.SearchResult(
                        "src/A.java",
                        "class",
                        "A",
                        "class A {}",
                        0.7,
                        1,
                        1,
                        true
                )
        );

        String output = SearchResultFormatter.formatForTool("A", results);

        assertTrue(output.contains("stale"), "过期结果应有 stale 标记");
        assertTrue(output.contains("预览不可信"));
    }
}
