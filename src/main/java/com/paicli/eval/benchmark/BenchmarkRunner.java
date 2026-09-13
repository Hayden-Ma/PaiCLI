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
 * 汇总 pass@1 / pass@k，并把加权分数交给 ScoreAggregator 聚合，
 * 全部产物（manifest / 每次采样 run.json / verifier.json / answer.md /
 * raw.jsonl / 汇总 summary.json / 人读 report.md）落盘到 run 目录。
 */
public final class BenchmarkRunner {

    /** 单 case 汇总（跨 k 次采样）。 */
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
                             long totalDurationMs) {
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
        for (CaseRunner.EpisodeResult episode : episodes) {
            if (episode.isInfraError()) {
                infra++;
                detail.append("infra: ").append(episode.infraError()).append("; ");
            } else if (episode.timedOut()) {
                anyTimedOut = true;
            } else if (episode.passed()) {
                passed++;
            }
        }
        if (infra == episodes.size()) {
            return new CaseReport(definition.id(), definition.title(), definition.category(),
                    definition.level(), definition.weight(),
                    episodes.size(), 0, infra, false, false, false,
                    null, ScoreAggregator.ResultStatus.INFRA_ERROR,
                    detail.toString(), 0, 0, totalDuration(episodes));
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
        double scored = episodes.size() - infra;
        return new CaseReport(definition.id(), definition.title(), definition.category(),
                definition.level(), definition.weight(),
                episodes.size(), passed, infra,
                passAt1, passAtK, passed == episodes.size(),
                score, ScoreAggregator.ResultStatus.SCORED, notes,
                scored == 0 ? 0 : episodes.stream()
                        .filter(e -> !e.isInfraError()).mapToLong(e -> e.metrics() == null ? 0 : e.metrics().calls())
                        .average().orElse(0),
                scored == 0 ? 0 : episodes.stream()
                        .filter(e -> !e.isInfraError()).mapToLong(e -> e.metrics() == null ? 0 : e.metrics().toolCalls())
                        .average().orElse(0),
                totalDuration(episodes));
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
        markdown.append("| case | category | level | passed | pass@1 | pass@k | score | avgCalls | avgTools | time(s) |\n");
        markdown.append("|------|----------|-------|--------|--------|--------|-------|----------|----------|---------|\n");
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
                    .append(" |\n");
        }
        Path target = store.runDirectory().resolve("report.md");
        Files.writeString(target, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static void logProgress(String format, Object... args) {
        System.out.println("[bench] " + String.format(java.util.Locale.ROOT, format, args));
    }
}
