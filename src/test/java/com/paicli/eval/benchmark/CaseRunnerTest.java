package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/** CaseRunner 端到端（fake LLM + 真 JVM verifier）：通过 / 不通过 / 无 verifier 三条路径。 */
class CaseRunnerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldPassWhenAgentWritesExpectedContent() throws Exception {
        SuiteDefinition suite = suiteWithVerifier(CHECK_JAVA);
        LlmClient client = new ScriptedClient(
                writeToolCall("answer.txt", "42"),
                finalAnswer("done"));

        CaseRunner.EpisodeResult result = runCase(suite, client, 1);

        assertFalse(result.isInfraError());
        assertTrue(result.passed());
        assertEquals(0, result.verifierExitCode());
        assertNotNull(result.metrics());
        assertTrue(result.metrics().calls() >= 2);
    }

    @Test
    void shouldFailWhenAgentWritesWrongContent() throws Exception {
        SuiteDefinition suite = suiteWithVerifier(CHECK_JAVA);
        LlmClient client = new ScriptedClient(
                writeToolCall("answer.txt", "43"),
                finalAnswer("done"));

        CaseRunner.EpisodeResult result = runCase(suite, client, 1);

        assertFalse(result.isInfraError());
        assertFalse(result.passed());
        assertNotNull(result.verifierExitCode());
        assertNotEquals(0, result.verifierExitCode());
    }

    @Test
    void shouldAutoPassWithoutVerifier() throws Exception {
        SuiteDefinition suite = suiteWithVerifierNone();
        LlmClient client = new ScriptedClient(finalAnswer("nothing to do"));

        CaseRunner.EpisodeResult result = runCase(suite, client, 1);

        assertFalse(result.isInfraError());
        assertTrue(result.passed());
        assertNull(result.verifierExitCode());
    }

    @Test
    void shouldReportInfraErrorWhenFixtureMissing() throws Exception {
        Path suiteFile = tempDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[{"id":"case-a","title":"a","category":"c",
                "level":"L1","weight":100,"mode":"react","fixturePath":"fixtures/missing",
                "prompt":"p","verifierType":"none","verifierCommand":[],"status":"active"}]}
                """);
        SuiteDefinition suite = SuiteDefinition.load(suiteFile);

        CaseRunner runner = new CaseRunner(new ScriptedClient(finalAnswer("x")), CaseRunner.Options.defaults());
        BenchmarkArtifactStore store = openStore();
        CaseRunner.EpisodeResult result = runner.run(suite, suite.activeCases().get(0), 1, store);
        assertTrue(result.isInfraError());
    }

    @Test
    void shouldExposeToolsEvenWhenPromptDoesNotStartWithVerb() throws Exception {
        // 回归：TurnToolPolicy 启发式曾把名词开头的任务 prompt 误判为非任务，
        // 导致模型一个工具都拿不到（exposedTools=0）。bench 必须强制 actionable。
        Path suiteDir = tempDir.resolve("suite-noverb-" + System.nanoTime());
        Files.createDirectories(suiteDir.resolve("fixtures/case-a"));
        Files.writeString(suiteDir.resolve("fixtures/case-a/README.md"), "fixture");
        Path suiteFile = suiteDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[{"id":"case-a","title":"a","category":"c",
                "level":"L1","weight":100,"mode":"react","fixturePath":"fixtures/case-a",
                "prompt":"工作区 src/Calculator.java 的 subtract 方法行为有 bug，请修复它。",
                "verifierType":"none","verifierCommand":[],"status":"active"}]}
                """);
        SuiteDefinition suite = SuiteDefinition.load(suiteFile);
        RecordingToolsClient client = new RecordingToolsClient(finalAnswer("done"));

        CaseRunner runner = new CaseRunner(client, CaseRunner.Options.defaults());
        BenchmarkArtifactStore store = openStore();
        CaseRunner.EpisodeResult result = runner.run(suite, suite.activeCases().get(0), 1, store);

        assertFalse(result.isInfraError());
        assertFalse(client.capturedTools.isEmpty());
        assertTrue(client.capturedTools.get(0).size() > 0, "第一轮必须向模型暴露工具定义");
    }

    private static final class RecordingToolsClient extends ScriptedClient {
        private final List<List<Tool>> capturedTools = new java.util.ArrayList<>();

        private RecordingToolsClient(ChatResponse... responses) {
            super(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            capturedTools.add(tools == null ? List.of() : List.copyOf(tools));
            return super.chat(messages, tools, listener);
        }
    }

    @Test
    void shouldApplyAdditiveTrajectoryRulesWhenPresent() throws Exception {
        // 叠加层：verifierType=none 本会自动通过，但存在 hidden/{caseId}/trajectory.json
        // 时追加轨迹判定——这里禁止 write_file，脚本模型却调用了它，应判失败。
        Path suiteDir = tempDir.resolve("suite-additive-" + System.nanoTime());
        Files.createDirectories(suiteDir.resolve("fixtures/case-a"));
        Files.writeString(suiteDir.resolve("fixtures/case-a/README.md"), "fixture");
        Path hidden = suiteDir.resolve("hidden/case-a");
        Files.createDirectories(hidden);
        Files.writeString(hidden.resolve("trajectory.json"),
                "{\"mustNotCall\":[\"write_file\"]}");
        Path suiteFile = suiteDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[{"id":"case-a","title":"a","category":"c",
                "level":"L1","weight":100,"mode":"react","fixturePath":"fixtures/case-a",
                "prompt":"p","verifierType":"none","verifierCommand":[],"status":"active"}]}
                """);
        SuiteDefinition suite = SuiteDefinition.load(suiteFile);
        CaseRunner runner = new CaseRunner(
                new ScriptedClient(writeToolCall("x.txt", "1"), finalAnswer("done")),
                CaseRunner.Options.defaults());
        BenchmarkArtifactStore store = openStore();

        CaseRunner.EpisodeResult result = runner.run(suite, suite.activeCases().get(0), 1, store);

        assertFalse(result.isInfraError());
        assertFalse(result.passed());
        assertTrue(result.verifierOutput().contains("[trajectory]"));
    }

    @Test
    void shouldAutoPassNoneVerifierWithoutTrajectoryRules() throws Exception {
        // 无 trajectory.json 时保持原行为：NONE 自动通过
        SuiteDefinition suite = suiteWithVerifierNone();
        LlmClient client = new ScriptedClient(finalAnswer("nothing to do"));

        CaseRunner.EpisodeResult result = runCase(suite, client, 1);

        assertFalse(result.isInfraError());
        assertTrue(result.passed());
    }

    @Test
    void shouldPassWhenJudgeAcceptsArtifact() throws Exception {
        SuiteDefinition suite = suiteWithLlmJudge();
        LlmClient agent = new ScriptedClient(
                writeToolCall("answer.txt", "review: dividePrice 除零风险..."),
                finalAnswer("done"));
        LlmClient judge = new ScriptedClient(judgeResponse(4, 4, "[]"));

        CaseRunner runner = new CaseRunner(agent, judge, CaseRunner.Options.defaults());
        CaseRunner.EpisodeResult result = runCase(suite, runner);

        assertFalse(result.isInfraError());
        assertTrue(result.passed());
        assertTrue(result.verifierOutput().contains("judge weighted=80.0"));
    }

    @Test
    void shouldFailWhenJudgeRejectsArtifact() throws Exception {
        SuiteDefinition suite = suiteWithLlmJudge();
        LlmClient agent = new ScriptedClient(
                writeToolCall("answer.txt", "shallow review"),
                finalAnswer("done"));
        LlmClient judge = new ScriptedClient(judgeResponse(2, 2, "[]"));

        CaseRunner runner = new CaseRunner(agent, judge, CaseRunner.Options.defaults());
        CaseRunner.EpisodeResult result = runCase(suite, runner);

        assertFalse(result.isInfraError());
        assertFalse(result.passed());
        assertTrue(result.verifierOutput().contains("judge weighted=40.0"));
    }

    @Test
    void shouldFailWhenJudgeArtifactMissing() throws Exception {
        SuiteDefinition suite = suiteWithLlmJudge();
        LlmClient agent = new ScriptedClient(finalAnswer("done"));
        LlmClient judge = new ScriptedClient(judgeResponse(4, 4, "[]"));

        CaseRunner runner = new CaseRunner(agent, judge, CaseRunner.Options.defaults());
        CaseRunner.EpisodeResult result = runCase(suite, runner);

        assertFalse(result.isInfraError());
        assertFalse(result.passed());
        assertTrue(result.verifierOutput().contains("judge artifact missing"));
    }

    @Test
    void shouldFailWhenNoJudgeClientConfigured() throws Exception {
        SuiteDefinition suite = suiteWithLlmJudge();
        LlmClient agent = new ScriptedClient(
                writeToolCall("answer.txt", "review"),
                finalAnswer("done"));

        CaseRunner runner = new CaseRunner(agent, null, CaseRunner.Options.defaults());
        CaseRunner.EpisodeResult result = runCase(suite, runner);

        assertFalse(result.isInfraError());
        assertFalse(result.passed());
        assertTrue(result.verifierOutput().contains("未配置 judgeClient"));
    }

    @Test
    void shouldRejectLlmJudgeCaseWithoutRubrics() throws Exception {
        Path suiteDir = tempDir.resolve("suite-judge-norubrics-" + System.nanoTime());
        Files.createDirectories(suiteDir.resolve("fixtures/case-a"));
        Files.writeString(suiteDir.resolve("fixtures/case-a/README.md"), "fixture");
        Path suiteFile = suiteDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[{"id":"case-a","title":"a","category":"c",
                "level":"L1","weight":100,"mode":"react","fixturePath":"fixtures/case-a",
                "prompt":"p","verifierType":"llm_judge","verifierCommand":[],"status":"active"}]}
                """);
        assertThrows(IllegalArgumentException.class, () -> SuiteDefinition.load(suiteFile));
    }

    private CaseRunner.EpisodeResult runCase(SuiteDefinition suite, CaseRunner runner) throws IOException {
        BenchmarkArtifactStore store = openStore();
        return runner.run(suite, suite.activeCases().get(0), 1, store);
    }

    private SuiteDefinition suiteWithLlmJudge() throws IOException {
        Path suiteDir = tempDir.resolve("suite-judge-" + System.nanoTime());
        Files.createDirectories(suiteDir.resolve("fixtures/case-a"));
        Files.writeString(suiteDir.resolve("fixtures/case-a/README.md"), "fixture");
        Path suiteFile = suiteDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[{"id":"case-a","title":"a","category":"c",
                "level":"L1","weight":100,"mode":"react","fixturePath":"fixtures/case-a",
                "prompt":"写审查报告到 answer.txt","verifierType":"llm_judge",
                "verifierCommand":[],"judgeArtifact":"answer.txt","judgePassThreshold":60,
                "rubrics":[
                  {"name":"correctness","criteria":"是否准确","weight":3,"hardGate":true},
                  {"name":"completeness","criteria":"是否完整","weight":2,"hardGate":false}
                ],
                "status":"active"}]}
                """);
        return SuiteDefinition.load(suiteFile);
    }

    /** 构造 judge 的 JSON 响应：两个 rubric 各给 score 分。 */
    private static LlmClient.ChatResponse judgeResponse(int correctness, int completeness, String hardFailures) {
        String content = "{\"scores\":["
                + "{\"name\":\"correctness\",\"score\":" + correctness + ",\"evidence\":\"e1\"},"
                + "{\"name\":\"completeness\",\"score\":" + completeness + ",\"evidence\":\"e2\"}],"
                + "\"hardFailures\":" + hardFailures + ",\"summary\":\"ok\"}";
        return new LlmClient.ChatResponse("assistant", content, null, 100, 10);
    }

    private BenchmarkArtifactStore openStore() throws IOException {
        return BenchmarkArtifactStore.create(tempDir.resolve("out"), "test-run-" + System.nanoTime());
    }

    private CaseRunner.EpisodeResult runCase(SuiteDefinition suite, LlmClient client, int repeat)
            throws IOException {
        CaseRunner runner = new CaseRunner(client, CaseRunner.Options.defaults());
        BenchmarkArtifactStore store = openStore();
        return runner.run(suite, suite.activeCases().get(0), repeat, store);
    }

    private SuiteDefinition suiteWithVerifier(String checkJava) throws IOException {
        Path suiteDir = tempDir.resolve("suite-with-verifier-" + System.nanoTime());
        Files.createDirectories(suiteDir.resolve("fixtures/case-a"));
        Files.writeString(suiteDir.resolve("fixtures/case-a/README.md"), "fixture");
        Path hidden = suiteDir.resolve("hidden");
        Files.createDirectories(hidden);
        Files.writeString(hidden.resolve("Check.java"), checkJava, StandardCharsets.UTF_8);
        Path suiteFile = suiteDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[{"id":"case-a","title":"a","category":"c",
                "level":"L1","weight":100,"mode":"react","fixturePath":"fixtures/case-a",
                "prompt":"把答案写入 answer.txt","verifierType":"command",
                "verifierCommand":["java","hidden/Check.java","{workspace}"],"status":"active"}]}
                """);
        return SuiteDefinition.load(suiteFile);
    }

    private SuiteDefinition suiteWithVerifierNone() throws IOException {
        Path suiteDir = tempDir.resolve("suite-none-" + System.nanoTime());
        Files.createDirectories(suiteDir.resolve("fixtures/case-a"));
        Files.writeString(suiteDir.resolve("fixtures/case-a/README.md"), "fixture");
        Path suiteFile = suiteDir.resolve("suite.json");
        Files.writeString(suiteFile, """
                {"version":"1.0","name":"t","cases":[{"id":"case-a","title":"a","category":"c",
                "level":"L1","weight":100,"mode":"react","fixturePath":"fixtures/case-a",
                "prompt":"p","verifierType":"none","verifierCommand":[],"status":"active"}]}
                """);
        return SuiteDefinition.load(suiteFile);
    }

    private static final String CHECK_JAVA = """
            import java.nio.file.*;

            public class Check {
                public static void main(String[] args) throws Exception {
                    Path workspace = Path.of(args[0]);
                    Path answer = workspace.resolve("answer.txt");
                    if (!Files.exists(answer)) {
                        System.out.println("answer.txt 不存在");
                        System.exit(1);
                    }
                    String content = Files.readString(answer).trim();
                    if (!"42".equals(content)) {
                        System.out.println("期望 42，实际 " + content);
                        System.exit(2);
                    }
                    System.out.println("OK");
                    System.exit(0);
                }
            }
            """;

    private static LlmClient.ChatResponse writeToolCall(String path, String content) {
        String arguments;
        try {
            arguments = MAPPER.writeValueAsString(java.util.Map.of("path", path, "content", content));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new LlmClient.ChatResponse("assistant", "", null,
                List.of(new LlmClient.ToolCall("call-1",
                        new LlmClient.ToolCall.Function("write_file", arguments))),
                100, 10);
    }

    private static LlmClient.ChatResponse finalAnswer(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 10);
    }

    private static class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();

        ScriptedClient(ChatResponse... responses) {
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
