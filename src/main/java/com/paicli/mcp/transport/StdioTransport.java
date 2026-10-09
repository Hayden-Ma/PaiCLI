package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StdioTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STDERR_LINES = 200;
    private static final int MAX_IGNORED_LINE_CHARS = 500;

    private final Process process;
    private final BufferedWriter stdin;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> stderrRing = new ArrayDeque<>();
    private final Object stderrLock = new Object();
    private volatile boolean closed;

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            commandLine.addAll(args);
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        // 不继承 PaiCLI 的全部环境变量，避免把用户的各种 API Key 交给 MCP 服务
        Map<String, String> childEnv = builder.environment();
        Map<String, String> allowed = McpChildEnvironment.build(System.getenv(), env);
        childEnv.clear();
        childEnv.putAll(allowed);
        this.process = builder.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startStdoutReader();
        startStderrReader();
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("MCP stdio transport already closed");
        }
        stdin.write(MAPPER.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public List<String> stderrLines() {
        synchronized (stderrLock) {
            return List.copyOf(stderrRing);
        }
    }

    @Override
    public Long processId() {
        return process.pid();
    }

    @Override
    public String transportName() {
        return "stdio";
    }

    @Override
    public void close() {
        closed = true;
        // 关 stdin 让子进程读到 EOF，给一次优雅退出窗口（1 秒）。
        // shutdown 通知由 McpClient.close 在调本方法之前发出，子进程拿到 EOF 后通常会立即退出。
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return;
        }
        // 优雅窗口超时，发 SIGTERM
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void startStdoutReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        handleStdoutLine(line);
                    }
                }
            } catch (Exception e) {
                appendStderr("[paicli] stdout reader stopped: " + e.getMessage());
            }
        }, "paicli-mcp-stdio-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 每行单独处理：server 往 stdout 打了一行日志（不是 JSON）时只记进 stderr 缓冲并跳过，
     * 读线程继续工作；否则后面所有请求都读不到回复，只能等超时。
     */
    private void handleStdoutLine(String line) {
        JsonNode message;
        try {
            message = MAPPER.readTree(line);
        } catch (Exception e) {
            appendStderr("[paicli] stdout 非 JSON 行已忽略: " + abbreviate(line));
            return;
        }
        if (message != null && message.isArray()) {
            message.forEach(this::dispatch);
        } else {
            dispatch(message);
        }
    }

    private void dispatch(JsonNode message) {
        if (message == null || !message.isObject()) {
            appendStderr("[paicli] stdout 非 JSON-RPC 消息已忽略: " + abbreviate(String.valueOf(message)));
            return;
        }
        for (Consumer<JsonNode> listener : listeners) {
            try {
                listener.accept(message);
            } catch (RuntimeException e) {
                appendStderr("[paicli] 处理 MCP 消息失败: " + e.getMessage());
            }
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= MAX_IGNORED_LINE_CHARS ? line : line.substring(0, MAX_IGNORED_LINE_CHARS) + "...";
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException e) {
                appendStderr("[paicli] stderr reader stopped: " + e.getMessage());
            }
        }, "paicli-mcp-stdio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            while (stderrRing.size() >= MAX_STDERR_LINES) {
                stderrRing.removeFirst();
            }
            stderrRing.addLast(line);
        }
    }
}
