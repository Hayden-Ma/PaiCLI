package com.paicli.eval.benchmark;

import java.util.List;

/**
 * 评测 worker 的非交互策略配置。
 *
 * 命令白名单按"整条命令精确匹配"生效（与微信通道同款语义）；评测 suite 若需要
 * 让被评 agent 执行 shell 命令，必须在 suite 级别显式列出，默认全部拒绝。
 */
public record BenchmarkPolicyConfig(List<String> commandAllowlist) {
    public BenchmarkPolicyConfig {
        commandAllowlist = commandAllowlist == null ? List.of() : List.copyOf(commandAllowlist);
    }

    public static BenchmarkPolicyConfig denyAllCommands() {
        return new BenchmarkPolicyConfig(List.of());
    }
}
