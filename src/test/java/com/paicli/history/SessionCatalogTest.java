package com.paicli.history;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCatalogTest {

    @Test
    void discoverReturnsSessionsSortedByLastActive(@TempDir Path tempDir) throws Exception {
        Path history = tempDir.resolve("history");
        ConversationLedger older = session(history, "session-older", "/tmp/proj-a", "旧");
        ConversationLedger newer = session(history, "session-newer", "/tmp/proj-a", "新");
        Files.setLastModifiedTime(older.file(), FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(newer.file(), FileTime.fromMillis(2_000L));

        List<SessionCatalog.SessionSummary> sessions = SessionCatalog.discover(history);

        assertEquals(2, sessions.size());
        assertEquals("session-newer", sessions.get(0).sessionId());
        assertEquals("session-older", sessions.get(1).sessionId());
    }

    @Test
    void latestForCwdMatchesNormalizedCwd(@TempDir Path tempDir) throws Exception {
        Path history = tempDir.resolve("history");
        session(history, "session-a", "/tmp/proj-a", "a");
        session(history, "session-b", "/tmp/proj-b", "b");

        Optional<SessionCatalog.SessionSummary> latest =
                SessionCatalog.latestForCwd(history, "/tmp/proj-b");

        assertTrue(latest.isPresent());
        assertEquals("session-b", latest.get().sessionId());
        assertEquals(1, latest.get().messageCount());
        assertEquals(42L, latest.get().createdAt());
        assertTrue(SessionCatalog.latestForCwd(history, "/tmp/proj-zzz").isEmpty());
    }

    @Test
    void latestForCwdPicksMostRecentWhenSeveralMatch(@TempDir Path tempDir) throws Exception {
        Path history = tempDir.resolve("history");
        ConversationLedger first = session(history, "session-1", "/tmp/proj", "一");
        ConversationLedger second = session(history, "session-2", "/tmp/proj", "二");
        Files.setLastModifiedTime(first.file(), FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(second.file(), FileTime.fromMillis(2_000L));

        assertEquals("session-2",
                SessionCatalog.latestForCwd(history, "/tmp/proj").orElseThrow().sessionId());
    }

    @Test
    void skipsLegacySessionsWithoutSessionStart(@TempDir Path tempDir) throws Exception {
        Path history = tempDir.resolve("history");
        ConversationLedger legacy = ConversationLedger.open(history, "session-legacy");
        legacy.appendMessage("react", "agent", "user_input", LlmClient.Message.user("老账本"));

        List<SessionCatalog.SessionSummary> sessions = SessionCatalog.discover(history);

        assertEquals(1, sessions.size());
        assertNull(sessions.get(0).cwd());
        assertTrue(SessionCatalog.latestForCwd(history, "/tmp/anything").isEmpty());
    }

    @Test
    void ignoresSessionsWithoutMessages(@TempDir Path tempDir) throws Exception {
        Path history = tempDir.resolve("history");
        ConversationLedger empty = ConversationLedger.open(history, "session-empty");
        empty.appendEvent(SessionCatalog.START_EVENT, "react", "cli", "startup",
                Map.of("cwd", "/tmp/proj", "createdAt", 5L));

        assertTrue(SessionCatalog.discover(history).isEmpty());
    }

    @Test
    void discoverReturnsEmptyWhenDirectoryMissing(@TempDir Path tempDir) throws Exception {
        assertTrue(SessionCatalog.discover(tempDir.resolve("nope")).isEmpty());
    }

    private static ConversationLedger session(Path history, String sessionId, String cwd, String text)
            throws Exception {
        ConversationLedger ledger = ConversationLedger.open(history, sessionId);
        ledger.appendEvent(SessionCatalog.START_EVENT, "react", "cli", "startup",
                Map.of("cwd", cwd, "createdAt", 42L));
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user(text));
        return ledger;
    }
}
