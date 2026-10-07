package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.InternalUserMessages;
import com.paicli.skill.Skill;
import com.paicli.skill.SkillRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把本批工具结果里成功的 load_skill 整理成一条 user 消息，由调用方紧跟在工具结果之后追加，
 * 让同一轮的下一次 LLM 请求就能看到 SKILL.md 正文。
 *
 * <p>正文不放进工具结果本身：工具结果统一经 {@link ToolResultBoundary} 包成 untrusted-data，
 * 模型会被要求不执行其中的指令，而 Skill 恰恰是本地可信的操作指引。</p>
 *
 * <p>已激活集合从调用方传入的消息历史推导，不持有任何共享状态：ReAct 会话、
 * 每个 Plan 任务和每个 Team worker 各自只看自己的历史；压缩会把 Skill 注入原样挂回，
 * {@code /clear} 清空历史后自然可以重新加载。</p>
 */
public final class LoadedSkillMessages {

    static final String TOOL_NAME = "load_skill";
    static final int MAX_BODY_CHARS = 5 * 1024;

    private static final String SECTION_PREFIX = "## 已加载 Skill：";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LoadedSkillMessages() {
    }

    /**
     * @param results 调用方应改用它追加工具结果：重复加载的 load_skill 结果会被改写
     * @param message 可直接作为 user 消息内容的 Skill 指引；没有需要注入的正文时为空串
     */
    public record Injection(List<ToolRegistry.ToolExecutionResult> results, String message) {
        public Injection {
            results = results == null ? List.of() : List.copyOf(results);
            message = message == null ? "" : message;
        }
    }

    /** 不看历史的便捷入口，只在同一批内去重。 */
    public static String from(List<ToolRegistry.ToolExecutionResult> results, SkillRegistry registry) {
        return prepare(results, registry, List.of()).message();
    }

    public static Injection prepare(List<ToolRegistry.ToolExecutionResult> results,
                                    SkillRegistry registry,
                                    List<LlmClient.Message> history) {
        if (results == null || results.isEmpty() || registry == null) {
            return new Injection(results, "");
        }
        Set<String> active = activeSkills(history);
        Map<String, String> sections = new LinkedHashMap<>();
        List<ToolRegistry.ToolExecutionResult> rewritten = new ArrayList<>(results.size());
        for (ToolRegistry.ToolExecutionResult result : results) {
            Skill skill = injectableSkill(result, registry);
            if (skill == null) {
                rewritten.add(result);
            } else if (active.contains(skill.name()) || sections.containsKey(skill.name())) {
                rewritten.add(withResult(result, "Skill '" + skill.name() + "' 已在本会话加载，正文就在前面的 \""
                        + SECTION_PREFIX + skill.name() + "\" 段，直接按指引继续，不要再次加载。", true));
            } else {
                sections.put(skill.name(), section(skill));
                rewritten.add(result);
            }
        }
        if (sections.isEmpty()) {
            return new Injection(rewritten, "");
        }
        StringBuilder message = new StringBuilder(InternalUserMessages.SKILL_INJECTION_MARKER)
                .append('\n')
                .append("以下是刚才 load_skill 加载的 Skill 指引，请按指引继续当前任务。\n\n");
        sections.values().forEach(section -> message.append(section).append("\n\n"));
        return new Injection(rewritten, message.append("---").toString());
    }

    /** 历史里已经注入过正文的 Skill 名。 */
    static Set<String> activeSkills(List<LlmClient.Message> history) {
        Set<String> names = new LinkedHashSet<>();
        if (history == null) {
            return names;
        }
        for (LlmClient.Message message : history) {
            if (!InternalUserMessages.isSkillInjection(message)) {
                continue;
            }
            for (String line : message.content().split("\n")) {
                if (line.startsWith(SECTION_PREFIX)) {
                    String rest = line.substring(SECTION_PREFIX.length());
                    names.add(rest.trim());
                }
            }
        }
        return names;
    }

    private static String section(Skill skill) {
        return SECTION_PREFIX + skill.name() + '\n' + truncatedBody(skill).trim();
    }

    static String truncatedBody(Skill skill) {
        String body = skill.body() == null ? "" : skill.body();
        if (body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_BODY_CHARS)
                + "\n\n...(skill body truncated, full content via /skill show " + skill.name() + ")";
    }

    private static Skill injectableSkill(ToolRegistry.ToolExecutionResult result, SkillRegistry registry) {
        if (result == null || !TOOL_NAME.equals(result.name()) || !result.successful()) {
            return null;
        }
        JsonNode name = parse(result.argumentsJson()).path("name");
        // 与 load_skill 执行器一致，按原值查找，避免工具报“未找到”却注入了正文
        if (!name.isTextual() || name.asText().isBlank()) {
            return null;
        }
        return registry.findSkill(name.asText());
    }

    private static JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static ToolRegistry.ToolExecutionResult withResult(ToolRegistry.ToolExecutionResult result,
                                                               String text, boolean successful) {
        return new ToolRegistry.ToolExecutionResult(result.id(), result.name(), result.argumentsJson(), text,
                result.elapsedMillis(), result.timedOut(), result.imageParts(), successful, result.discoveredUrls());
    }
}
