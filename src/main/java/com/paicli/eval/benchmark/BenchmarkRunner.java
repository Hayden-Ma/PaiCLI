package com.paicli.eval.benchmark;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测编排器：对 suite 内每个 active case 采样 k 次，
 * 汇总 pass@1 / pass@k / pass^k 与稳定性方差（成功率的样本标准差
 * 及效率指标标准差），并把加权分数交给 ScoreAggregator 聚合，
 * 全部产物（manifest / 每次采样 run.json / verifier.json / answer.md /
 * raw.jsonl / 汇总 summary.json / 人读 report.md）落盘到 run 目录。
 */
public final class BenchmarkRunner {

    /** 单 case 汇总（跨 k 次采样）。末尾四个 StdDev 为各采样间的样本标准差（n-1 分母，k<2 时为 0）。 */
    public record CaseReport(String caseId,
                             String title,
                             String category,
                             CaseDefinition.Level level,
                             int weight,
                             int runs,
                             int passedRuns,
                             int infraErrors,
                             boolean passAt1,
                             boolean passAtK,
                             boolean strictSuccess,
                             Double score,
                             ScoreAggregator.ResultStatus status,
                             String detail,
                             double avgLlmCalls,
                             double avgToolCalls,
                             long totalDurationMs,
                             double passRateStdDev,
                             double llmCallsStdDev,
                             double toolCallsStdDev,
                             double durationMsStdDev) {
    }

    public record SuiteReport(String runId,
                              String suiteId,
                              String suiteName,
                              String model,
                              String provider,
                              int runsPerCase,
                              Instant startedAt,
                              Instant finishedAt,
                              Double weightedScore,
                              double passAt1Percent,
                              double passAtKPercent,
                              double passHatKPercent,
                              boolean allPassedAtLeastOnce,
                              List<CaseReport> cases) {
    }

    private final LlmClient baseClient;
    private final CaseRunner.Options options;

    public BenchmarkRunner(LlmClient baseClient, CaseRunner.Options options) {
        this.baseClient = baseClient;
        this.options = options == null ? CaseRunner.Options.defaults() : options;
    }

    public SuiteReport run(SuiteDefinition suite, int runsPerCase, Path outputRoot) throws IOException {
        if (runsPerCase <= 0) {
            throw new IllegalArgumentException("runsPerCase must be positive");
        }
        String runId = "bench-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
        Instant startedAt = Instant.now();
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(outputRoot, runId);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("suite", suite.name());
        manifest.put("suiteVersion", suite.version());
        manifest.put("model", baseClient.getModelName());
        manifest.put("provider", baseClient.getProviderName());
        manifest.put("runsPerCase", runsPerCase);
        manifest.put("options", Map.of(
                "caseTimeoutSeconds", options.caseTimeoutSeconds(),
                "verifierTimeoutSeconds", options.verifierTimeoutSeconds(),
                "hardMaxIterations", options.hardMaxIterations(),
                "tokenBudget", options.tokenBudget()));
        manifest.put("startedAt", Instant.now().toString());
        store.writeManifest(manifest);

        CaseRunner caseRunner = new CaseRunner(baseClient, options);
        List<CaseReport> reports = new ArrayList<>();
        List<ScoreAggregator.CaseScore> scores = new ArrayList<>();

        for (CaseDefinition definition : suite.activeCases()) {
            List<CaseRunner.EpisodeResult> episodes = new ArrayList<>();
            for (int repeat = 1; repeat <= runsPerCase; repeat++) {
                logProgress("run case=%s repeat=%d/%d", definition.id(), repeat, runsPerCase);
                episodes.add(caseRunner.run(suite, definition, repeat, store));
            }
            CaseReport report = summarize(definition, episodes);
            reports.add(report);
            scores.add(toCaseScore(report));
        }

        ScoreAggregator.AggregateResult aggregate =
                ScoreAggregator.aggregate(suite, scores);
        SuiteReport suiteReport = new SuiteReport(
                runId,
                suite.name(),
                suite.name(),
                baseClient.getModelName(),
                baseClient.getProviderName(),
                runsPerCase,
                startedAt,
                Instant.now(),
                aggregate.weightedScore(),
                percent(reports, r -> r.passAt1()),
                percent(reports, r -> r.passAtK()),
                percent(reports, CaseReport::strictSuccess),
                reports.stream().allMatch(CaseReport::passAtK),
                List.copyOf(reports));

        writeSummary(store, suiteReport, aggregate);
        writeReportMarkdown(store, suiteReport);
        return suiteReport;
    }

    private CaseReport summarize(CaseDefinition definition, List<CaseRunner.EpisodeResult> episodes) {
        int passed = 0;
        int infra = 0;
        boolean anyTimedOut = false;
        StringBuilder detail = new StringBuilder();
        List<CaseRunner.EpisodeResult> scoredEpisodes = new ArrayList<>();
        for (CaseRunner.EpisodeResult episode : episodes) {
            if (episode.isInfraError()) {
                infra++;
                detail.append("infra: ").append(episode.infraError()).append("; ");
            } else {
                scoredEpisodes.add(episode);
                if (episode.timedOut()) {
                    anyTimedOut = true;
                } else if (episode.passed()) {
                    passed++;
                }
            }
        }
        if (infra == episodes.size()) {
            return new CaseReport(definition.id(), definition.title(), definition.category(),
                    definition.level(), definition.weight(),
                    episodes.size(), 0, infra, false, false, false,
                    null, ScoreAggregator.ResultStatus.INFRA_ERROR,
                    detail.toString(), 0, 0, totalDuration(episodes),
                    0, 0, 0, 0);
        }
        boolean passAt1 = !episodes.isEmpty()
                && !episodes.get(0).isInfraError()
                && episodes.get(0).passed();
        boolean passAtK = passed > 0;
        double score = 100.0 * passed / episodes.size();
        String notes = detail.toString();
        if (anyTimedOut) {
            notes = "存在超时采样; " + notes;
        }
        return new CaseReport(definition.id(), definition.title(), definition.category(),
                definition.level(), definition.weight(),
                episodes.size(), passed, infra,
                passAt1, passAtK, passed == episodes.size(),
                score, ScoreAggregator.ResultStatus.SCORED, notes,
                scoredEpisodes.isEmpty() ? 0 : scoredEpisodes.stream()
                        .mapToDouble(BenchmarkRunner::calls).average().orElse(0),
                scoredEpisodes.isEmpty() ? 0 : scoredEpisodes.stream()
                        .mapToDouble(BenchmarkRunner::tools).average().orElse(0),
                totalDuration(episodes),
                sampleStdDev(scoredEpisodes.stream().mapToDouble(e -> e.passed() ? 100.0 : 0.0).toArray()),
                sampleStdDev(scoredEpisodes.stream().mapToDouble(BenchmarkRunner::calls).toArray()),
                sampleStdDev(scoredEpisodes.stream().mapToDouble(BenchmarkRunner::tools).toArray()),
                sampleStdDev(scoredEpisodes.stream().mapToDouble(CaseRunner.EpisodeResult::durationMs).toArray()));
    }

    private static long calls(CaseRunner.EpisodeResult episode) {
        return episode.metrics() == null ? 0 : episode.metrics().calls();
    }

    private static long tools(CaseRunner.EpisodeResult episode) {
        return episode.metrics() == null ? 0 : episode.metrics().toolCalls();
    }

    /** 样本标准差（n-1 分母）；样本数 < 2 时返回 0，避免单次采样被误报为"零方差"。 */
    private static double sampleStdDev(double[] values) {
        if (values.length < 2) {
            return 0.0;
        }
        double mean = 0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;
        double sumSquares = 0;
        for (double value : values) {
            double diff = value - mean;
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares / (values.length - 1));
    }

    private static long totalDuration(List<CaseRunner.EpisodeResult> episodes) {
        return episodes.stream().mapToLong(CaseRunner.EpisodeResult::durationMs).sum();
    }

    private ScoreAggregator.CaseScore toCaseScore(CaseReport report) {
        if (report.status() == ScoreAggregator.ResultStatus.INFRA_ERROR) {
            return ScoreAggregator.CaseScore.infraError(report.caseId(), report.detail());
        }
        return ScoreAggregator.CaseScore.scored(report.caseId(), report.score(), report.strictSuccess());
    }

    private static double percent(List<CaseReport> reports,
                                  java.util.function.Predicate<CaseReport> predicate) {
        if (reports.isEmpty()) {
            return 0.0;
        }
        return 100.0 * reports.stream().filter(predicate).count() / reports.size();
    }

    private void writeSummary(BenchmarkArtifactStore store,
                              SuiteReport report,
                              ScoreAggregator.AggregateResult aggregate) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", report.runId());
        summary.put("suite", report.suiteName());
        summary.put("model", report.model());
        summary.put("provider", report.provider());
        summary.put("runsPerCase", report.runsPerCase());
        summary.put("startedAt", report.startedAt().toString());
        summary.put("finishedAt", report.finishedAt().toString());
        summary.put("weightedScore", aggregate.weightedScore());
        summary.put("passAt1Percent", report.passAt1Percent());
        summary.put("passAtKPercent", report.passAtKPercent());
        summary.put("passHatKPercent", report.passHatKPercent());
        summary.put("allPassedAtLeastOnce", report.allPassedAtLeastOnce());
        summary.put("strictSuccess", aggregate.strictSuccess());
        summary.put("levels", levelMap(aggregate));
        List<Map<String, Object>> cases = new ArrayList<>();
        for (CaseReport caseReport : report.cases()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("caseId", caseReport.caseId());
            entry.put("category", caseReport.category());
            entry.put("level", caseReport.level().toString());
            entry.put("weight", caseReport.weight());
            entry.put("passedRuns", caseReport.passedRuns() + "/" + caseReport.runs());
            entry.put("passAt1", caseReport.passAt1());
            entry.put("passAtK", caseReport.passAtK());
            entry.put("strictSuccess", caseReport.strictSuccess());
            entry.put("score", caseReport.score());
            entry.put("status", caseReport.status().toString());
            entry.put("passRateStdDev", round3(caseReport.passRateStdDev()));
            entry.put("llmCallsStdDev", round3(caseReport.llmCallsStdDev()));
            entry.put("toolCallsStdDev", round3(caseReport.toolCallsStdDev()));
            entry.put("durationMsStdDev", round3(caseReport.durationMsStdDev()));
            if (!caseReport.detail().isBlank()) {
                entry.put("detail", caseReport.detail());
            }
            cases.add(entry);
        }
        summary.put("cases", cases);
        store.writeSummary(summary);
    }

    private static Map<String, Object> levelMap(ScoreAggregator.AggregateResult aggregate) {
        Map<String, Object> levels = new LinkedHashMap<>();
        aggregate.levels().forEach((level, summary) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("weightedScore", summary.weightedScore());
            entry.put("scoredCases", summary.scoredCases() + "/" + summary.totalCases());
            entry.put("strictSuccessCases", summary.strictSuccessCases());
            levels.put(level.toString(), entry);
        });
        return levels;
    }

    private void writeReportMarkdown(BenchmarkArtifactStore store, SuiteReport report) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Benchmark Report\n\n");
        markdown.append("- suite: ").append(report.suiteName()).append('\n');
        markdown.append("- model: ").append(report.provider()).append("/").append(report.model()).append('\n');
        markdown.append("- runs per case: ").append(report.runsPerCase()).append('\n');
        markdown.append("- weighted score: ").append(report.weightedScore()).append('\n');
        markdown.append("- pass@1: ").append(String.format(java.util.Locale.ROOT, "%.1f%%", report.passAt1Percent())).append('\n');
        markdown.append("- pass@k: ").append(String.format(java.util.Locale.ROOT, "%.1f%%", report.passAtKPercent())).append('\n');
        markdown.append("- pass^k: ").append(String.format(java.util.Locale.ROOT, "%.1f%%", report.passHatKPercent())).append('\n');
        markdown.append('\n');
        markdown.append("| case | category | level | passed | pass@1 | pass@k | score | avgCalls | avgTools | time(s) | passσ | callsσ | toolsσ | timeσ |\n");
        markdown.append("|------|----------|-------|--------|--------|--------|-------|----------|----------|---------|-------|--------|--------|-------|\n");
        for (CaseReport caseReport : report.cases()) {
            markdown.append("| ").append(caseReport.caseId())
                    .append(" | ").append(caseReport.category())
                    .append(" | ").append(caseReport.level())
                    .append(" | ").append(caseReport.passedRuns()).append("/").append(caseReport.runs())
                    .append(" | ").append(caseReport.passAt1())
                    .append(" | ").append(caseReport.passAtK())
                    .append(" | ").append(caseReport.score() == null ? "INFRA" : caseReport.score())
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", caseReport.avgLlmCalls()))
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", caseReport.avgToolCalls()))
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", caseReport.totalDurationMs() / 1000.0))
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", caseReport.passRateStdDev()))
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", caseReport.llmCallsStdDev()))
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", caseReport.toolCallsStdDev()))
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", caseReport.durationMsStdDev() / 1000.0))
                    .append(" |\n");
        }
        Path target = store.runDirectory().resolve("report.md");
        Files.writeString(target, markdown.toString(), StandardCharsets.UTF_8);
    }

    /** 保留 3 位小数，避免 summary.json 里出现浮点长尾。 */
    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static void logProgress(String format, Object... args) {
        System.out.println("[bench] " + String.format(java.util.Locale.ROOT, format, args));
    }
}
