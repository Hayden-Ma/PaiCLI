package com.paicli.cli;

import com.paicli.eval.benchmark.CaseRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainBenchArgsTest {

    @Test
    void shouldParseSuiteRunsAndOut() {
        Main.BenchCliArgs args = Main.parseBenchArgs(new String[]{
                "bench", "--suite", "s.json", "--runs", "3", "--out", "results"});
        assertEquals(Path.of("s.json"), args.suitePath());
        assertEquals(3, args.runs());
        assertEquals(Path.of("results"), args.outputRoot());
    }

    @Test
    void shouldApplyDefaults() {
        Main.BenchCliArgs args = Main.parseBenchArgs(new String[]{"bench", "--suite", "s.json"});
        assertEquals(Path.of("s.json"), args.suitePath());
        assertEquals(1, args.runs());
        assertEquals(Path.of("benchmarks", "results"), args.outputRoot());
        assertEquals(600, args.options().caseTimeoutSeconds());
        assertEquals(120, args.options().verifierTimeoutSeconds());
        assertEquals(40, args.options().hardMaxIterations());
        assertEquals(400_000, args.options().tokenBudget());
        assertFalse(args.options().keepWorkspaces());
    }

    @Test
    void shouldParseTuningFlags() {
        Main.BenchCliArgs args = Main.parseBenchArgs(new String[]{
                "bench", "--suite", "s.json", "--case-timeout-seconds", "30",
                "--verifier-timeout-seconds", "10", "--max-iterations", "5",
                "--token-budget", "1000", "--keep-workspaces"});
        CaseRunner.Options options = args.options();
        assertEquals(30, options.caseTimeoutSeconds());
        assertEquals(10, options.verifierTimeoutSeconds());
        assertEquals(5, options.hardMaxIterations());
        assertEquals(1000, options.tokenBudget());
        assertTrue(options.keepWorkspaces());
    }

    @Test
    void shouldFlagMissingSuite() {
        assertNull(Main.parseBenchArgs(new String[]{"bench"}).suitePath());
        assertNull(Main.parseBenchArgs(new String[]{"bench", "--runs", "2"}).suitePath());
    }

    @Test
    void shouldTolerateTrailingFlagWithoutValue() {
        assertDoesNotThrow(() -> Main.parseBenchArgs(new String[]{"bench", "--suite", "s.json", "--runs"}));
        assertEquals(1, Main.parseBenchArgs(new String[]{"bench", "--suite", "s.json", "--runs"}).runs());
    }
}
