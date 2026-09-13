package com.paicli.eval.benchmark;

import com.paicli.agent.Agent;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.tool.ToolOutput;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 评测执行 worker：单个 case 的单次采样（repeat）。
 *
 * 流程：物化 fixture 工作区 → 构建隔离 Agent（临时长期记忆 + 非交互策略 + 预算上限 +
 * 超时取消）→ 跑一次受限 ReAct → 用 ProcessBuilder 直传参数执行 verifier 判定。
 * verifier 通过（exit 0）即该次采样通过；fixture/环境类失败计为 INFRA_ERROR，
 * 不计入模型得分。
 */
public final class CaseRunner {

    /** 单次采样结果。 */
    public record EpisodeResult(int repeat,
                                boolean passed,
                                boolean timedOut,
                                Integer verifierExitCode,
                                String verifierOutput,
                                String agentResponse,
                                String infraError,
                                TracingLlmClient.Metrics metrics,
                                long durationMs) {
        public boolean isInfraError() {
            return infraError != null && !infraError.isBlank();
        }
    }

    public record Options(int caseTimeoutSeconds,
                          int verifierTimeoutSeconds,
                          int hardMaxIterations,
                          int tokenBudget,
                          boolean keepWorkspaces,
                          List<String> commandAllowlist) {
        public Options {
            commandAllowlist = commandAllowlist == null ? List.of() : List.copyOf(commandAllowlist);
        }

        public static Options defaults() {
            return new Options(600, 120, 40, 400_000, false, List.of());
        }
    }

    private static final int MAX_VERIFIER_OUTPUT_CHARS = 16_000;

    private final LlmClient baseClient;
    private final Options options;

    public CaseRunner(LlmClient baseClient, Options options) {
        this.baseClient = baseClient;
        this.options = options == null ? Options.defaults() : options;
    }

    public EpisodeResult run(SuiteDefinition suite,
                             CaseDefinition caseDefinition,
                             int repeat,
                             BenchmarkArtifactStore artifactStore) {
        long started = System.currentTimeMillis();
        Path workspace = null;
        Path memoryDir = null;
        ScheduledExecutorService watchdog = null;
        CancellationToken token = null;
        try {
            Path fixture = suite.resolveFixture(caseDefinition);
            workspace = CaseWorkspace.materialize(fixture);
            memoryDir = Files.createTempDirectory("paicli-bench-memory-");

            BenchmarkArtifactStore.EpisodeArtifacts episode =
                    artifactStore.episode(caseDefinition.id(), safeModelId(), repeat);

            LongTermMemory longTermMemory = new LongTermMemory(memoryDir.toFile());
            MemoryManager memoryManager = new MemoryManager(
                    baseClient, 1, baseClient.maxContextWindow(), longTermMemory);

            BenchmarkPolicyDecider policyDecider = new BenchmarkPolicyDecider(
                    new BenchmarkPolicyConfig(options.commandAllowlist()));
            // 上游 BenchmarkToolRegistry 按 profile 收紧工具暴露；终端闭环 case 需要
            // execute_command，叠加 suite 级命令白名单（* 结尾前缀匹配）后放行。
            BenchmarkToolProfile profile = options.commandAllowlist().isEmpty()
                    ? BenchmarkToolProfile.FILE_ONLY
                    : BenchmarkToolProfile.LOCAL_COMMAND;
            BenchmarkToolRegistry registry = new BenchmarkToolRegistry(profile) {
                @Override
                public ToolOutput executeToolOutput(String name, String argumentsJson) {
                    BenchmarkPolicyDecider.Decision decision = policyDecider.decide(name, argumentsJson);
                    if (!decision.allowed()) {
                        return ToolOutput.failure("评测策略拒绝: " + decision.reason());
                    }
                    return super.executeToolOutput(name, argumentsJson);
                }
            };
            registry.setProjectPath(workspace.toString());
            registry.setSanitizeCommandEnvironment(true);

            TracingLlmClient tracedClient = new TracingLlmClient(
                    baseClient, episode.directory().resolve("raw.jsonl"));
            Agent agent = new Agent(baseClient, registry, memoryManager);
            agent.setLlmClient(tracedClient);
            agent.setRenderer(new SilentRenderer());
            agent.setReturnFinalResponseWhenStreamed(true);
            agent.setForceActionableTurns(true);

            token = CancellationContext.startRun();
            watchdog = newScheduledWatchdog();
            ScheduledFuture<?> timeoutHandle = watchdog.schedule(
                    token::cancel, options.caseTimeoutSeconds(), TimeUnit.SECONDS);

            String response = runWithBudget(agent, caseDefinition.prompt());
            boolean timedOut = timeoutHandle.isDone() && !timeoutHandle.isCancelled();
            long durationMs = System.currentTimeMillis() - started;
            int llmCalls = tracedClient.metrics() == null ? 0 : tracedClient.metrics().calls();
            Path rawTrace = episode.directory().resolve("raw.jsonl");

            VerifierOutcome verifier = runVerifier(suite, caseDefinition, workspace, rawTrace, llmCalls);
            EpisodeResult result = new EpisodeResult(
                    repeat,
                    verifier.passed(),
                    timedOut,
                    verifier.exitCode(),
                    verifier.output(),
                    response == null ? "" : response,
                    null,
                    tracedClient.metrics(),
                    durationMs);

            writeArtifacts(episode, caseDefinition, workspace, result);
            return result;
        } catch (IOException | RuntimeException e) {
            long durationMs = System.currentTimeMillis() - started;
            return new EpisodeResult(repeat, false, false, null, "",
                    "", "episode infra failure: " + e.getMessage(), null, durationMs);
        } finally {
            if (watchdog != null) {
                watchdog.shutdownNow();
            }
            if (token != null) {
                CancellationContext.clear(token);
            }
            if (!options.keepWorkspaces()) {
                CaseWorkspace.deleteRecursively(workspace);
                CaseWorkspace.deleteRecursively(memoryDir);
            }
        }
    }

    /**
     * 评测运行使用硬轮数 + token 预算兜底（AgentBudget 从系统属性读取，
     * 在 run 前设置、run 后恢复，避免污染同进程其他调用方）。
     */
    private String runWithBudget(Agent agent, String prompt) {
        String iterationsKey = "paicli.react.hard.max.iterations";
        String tokenKey = "paicli.react.token.budget";
        String oldIterations = System.getProperty(iterationsKey);
        String oldToken = System.getProperty(tokenKey);
        System.setProperty(iterationsKey, Integer.toString(options.hardMaxIterations()));
        System.setProperty(tokenKey, Integer.toString(options.tokenBudget()));
        try {
            return agent.run(prompt);
        } finally {
            restoreProperty(iterationsKey, oldIterations);
            restoreProperty(tokenKey, oldToken);
        }
    }

    private VerifierOutcome runVerifier(SuiteDefinition suite,
                                        CaseDefinition caseDefinition,
                                        Path workspace,
                                        Path rawTrace,
                                        int llmCalls) {
        VerifierOutcome primary;
        if (caseDefinition.verifierType() == CaseDefinition.VerifierType.NONE) {
            primary = new VerifierOutcome(true, null, "no verifier configured; auto-pass");
        } else if (caseDefinition.verifierType() == CaseDefinition.VerifierType.TRAJECTORY) {
            primary = runTrajectoryVerifier(suite, caseDefinition, rawTrace, llmCalls);
        } else {
            primary = runCommandVerifier(suite, caseDefinition, workspace);
        }
        // 叠加层：hidden/{caseId}/trajectory.json 存在时，在主判定之外追加轨迹校验
        if (caseDefinition.verifierType() != CaseDefinition.VerifierType.TRAJECTORY) {
            Path additiveRules = suite.sourceDirectory()
                    .resolve("hidden")
                    .resolve(caseDefinition.id())
                    .resolve("trajectory.json");
            if (Files.exists(additiveRules)) {
                VerifierOutcome trajectory = runTrajectoryVerifier(suite, caseDefinition, rawTrace, llmCalls);
                if (!trajectory.passed()) {
                    return new VerifierOutcome(
                            false,
                            primary.exitCode() == null ? 1 : primary.exitCode(),
                            primary.output() + System.lineSeparator()
                                    + "[trajectory] " + trajectory.output());
                }
                return new VerifierOutcome(true, primary.exitCode(),
                        primary.output() + System.lineSeparator()
                                + "[trajectory] " + trajectory.output());
            }
        }
        return primary;
    }

    private VerifierOutcome runCommandVerifier(SuiteDefinition suite,
                                               CaseDefinition caseDefinition,
                                               Path workspace) {
        try {
            CaseDefinition.VerifierInvocation invocation =
                    suite.resolveVerifier(caseDefinition).forWorkspace(workspace);
            ProcessBuilder builder = new ProcessBuilder(invocation.arguments());
            builder.directory(invocation.workingDirectory().toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = readBounded(process.getInputStream());

            boolean finished = process.waitFor(
                    options.verifierTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new VerifierOutcome(false, null,
                        "verifier 超时（>" + options.verifierTimeoutSeconds() + "s）\n" + output);
            }
            int exitCode = process.exitValue();
            return new VerifierOutcome(exitCode == 0, exitCode, output);
        } catch (IOException e) {
            return new VerifierOutcome(false, null, "verifier 启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VerifierOutcome(false, null, "verifier 等待被中断");
        }
    }

    /**
     * 轨迹判定：规则文件按约定放在 suite 目录 hidden/{caseId}/trajectory.json（不进工作区）。
     * 规则缺失属于 suite 编写错误，按 INFRA 处理（抛出由外层统一捕获）。
     */
    private VerifierOutcome runTrajectoryVerifier(SuiteDefinition suite,
                                                  CaseDefinition caseDefinition,
                                                  Path rawTrace,
                                                  int llmCalls) {
        try {
            Path rulesFile = suite.sourceDirectory()
                    .resolve("hidden")
                    .resolve(caseDefinition.id())
                    .resolve("trajectory.json");
            TrajectoryVerifier.Rules rules = TrajectoryVerifier.load(rulesFile);
            TrajectoryVerifier.TrajectoryResult result =
                    TrajectoryVerifier.verify(rules, rawTrace, llmCalls);
            String output = result.passed()
                    ? "trajectory PASS"
                    : "trajectory FAIL:" + System.lineSeparator()
                            + String.join(System.lineSeparator(), result.violations());
            return new VerifierOutcome(result.passed(), result.passed() ? 0 : 1, output);
        } catch (IOException e) {
            return new VerifierOutcome(false, null, "trajectory rules 加载失败: " + e.getMessage());
        }
    }

    private record VerifierOutcome(boolean passed, Integer exitCode, String output) {
    }

    private void writeArtifacts(BenchmarkArtifactStore.EpisodeArtifacts episode,
                                CaseDefinition caseDefinition,
                                Path workspace,
                                EpisodeResult result) throws IOException {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("caseId", caseDefinition.id());
        run.put("repeat", result.repeat());
        run.put("passed", result.passed());
        run.put("timedOut", result.timedOut());
        run.put("durationMs", result.durationMs());
        run.put("metrics", metricsMap(result.metrics()));
        episode.writeRun(run);

        Map<String, Object> verifier = new LinkedHashMap<>();
        verifier.put("exitCode", result.verifierExitCode());
        verifier.put("output", result.verifierOutput());
        episode.writeVerifier(verifier);

        episode.writeAnswer(result.agentResponse() == null ? "" : result.agentResponse());
    }

    private Map<String, Object> metricsMap(TracingLlmClient.Metrics metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (metrics != null) {
            map.put("llmCalls", metrics.calls());
            map.put("inputTokens", metrics.inputTokens());
            map.put("outputTokens", metrics.outputTokens());
            map.put("cachedInputTokens", metrics.cachedInputTokens());
            map.put("toolCalls", metrics.toolCalls());
            map.put("elapsedMillis", metrics.elapsedMillis());
        }
        return map;
    }

    private static String readBounded(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0 && builder.length() <= MAX_VERIFIER_OUTPUT_CHARS) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        input.close();
        if (builder.length() > MAX_VERIFIER_OUTPUT_CHARS) {
            builder.setLength(MAX_VERIFIER_OUTPUT_CHARS);
            builder.append("\n...[输出超长，已截断]");
        }
        return builder.toString();
    }

    private String safeModelId() {
        String raw = baseClient.getModelName();
        String sanitized = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "-");
        if (sanitized.isEmpty() || sanitized.length() > 127) {
            return "model";
        }
        return sanitized;
    }

    private static ScheduledExecutorService newScheduledWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "paicli-bench-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void restoreProperty(String key, String oldValue) {
        if (oldValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, oldValue);
        }
    }
}
