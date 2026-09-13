package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkPolicyDeciderTest {
    private final BenchmarkPolicyDecider decider = new BenchmarkPolicyDecider(
            new BenchmarkPolicyConfig(java.util.List.of("mvn test")));

    @Test
    void shouldAllowReadOnlyBuiltins() {
        assertTrue(decider.decide("read_file", "{}").allowed());
        assertTrue(decider.decide("grep_code", "{}").allowed());
        assertTrue(decider.decide("glob_files", "{}").allowed());
        assertTrue(decider.decide("list_dir", "{}").allowed());
        assertTrue(decider.decide("search_code", "{}").allowed());
    }

    @Test
    void shouldAllowFileWritesButConfineViaPathGuard() {
        assertTrue(decider.decide("write_file", "{\"path\":\"a.txt\"}").allowed());
        assertTrue(decider.decide("create_project", "{}").allowed());
    }

    @Test
    void shouldAllowOnlyWhitelistedCommands() {
        assertTrue(decider.decide("execute_command", "{\"command\":\"mvn test\"}").allowed());
        assertFalse(decider.decide("execute_command", "{\"command\":\"mvn test -Pquick\"}").allowed());
        assertFalse(decider.decide("execute_command", "{\"command\":\"rm -rf /\"}").allowed());
        assertFalse(decider.decide("execute_command", "{}").allowed());
    }

    @Test
    void shouldDenyNetworkBrowserMcpAndRollback() {
        assertFalse(decider.decide("web_search", "{}").allowed());
        assertFalse(decider.decide("web_fetch", "{}").allowed());
        assertFalse(decider.decide("browser_connect", "{}").allowed());
        assertFalse(decider.decide("revert_turn", "{}").allowed());
        assertFalse(decider.decide("mcp__server__tool", "{}").allowed());
    }

    @Test
    void shouldDenyUnknownAndBlankTools() {
        assertFalse(decider.decide("", "{}").allowed());
        assertFalse(decider.decide(null, "{}").allowed());
        assertFalse(decider.decide("some_unknown_tool", "{}").allowed());
    }
}
