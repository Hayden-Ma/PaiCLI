package com.paicli.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 在 {@code <historyDirectory>/raw/} 下发现可恢复的会话。
 *
 * <p>会话归属哪个项目由第一行的 {@link #START_EVENT} 事件决定——它记下写入时的工作目录
 * （{@code cwd}）和创建时间。老账本没有这个事件，无法归属到具体目录：{@link #discover}
 * 仍会列出它们（{@code cwd} 为 {@code null}），但 {@link #latestForCwd} 不会命中。</p>
 */
public final class SessionCatalog {
    /** 新会话的第一行事件，metadata 带 {@code cwd} / {@code createdAt} / {@code provider} / {@code model}。 */
    public static final String START_EVENT = "session_start";
    /** 恢复会话时写入的事件，标记这次是接着旧会话继续。 */
    public static final String RESUME_EVENT = "session_resume";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUFFIX = ".jsonl";
    /** 账本里消息条目的 {@code message} 字段非空，事件条目写成 {@code "message":null}。 */
    private static final String MESSAGE_MARKER = "\"message\":{";

    private SessionCatalog() {
    }

    /**
     * @param cwd          写入会话时的工作目录；老账本为 {@code null}
     * @param createdAt    {@code session_start} 记录的时间；老账本为 0
     * @param lastActiveAt 文件最后修改时间
     * @param messageCount 消息条目数（按 {@code message} 字段是否为空判定，不含事件）
     */
    public record SessionSummary(String sessionId, Path file, String cwd,
                                 long createdAt, long lastActiveAt, int messageCount) {
    }

    /** 列出 raw 目录下所有会话，按最近活动时间倒序。 */
    public static List<SessionSummary> discover(Path historyDirectory) throws IOException {
        Path raw = rawDirectory(historyDirectory);
        if (!Files.isDirectory(raw)) {
            return List.of();
        }
        List<SessionSummary> sessions = new ArrayList<>();
        try (Stream<Path> files = Files.list(raw)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(SUFFIX)) {
                    continue;
                }
                SessionSummary summary = read(file, name.substring(0, name.length() - SUFFIX.length()));
                if (summary != null) {
                    sessions.add(summary);
                }
            }
        }
        sessions.sort(Comparator.comparingLong(SessionSummary::lastActiveAt).reversed());
        return sessions;
    }

    /** 最近一次属于 {@code cwd}、且至少有一条消息的会话。 */
    public static Optional<SessionSummary> latestForCwd(Path historyDirectory, String cwd) throws IOException {
        String target = normalizeCwd(cwd);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        return discover(historyDirectory).stream()
                .filter(summary -> summary.messageCount() > 0)
                .filter(summary -> target.equals(normalizeCwd(summary.cwd())))
                .findFirst();
    }

    private static SessionSummary read(Path file, String sessionId) throws IOException {
        String cwd = null;
        long createdAt = 0L;
        int messageCount = 0;
        boolean firstLine = true;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (firstLine) {
                    firstLine = false;
                    JsonNode node = readNode(line);
                    if (node != null && START_EVENT.equals(node.path("event").asText())) {
                        JsonNode metadata = node.path("metadata");
                        cwd = metadata.path("cwd").asText(null);
                        createdAt = metadata.path("createdAt").asLong(0L);
                    }
                }
                if (line.contains(MESSAGE_MARKER)) {
                    messageCount++;
                }
            }
        }
        if (messageCount == 0) {
            return null;
        }
        return new SessionSummary(sessionId, file, cwd, createdAt,
                Files.getLastModifiedTime(file).toMillis(), messageCount);
    }

    private static JsonNode readNode(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path rawDirectory(Path historyDirectory) {
        return historyDirectory.toAbsolutePath().normalize().resolve("raw");
    }

    /** 归一化工作目录，Windows 下不区分大小写。 */
    private static String normalizeCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return "";
        }
        String normalized = Path.of(cwd).toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }
}
