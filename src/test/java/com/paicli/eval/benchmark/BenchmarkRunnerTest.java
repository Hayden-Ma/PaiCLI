package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/** BenchmarkRunner 编排测试：pass@1 / pass@k 聚合与产物落盘。 */
class BenchmarkRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldAggregatePassRatesAcrossRepeats() throws Exception {
        // case-a：2 次采样全过（先 write_file 再收尾）；case-b：verifierType=none 自动过
        SuiteDefinition suite = suite();
        LlmClient client = new ScriptedClient(
                writeToolCall("answer.txt", "42"), finalAnswer("a done 1"),
                writeToolCall("answer.txt", "42"), finalAnswer("a done 2"),
                finalAnswer("b done"), finalAnswer("b done"));
        Path outputRoot = tempDir.resolve("out");

        BenchmarkRunner runner = new BenchmarkRunner(client, CaseRunner.Options.defaults());
        BenchmarkRunner.SuiteReport report = runner.run(suite, 2, outputRoot);

        assertEquals(2, report.cases().size());
        assertTrue(report.allPassedAtLeastOnce());
        assertEquals(100.0, report.passAt1Percent());
        assertEquals(100.0, report.passAtKPercent());
        assertEquals(100.0, report.passHatKPercent());
        assertEquals(100.0, report.weightedScore());
        assertTrue(report.cases().get(0).passAt1());
        assertTrue(report.cases().get(0).strictSuccess());
    }

    @Test
    void shouldScorePartialPassAndFlagPassAtK() throws Exception {
        SuiteDefinition suite = suite();
        // case-a 两次采样都交白卷（verifier 判失败）；case-b 两次都自动过（strictSuccess）
        LlmClient failingClient = new ScriptedClient(
                finalAnswer("give up"), finalAnswer("give up"),
                finalAnswer("b done"), finalAnswer("b done"));
        Path outputRoot = tempDir.resolve("out-fail");

        BenchmarkRunner runner = new BenchmarkRunner(failingClient, CaseRunner.Options.defaults());
        BenchmarkRunner.SuiteReport report = runner.run(suite, 2, outputRoot);

        BenchmarkRunner.CaseReport caseA = report.cases().get(0);
        assertEquals(0.0, caseA.score());
        assertFalse(caseA.passAt1());
        assertFalse(caseA.passAtK());
        assertEquals(100.0, report.cases().get(1).score());
        assertFalse(report.allPassedAtLeastOnce());
        assertEquals(50.0, report.passAt1Percent());
        assertEquals(50.0, report.passAtKPercent());
        assertEquals(50.0, report.passHatKPercent());
    }

    @Test
    void shouldWritePassHatKAndEfficiencyIntoReport() throws Exception {
        SuiteDefinition suite = suite();
        LlmClient client = new ScriptedClient(
                writeToolCall("answer.txt", "42"), finalAnswer("a"),
                writeToolCall("answer.txt", "42"), finalAnswer("a"),
                finalAnswer("b"), finalAnswer("b"));
        Path outputRoot = tempDir.resolve("out-report");

        new BenchmarkRunner(client, CaseRunner.Options.defaults()).run(suite, 2, outputRoot);

        Path runDir = Files.list(outputRoot).findFirst().orElseThrow();
        String report = Files.readString(runDir.resolve("report.md"));
        assertTrue(report.contains("pass^k"));
        assertTrue(report.contains("avgCalls"));
        assertTrue(report.contains("avgTools"));
        assertTrue(report.contains("passσ"));
        assertTrue(report.contains("callsσ"));
        String summary = Files.readString(runDir.resolve("summary.json"));
        assertTrue(summary.contains("passHatKPercent"));
        assertTrue(summary.contains("passRateStdDev"));
    }

    @Test
    void shouldComputeVarianceAcrossRepeats() throws Exception {
        SuiteDefinition suite = suite();
        // case-a: 第 1 次采样成功（2 次 LLM 调用、1 次工具调用），第 2 次交白卷（1 次调用、0 工具）；
        // case-b: 两次都过。方差只对 case-a 有意义。
        LlmClient client = new ScriptedClient(
                writeToolCall("answer.txt", "42"), finalAnswer("a ok"),
                finalAnswer("a fail"),
                finalAnswer("b"), finalAnswer("b"));
        Path outputRoot = tempDir.resolve("out-var");

        BenchmarkRunner runner = new BenchmarkRunner(client, CaseRunner.Options.defaults());
        BenchmarkRunner.SuiteReport report = runner.run(suite, 2, outputRoot);

        BenchmarkRunner.CaseReport caseA = report.cases().get(0);
        assertEquals(50.0, caseA.score());
        // pass 指示 {100,0}：样本标准差 = sqrt(((50)^2 + (50)^2)/1) = 70.71
        assertEquals(70.71067811865476, caseA.passRateStdDev(), 1e-9);
        // LLM 调用 {2,1}：sqrt(0.5) ≈ 0.7071
        assertEquals(0.7071067811865476, caseA.llmCallsStdDev(), 1e-9);
        // 工具调用 {1,0}：sqrt(0.5) ≈ 0.7071
        assertEquals(0.7071067811865476, caseA.toolCallsStdDev(), 1e-9);
        assertTrue(caseA.durationMsStdDev() >= 0);
        // case-b 两次采样行为一致，方差为 0
        assertEquals(0.0, report.cases().get(1).passRateStdDev());
        assertEquals(0.0, report.cases().get(1).llmCallsStdDev());
    }

    @Test
    void shouldPersistArtifactsTree() throws Exception {
        SuiteDefinition suite = suite();
        LlmClient client = new ScriptedClient(
                writeToolCall("answer.txt", "42"), finalAnswer("a"),
                writeToolCall("answer.txt", "42"), finalAnswer("a"),
                finalAnswer("b"), finalAnswer("b"));
        Path outputRoot = tempDir.resolve("out-artifacts");

        new BenchmarkRunner(client, CaseRunner.Options.defaults()).run(suite, 2, outputRoot);

        Path runDir = Files.list(outputRoot).findFirst().orElseThrow();
        assertTrue(Files.exists(runDir.resolve("manifest.json")));
        assertTrue(Files.exists(runDir.resolve("summary.json")));
        assertTrue(Files.exists(runDir.resolve("report.md")));
        assertTrue(Files.exists(runDir.resolve("cases/case-a/models/scripted-model/repeat-001/run.json")));
        assertTrue(Files.exists(runDir.resolve("cases/case-a/models/scripted-model/repeat-001/raw.jsonl")));
        assertTrue(Files.exists(runDir.resolve("cases/case-a/models/scripted-model/repeat-001/answer.md")));
        assertTrue(Files.exists(runDir.resolve("cases/case-b/models/scripted-model/repeat-002/run.json")));
    }

    /** 两个 case：case-a 用真 JVM verifier 校验 answer.txt；case-b 无 verifier 自动过。 */
    private SuiteDefinition suite() throws IOException {
        Path suiteDir = tempDir.resolve("suite-" + System.nanoTime());
        Files.createDirectories(suiteDir.resolve("fixtures/case-a"));
        Files.writeString(suiteDir.resolve("fixtures/case-a/README.md"), "fixture");
        Files.createDirectories(suiteDir.resolve("fixtures/case-b"));
        Files.writeString(suiteDir.resolve("fixtures/case-b/README.md"), "fixture");
        Files.createDirectories(suiteDir.resolve("hidden"));
        Files.writeString(suiteDir.resolve("hidden/Check.java"), CHECK_JAVA);
        Path suiteFile = suiteDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[
                {"id":"case-a","title":"a","category":"c","level":"L1","weight":50,"mode":"react",
                 "fixturePath":"fixtures/case-a","prompt":"把答案写入 answer.txt","verifierType":"command",
                 "verifierCommand":["java","hidden/Check.java","{workspace}"],"status":"active"},
                {"id":"case-b","title":"b","category":"c","level":"L2","weight":50,"mode":"react",
                 "fixturePath":"fixtures/case-b","prompt":"p","verifierType":"none",
                 "verifierCommand":[],"status":"active"}]}
                """);
        return SuiteDefinition.load(suiteFile);
    }

    private static final String CHECK_JAVA = """
            import java.nio.file.*;

            public class Check {
                public static void main(String[] args) throws Exception {
                    Path answer = Path.of(args[0]).resolve("answer.txt");
                    if (Files.exists(answer) && "42".equals(Files.readString(answer).trim())) {
                        System.exit(0);
                    }
                    System.exit(1);
                }
            }
            """;

    private static LlmClient.ChatResponse finalAnswer(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 10);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LlmClient.ChatResponse writeToolCall(String path, String content) {
        String arguments;
        try {
            arguments = MAPPER.writeValueAsString(java.util.Map.of("path", path, "content", content));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new LlmClient.ChatResponse("assistant", "", null,
                List.of(new LlmClient.ToolCall("call-" + System.nanoTime(),
                        new LlmClient.ToolCall.Function("write_file", arguments))),
                100, 10);
    }

    private static final class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();

        private ScriptedClient(ChatResponse... responses) {
            for (ChatResponse response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("脚本响应已耗尽");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }

        @Override
        public String getProviderName() {
            return "scripted";
        }

        @Override
        public int maxContextWindow() {
            return 128_000;
        }
    }
}
