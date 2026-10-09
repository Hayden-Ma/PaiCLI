package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int METHOD_NOT_FOUND = -32601;

    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paicli-mcp-jsonrpc");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        try {
            transport.send(request);
            return future.get(timeoutSeconds + 1, TimeUnit.SECONDS);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    /**
     * 先按有没有 method 区分方向：带 method 的是 server 发来的请求或通知，不带的才是我们请求的回复。
     * 不能先拿 id 去 pending 里找：server 的请求 id 也从 1 开始，会和我们自己的请求撞号。
     */
    private void handleMessage(JsonNode message) {
        if (message.hasNonNull("method")) {
            JsonNode idNode = message.get("id");
            if (idNode == null || idNode.isNull()) {
                for (Consumer<JsonNode> listener : notificationListeners) {
                    listener.accept(message);
                }
            } else {
                // 回复放到别的线程发，避免在 stdout 读线程里阻塞写 stdin
                try {
                    scheduler.execute(() -> replyToServerRequest(idNode, message.path("method").asText()));
                } catch (RejectedExecutionException ignored) {
                    // client 已关闭，不再回复
                }
            }
            return;
        }
        Long id = responseId(message.get("id"));
        if (id == null) {
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("JSON-RPC error")));
            return;
        }
        future.complete(message.get("result"));
    }

    /** 我们发出的 id 都是数字；个别 server 会把它回成数字字符串，其他形式一律不认。 */
    private static Long responseId(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (idNode.isIntegralNumber()) {
            return idNode.asLong();
        }
        if (idNode.isTextual() && idNode.asText().matches("-?\\d{1,18}")) {
            return Long.parseLong(idNode.asText());
        }
        return null;
    }

    private void replyToServerRequest(JsonNode idNode, String method) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", idNode);
        if ("ping".equals(method)) {
            response.putObject("result");
        } else {
            ObjectNode error = response.putObject("error");
            error.put("code", METHOD_NOT_FOUND);
            error.put("message", "Method not found: " + method);
        }
        try {
            transport.send(response);
        } catch (IOException ignored) {
            // transport 已关闭或断开，server 那边会自己超时
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
    }
}
