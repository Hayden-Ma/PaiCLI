package com.paicli.eval.benchmark;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** TrajectoryVerifier 规则语义测试（mustCallInOrder / mustCallAny / mustNotCall / maxLlmCalls）。 */
class TrajectoryVerifierTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldPassWhenSequenceContainsRequiredSubsequence() throws Exception {
        Path trace = trace("""
                {"status":"SUCCESS","returnedTools":["list_dir"]}
                {"status":"SUCCESS","returnedTools":["read_file"]}
                {"status":"SUCCESS","returnedTools":[]}
                {"status":"SUCCESS","returnedTools":["write_file"]}
                """);
        TrajectoryVerifier.Rules rules = new TrajectoryVerifier.Rules(
                List.of("read_file", "write_file"), null, null, 10);
        assertTrue(TrajectoryVerifier.verify(rules, trace, 4).passed());
    }

    @Test
    void shouldFailWhenRequiredOrderViolated() throws Exception {
        Path trace = trace("""
                {"status":"SUCCESS","returnedTools":["write_file"]}
                {"status":"SUCCESS","returnedTools":["read_file"]}
                """);
        TrajectoryVerifier.Rules rules = new TrajectoryVerifier.Rules(
                List.of("read_file", "write_file"), null, null, null);
        TrajectoryVerifier.TrajectoryResult result = TrajectoryVerifier.verify(rules, trace, 2);
        assertFalse(result.passed());
        assertTrue(result.violations().get(0).contains("mustCallInOrder"));
    }

    @Test
    void shouldFailOnForbiddenTool() throws Exception {
        Path trace = trace("""
                {"status":"SUCCESS","returnedTools":["grep_code","web_search"]}
                """);
        TrajectoryVerifier.Rules rules = new TrajectoryVerifier.Rules(
                null, null, List.of("web_search"), null);
        TrajectoryVerifier.TrajectoryResult result = TrajectoryVerifier.verify(rules, trace, 1);
        assertFalse(result.passed());
        assertTrue(result.violations().get(0).contains("web_search"));
    }

    @Test
    void shouldRequireOneToolFromEachAnyGroup() throws Exception {
        Path trace = trace("""
                {"status":"SUCCESS","returnedTools":["glob_files"]}
                """);
        TrajectoryVerifier.Rules rules = new TrajectoryVerifier.Rules(
                null, List.of(List.of("grep_code", "glob_files"), List.of("read_file")), null, null);
        TrajectoryVerifier.TrajectoryResult result = TrajectoryVerifier.verify(rules, trace, 1);
        assertFalse(result.passed());
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("read_file"));
    }

    @Test
    void shouldFailWhenLlmCallsExceedBudget() throws Exception {
        Path trace = trace("""
                {"status":"SUCCESS","returnedTools":[]}
                """);
        TrajectoryVerifier.Rules rules = new TrajectoryVerifier.Rules(null, null, null, 5);
        TrajectoryVerifier.TrajectoryResult result = TrajectoryVerifier.verify(rules, trace, 6);
        assertFalse(result.passed());
        assertTrue(result.violations().get(0).contains("预算"));
    }

    @Test
    void shouldIgnoreErrorEventsAndLoadRulesFromJson() throws Exception {
        Path trace = trace("""
                {"status":"ERROR","returnedTools":["write_file"],"errorType":"IOException"}
                {"status":"SUCCESS","returnedTools":["read_file","write_file"]}
                """);
        Path rulesFile = tempDir.resolve("trajectory.json");
        Files.writeString(rulesFile, """
                {"mustCallInOrder":["read_file","write_file"],"mustNotCall":["web_search"]}
                """);
        TrajectoryVerifier.Rules rules = TrajectoryVerifier.load(rulesFile);
        assertTrue(TrajectoryVerifier.verify(rules, trace, 2).passed());
    }

    private Path trace(String jsonLines) throws Exception {
        Path file = tempDir.resolve("raw-" + System.nanoTime() + ".jsonl");
        Files.writeString(file, jsonLines);
        return file;
    }
}
