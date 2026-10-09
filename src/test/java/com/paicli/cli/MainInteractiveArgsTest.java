package com.paicli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainInteractiveArgsTest {

    @Test
    void defaultsToNeither() {
        Main.InteractiveCliArgs args = Main.parseInteractiveArgs(new String[]{});

        assertFalse(args.continueLatest());
        assertNull(args.sessionId());
    }

    @Test
    void parsesContinueFlags() {
        assertTrue(Main.parseInteractiveArgs(new String[]{"--continue"}).continueLatest());
        assertTrue(Main.parseInteractiveArgs(new String[]{"-c"}).continueLatest());
    }

    @Test
    void parsesSessionId() {
        Main.InteractiveCliArgs args = Main.parseInteractiveArgs(new String[]{"--session", "session-123"});

        assertEquals("session-123", args.sessionId());
        assertFalse(args.continueLatest());
        assertEquals("session-456",
                Main.parseInteractiveArgs(new String[]{"--session=session-456"}).sessionId());
    }

    @Test
    void toleratesTrailingSessionWithoutValue() {
        Main.InteractiveCliArgs args = Main.parseInteractiveArgs(new String[]{"--session"});

        assertNull(args.sessionId());
        assertFalse(args.continueLatest());
    }

    @Test
    void ignoresUnknownFlags() {
        Main.InteractiveCliArgs args = Main.parseInteractiveArgs(new String[]{"--verbose", "--continue"});

        assertTrue(args.continueLatest());
        assertNull(args.sessionId());
    }
}
