package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轨迹判定器：检查 agent 的工具调用序列是否满足声明式规则。
 *
 * 规则来自 suite 目录下 hidden/{caseId}/trajectory.json（不进 agent 工作区），
 * 原始调用序列来自 TracingLlmClient 写出的 raw.jsonl（按 call 顺序展开 returnedTools）。
 *
 * 设计边界：只判"必须做过的关键动作 / 禁止动作 / 轮数预算"这类确定性规则，
 * 不判"是否走了最优路径"——路径优劣是主观的，业界多把轨迹用于诊断而非判分。
 */
public final class TrajectoryVerifier {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 声明式轨迹规则；全部字段可选，但至少声明一条。 */
    public record Rules(List<String> mustCallInOrder,
                        List<List<String>> mustCallAny,
                        List<String> mustNotCall,
                        Integer maxLlmCalls) {
        public Rules {
            mustCallInOrder = mustCallInOrder == null ? List.of() : List.copyOf(mustCallInOrder);
            mustCallAny = mustCallAny == null ? List.of()
                    : mustCallAny.stream().map(group -> group == null ? List.<String>of() : List.copyOf(group)).toList();
            mustNotCall = mustNotCall == null ? List.of() : List.copyOf(mustNotCall);
        }
    }

    public record TrajectoryResult(boolean passed, List<String> violations) {
    }

    public static Rules load(Path rulesFile) throws IOException {
        String json = Files.readString(rulesFile, StandardCharsets.UTF_8);
        Rules rules = MAPPER.readValue(json, Rules.class);
        if (rules.mustCallInOrder().isEmpty() && rules.mustCallAny().isEmpty()
                && rules.mustNotCall().isEmpty() && rules.maxLlmCalls() == null) {
            throw new IOException("trajectory rules 至少要声明一条规则: " + rulesFile);
        }
        return rules;
    }

    /** @param rawJsonl TracingLlmClient 的轨迹文件；@param llmCalls 本轮 LLM 调用总数 */
    public static TrajectoryResult verify(Rules rules, Path rawJsonl, int llmCalls) throws IOException {
        List<String> sequence = readToolSequence(rawJsonl);
        List<String> violations = new ArrayList<>();

        if (!rules.mustCallInOrder().isEmpty()
                && !containsInOrder(sequence, rules.mustCallInOrder())) {
            violations.add("mustCallInOrder 未按顺序出现: " + rules.mustCallInOrder()
                    + "，实际序列: " + sequence);
        }
        for (int g = 0; g < rules.mustCallAny().size(); g++) {
            List<String> group = rules.mustCallAny().get(g);
            if (group.stream().noneMatch(sequence::contains)) {
                violations.add("mustCallAny 第 " + (g + 1) + " 组一个都没调用: " + group);
            }
        }
        for (String forbidden : rules.mustNotCall()) {
            if (sequence.contains(forbidden)) {
                violations.add("调用了禁止工具: " + forbidden);
            }
        }
        if (rules.maxLlmCalls() != null && llmCalls > rules.maxLlmCalls()) {
            violations.add("LLM 调用轮数 " + llmCalls + " 超过预算 " + rules.maxLlmCalls());
        }
        return new TrajectoryResult(violations.isEmpty(), violations);
    }

    /** 按 call 顺序展开每轮 response 里的 tool call 名（只统计成功响应）。 */
    static List<String> readToolSequence(Path rawJsonl) throws IOException {
        List<String> sequence = new ArrayList<>();
        if (!Files.exists(rawJsonl)) {
            return sequence;
        }
        for (String line : Files.readAllLines(rawJsonl, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> event;
            try {
                event = MAPPER.readValue(line, LinkedHashMap.class);
            } catch (IOException e) {
                continue;
            }
            if (!"SUCCESS".equals(event.get("status"))) {
                continue;
            }
            Object returned = event.get("returnedTools");
            if (returned instanceof List<?> names) {
                for (Object name : names) {
                    if (name != null && !name.toString().isBlank()) {
                        sequence.add(name.toString());
                    }
                }
            }
        }
        return sequence;
    }

    /** 子序列匹配（不要求连续）：required 里的每个工具按顺序在 sequence 中各出现一次。 */
    private static boolean containsInOrder(List<String> sequence, List<String> required) {
        int cursor = 0;
        for (String tool : sequence) {
            if (cursor < required.size() && tool.equals(required.get(cursor))) {
                cursor++;
            }
        }
        return cursor == required.size();
    }

    private TrajectoryVerifier() {
    }
}
