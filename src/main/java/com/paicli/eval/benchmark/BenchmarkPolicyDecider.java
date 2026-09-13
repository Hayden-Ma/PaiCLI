package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 评测 worker 的工具准入决策器（非交互、默认拒绝）。
 *
 * 与微信通道策略同构，但面向评测场景做了两点收紧：
 * 1. 联网工具（web_search / web_fetch）默认拒绝，保证评测可离线、可复现；
 * 2. execute_command 只允许精确命中 suite 级命令白名单。
 * 文件写入工具（write_file / create_project）放行，由 ToolRegistry 的 PathGuard
 * 兜底圈定在临时工作区内。
 */
public class BenchmarkPolicyDecider {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    private final BenchmarkPolicyConfig config;

    public BenchmarkPolicyDecider(BenchmarkPolicyConfig config) {
        this.config = config == null ? BenchmarkPolicyConfig.denyAllCommands() : config;
    }

    public Decision decide(String toolName, String argumentsJson) {
        String name = toolName == null ? "" : toolName.trim();
        if (name.isBlank()) {
            return Decision.deny("工具名为空");
        }
        if (isReadOnlyBuiltin(name)) {
            return Decision.allow();
        }
        if ("execute_command".equals(name)) {
            return commandAllowed(argumentsJson);
        }
        if ("write_file".equals(name) || "create_project".equals(name)) {
            return Decision.allow();
        }
        if ("revert_turn".equals(name)) {
            return Decision.deny("评测不允许回滚快照");
        }
        if (name.startsWith("browser_")) {
            return Decision.deny("评测不允许使用浏览器工具");
        }
        if (name.startsWith("mcp__")) {
            return Decision.deny("评测不允许使用 MCP 工具");
        }
        if ("web_search".equals(name) || "web_fetch".equals(name)) {
            return Decision.deny("评测默认拒绝联网工具以保证可复现");
        }
        return Decision.deny("评测策略未将该工具列入允许清单: " + name);
    }

    private Decision commandAllowed(String argumentsJson) {
        String command = extract(argumentsJson, "command");
        if (command.isBlank()) {
            return Decision.deny("评测拒绝空命令");
        }
        for (String allowed : config.commandAllowlist()) {
            String normalized = allowed == null ? "" : allowed.trim();
            if (!normalized.isBlank() && command.trim().equals(normalized)) {
                return Decision.allow();
            }
        }
        return Decision.deny("评测默认拒绝 execute_command；仅允许精确命中 suite 命令白名单");
    }

    private static boolean isReadOnlyBuiltin(String name) {
        return switch (name) {
            case "read_file", "list_dir", "glob_files", "grep_code", "search_code",
                    "browser_status" -> true;
            default -> false;
        };
    }

    private static String extract(String json, String field) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
