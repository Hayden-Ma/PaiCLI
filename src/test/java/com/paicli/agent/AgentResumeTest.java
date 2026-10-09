package com.paicli.agent;

import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResumeTest {

    @Test
    void restoresReactMessagesAfterFreshSystemPrompt(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-a");
        ledger.appendMessage("react", "agent", "session_attach", LlmClient.Message.system("OLD SYSTEM"));
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("第一轮问题"));
        ledger.appendMessage("react", "agent", "llm_response", LlmClient.Message.assistant("第一轮回答"));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(2, agent.restoreFromLedger());

        List<LlmClient.Message> history = agent.getConversationHistory();
        assertEquals(3, history.size());
        assertEquals("system", history.get(0).role());
        assertNotEquals("OLD SYSTEM", history.get(0).content());
        assertEquals("第一轮问题", history.get(1).content());
        assertEquals("第一轮回答", history.get(2).content());
        assertEquals(1, history.stream().filter(m -> "system".equals(m.role())).count());
    }

    @Test
    void replaysOnlyReactModeEntries(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-b");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("react 消息"));
        ledger.appendMessage("plan", "planner", "planning_request", LlmClient.Message.user("plan 消息"));
        ledger.appendMessage("team", "worker-1", "llm_response", LlmClient.Message.assistant("team 消息"));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(1, agent.restoreFromLedger());

        List<String> contents = contents(agent);
        assertTrue(contents.contains("react 消息"));
        assertFalse(contents.contains("plan 消息"));
        assertFalse(contents.contains("team 消息"));
    }

    @Test
    void keepsToolCallToolResultPairing(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-c");
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call-1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}"));
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("读文件"));
        ledger.appendMessage("react", "agent", "llm_response",
                LlmClient.Message.assistant("", List.of(call)));
        ledger.appendMessage("react", "agent", "tool_execution",
                LlmClient.Message.tool("call-1", "文件内容"));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(3, agent.restoreFromLedger());

        List<LlmClient.Message> history = agent.getConversationHistory();
        assertEquals("call-1", history.get(2).toolCalls().get(0).id());
        assertEquals("call-1", history.get(3).toolCallId());
        assertEquals("文件内容", history.get(3).content());
    }

    @Test
    void replaysOnlyAfterLastHistoryClear(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-d");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("清空前"));
        ledger.appendEvent("history_clear", "react", "agent", "slash_clear",
                Map.of("discardedViewMessages", 1));
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("清空后"));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(1, agent.restoreFromLedger());

        List<String> contents = contents(agent);
        assertFalse(contents.contains("清空前"));
        assertTrue(contents.contains("清空后"));
    }

    @Test
    void prunesRestoredImages(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-e");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("看图"),
                LlmClient.ContentPart.imageBase64("AAAA", "image/png"))));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(1, agent.restoreFromLedger());

        assertEquals(0, agent.getConversationHistory().get(1).imagePartCount());
    }

    @Test
    void marksSessionAsExternalContextOnResume(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-f");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("你好"));

        Agent agent = agentWith(tempDir, ledger);
        assertFalse(agent.getMemoryManager().getExternalContextTracker().blocksAutomaticMemory());

        agent.restoreFromLedger();

        assertTrue(agent.getMemoryManager().getExternalContextTracker().blocksAutomaticMemory());
    }

    @Test
    void returnsZeroForEmptyLedger(@TempDir Path tempDir) throws Exception {
        Agent agent = agentWith(tempDir, ConversationLedger.disabled());

        assertEquals(0, agent.restoreFromLedger());
        assertEquals(1, agent.getConversationHistory().size());
    }

    @Test
    void replaysCompactionBoundarySummary(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-g");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("旧问题"));
        ledger.appendMessage("react", "agent", "llm_response", LlmClient.Message.assistant("旧回答"));
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("保留问题"));
        ledger.appendMessage("react", "agent", "llm_response", LlmClient.Message.assistant("保留回答"));
        String summary = ConversationHistoryCompactor.SUMMARY_PREFIX + "折叠摘要正文";
        ledger.appendEvent("compaction", "react", "agent", "automatic:full_summary",
                Map.of("summary", summary, "retainedMessages", 2,
                        "beforeMessages", 4, "afterMessages", 4));
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("新问题"));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(5, agent.restoreFromLedger());

        List<LlmClient.Message> history = agent.getConversationHistory();
        assertEquals(6, history.size());
        assertEquals("user", history.get(1).role());
        assertEquals(summary, history.get(1).content());
        assertEquals(ConversationHistoryCompactor.SUMMARY_ACK, history.get(2).content());
        assertEquals("保留问题", history.get(3).content());
        assertEquals("保留回答", history.get(4).content());
        assertEquals("新问题", history.get(5).content());
    }

    @Test
    void ignoresCompactionEventWithoutSummary(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-h");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("问题"));
        ledger.appendEvent("compaction", "react", "agent", "automatic:tool_result_clearing",
                Map.of("beforeMessages", 1, "afterMessages", 1, "retainedMessages", 0));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(1, agent.restoreFromLedger());
        assertEquals(2, agent.getConversationHistory().size());
        assertEquals("问题", agent.getConversationHistory().get(1).content());
    }

    @Test
    void ignoresCompactionBoundaryFromOtherModes(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ledger(tempDir, "session-i");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("react 问题"));
        ledger.appendEvent("compaction", "plan", "planner", "automatic",
                Map.of("summary", ConversationHistoryCompactor.SUMMARY_PREFIX + "plan 摘要",
                        "retainedMessages", 1));

        Agent agent = agentWith(tempDir, ledger);

        assertEquals(1, agent.restoreFromLedger());
        assertEquals(2, agent.getConversationHistory().size());
        assertEquals("react 问题", agent.getConversationHistory().get(1).content());
    }

    private static ConversationLedger ledger(Path tempDir, String sessionId) throws IOException {
        return ConversationLedger.open(tempDir.resolve("history"), sessionId);
    }

    private static Agent agentWith(Path tempDir, ConversationLedger ledger) {
        LlmClient client = new NoopClient();
        MemoryManager memoryManager = new MemoryManager(client, 4096, 128_000,
                new LongTermMemory(tempDir.resolve("memory").toFile()));
        Agent agent = new Agent(client, new ToolRegistry(), memoryManager);
        agent.setConversationLedger(ledger);
        return agent;
    }

    private static List<String> contents(Agent agent) {
        return agent.getConversationHistory().stream().map(LlmClient.Message::content).toList();
    }

    private static final class NoopClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            throw new IOException("restore 不应触发模型调用");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            throw new IOException("restore 不应触发模型调用");
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test-provider";
        }
    }
}
