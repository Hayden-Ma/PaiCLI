package com.paicli.skill;

import com.paicli.llm.LlmClient;
import com.paicli.tool.LoadedSkillMessages;
import com.paicli.tool.ToolResultBoundary;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    @Test
    void loadsExistingSkillAndBuildsSameTurnMessage(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "web-access", "决策手册",
                "# Body\nwhen to fetch\nwhen to browse\n"));

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"web-access\"}");

        assertTrue(results.get(0).result().contains("已加载 skill 'web-access'"), results.get(0).result());
        assertFalse(results.get(0).result().contains("when to fetch"), "正文不应进入 untrusted 工具结果");
        String message = LoadedSkillMessages.from(results, tools.getSkillRegistry());
        assertTrue(message.contains("## 已加载 Skill：web-access"), message);
        assertTrue(message.contains("when to fetch"), message);
    }

    @Test
    void unknownSkillProducesNoInjection(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "real-one", "desc", "body"));

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"nonexistent\"}");

        assertTrue(results.get(0).result().contains("未找到"), results.get(0).result());
        assertEquals("", LoadedSkillMessages.from(results, tools.getSkillRegistry()));
    }

    @Test
    void disabledSkillProducesNoInjection(@TempDir Path tempDir) throws IOException {
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        state.disable("web-access");
        SkillRegistry registry = new SkillRegistry(null,
                writeUserSkill(tempDir, "web-access", "desc", "body").getParent().getParent(),
                null, state);
        registry.reload();
        ToolRegistry tools = toolsWith(registry);

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"web-access\"}");

        assertTrue(results.get(0).result().contains("已被禁用"), results.get(0).result());
        assertEquals("", LoadedSkillMessages.from(results, tools.getSkillRegistry()));
    }

    @Test
    void truncatesOversizedBody(@TempDir Path tempDir) throws IOException {
        StringBuilder big = new StringBuilder();
        while (big.length() < 6 * 1024) big.append("0123456789");
        ToolRegistry tools = toolsWith(registryWith(tempDir, "huge", "desc", big.toString()));

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"huge\"}");

        assertTrue(results.get(0).result().contains("已加载 skill 'huge'"));
        String message = LoadedSkillMessages.from(results, tools.getSkillRegistry());
        assertTrue(message.contains("正文过长已截断"), "应包含截断标记");
    }

    @Test
    void truncationHintPointsToCallableToolWithFileOffset(@TempDir Path tempDir) throws IOException {
        StringBuilder big = new StringBuilder();
        for (int line = 1; big.length() < 6 * 1024; line++) big.append("line-").append(line).append(" 0123456789\n");
        ToolRegistry tools = toolsWith(registryWith(tempDir, "huge", "desc", big.toString()));

        String message = LoadedSkillMessages.from(load(tools, "{\"name\":\"huge\"}"), tools.getSkillRegistry());

        assertFalse(message.contains("/skill show"), "模型调用不了用户命令");
        java.util.regex.Matcher offset = java.util.regex.Pattern
                .compile("load_skill\\(name=\"huge\", file=\"SKILL.md\", offset=(\\d+)\\)").matcher(message);
        assertTrue(offset.find(), message.substring(message.length() - 200));
        String continued = tools.executeTool("load_skill",
                "{\"name\":\"huge\",\"file\":\"SKILL.md\",\"offset\":" + offset.group(1) + ",\"limit\":1}");
        String lastShown = message.substring(0, message.indexOf("...(正文过长已截断")).trim();
        lastShown = lastShown.substring(lastShown.lastIndexOf('\n') + 1);
        int lastShownNumber = Integer.parseInt(lastShown.substring(5, lastShown.indexOf(' ')));
        assertTrue(continued.contains("line-" + (lastShownNumber + 1) + " "), continued);
    }

    @Test
    void injectionCarriesSourceAndSkillDirectory(@TempDir Path tempDir) throws IOException {
        Path projectSkills = tempDir.resolve("project").resolve(".paicli").resolve("skills");
        Path skillDir = projectSkills.resolve("deploy");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: deploy\ndescription: d\n---\n先读 references/a.md\n");
        Files.writeString(skillDir.resolve("references").resolve("a.md"), "REFERENCE_A");
        SkillRegistry registry = new SkillRegistry(null, null, projectSkills,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        registry.trust(registry.findSkill("deploy"));
        ToolRegistry tools = toolsWith(registry);

        String message = LoadedSkillMessages.from(load(tools, "{\"name\":\"deploy\"}"), registry);

        assertTrue(message.contains("## 已加载 Skill：deploy（来源：project）"), message);
        assertTrue(message.contains("Skill 目录：" + skillDir.toAbsolutePath().normalize()), message);
        assertTrue(tools.executeTool("load_skill", "{\"name\":\"deploy\",\"file\":\"references/a.md\"}")
                .contains("REFERENCE_A"));
        assertTrue(tools.executeTool("load_skill", "{\"name\":\"deploy\",\"file\":\"references\"}")
                .contains("references/a.md"));
    }

    @Test
    void fileReadsCannotEscapeSkillDirectory(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "demo", "desc", "demo-body"));
        Files.writeString(tempDir.resolve("secret.txt"), "SECRET");

        List<ToolExecutionResult> results = load(tools, "{\"name\":\"demo\",\"file\":\"../../secret.txt\"}");

        assertFalse(results.get(0).result().contains("SECRET"), results.get(0).result());
        assertTrue(results.get(0).result().contains("不在 Skill 'demo' 的目录内"), results.get(0).result());
        assertEquals("", LoadedSkillMessages.from(results, tools.getSkillRegistry()), "读附属文件不注入正文");
    }

    @Test
    void skillAlreadyInHistoryIsNotInjectedAgain(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "demo", "desc", "demo-body"));
        List<LlmClient.Message> history = new ArrayList<>();
        LoadedSkillMessages.Injection first = LoadedSkillMessages.prepare(
                load(tools, "{\"name\":\"demo\"}"), tools.getSkillRegistry(), history);
        history.add(LlmClient.Message.user(first.message()));

        LoadedSkillMessages.Injection second = LoadedSkillMessages.prepare(
                load(tools, "{\"name\":\"demo\"}"), tools.getSkillRegistry(), history);

        assertTrue(first.message().contains("demo-body"));
        assertEquals("", second.message());
        assertTrue(second.results().get(0).result().contains("已在本会话加载"), second.results().get(0).result());
        assertTrue(second.results().get(0).successful());
    }

    @Test
    void untrustedProjectSkillStaysInUntrustedToolResultUntilTrusted(@TempDir Path tempDir) throws IOException {
        Path projectSkills = tempDir.resolve("project").resolve(".paicli").resolve("skills");
        Files.createDirectories(projectSkills.resolve("deploy"));
        Path skillMd = projectSkills.resolve("deploy").resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: deploy\ndescription: d\n---\nPROJECT_BODY rm -rf build\n");
        SkillRegistry registry = new SkillRegistry(null, null, projectSkills,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        ToolRegistry tools = toolsWith(registry);

        List<ToolExecutionResult> untrusted = load(tools, "{\"name\":\"deploy\"}");
        assertTrue(untrusted.get(0).result().contains("尚未被用户信任"), untrusted.get(0).result());
        assertTrue(untrusted.get(0).result().contains("PROJECT_BODY"), "正文作为不可信数据留在工具结果里");
        assertTrue(ToolResultBoundary.wrap(untrusted.get(0)).contains("untrusted-data"));
        assertEquals("", LoadedSkillMessages.from(untrusted, registry));

        registry.trust(registry.findSkill("deploy"));
        List<ToolExecutionResult> trusted = load(tools, "{\"name\":\"deploy\"}");
        assertFalse(trusted.get(0).result().contains("PROJECT_BODY"));
        assertTrue(LoadedSkillMessages.from(trusted, registry).contains("PROJECT_BODY"));

        Files.writeString(skillMd, "---\nname: deploy\ndescription: d\n---\nPROJECT_BODY v2\n");
        registry.reload();
        assertFalse(registry.isTrusted(registry.findSkill("deploy")), "正文改动后需要重新信任");
    }

    @Test
    void sameSkillLoadedTwiceInOneBatchIsInjectedOnce(@TempDir Path tempDir) throws IOException {
        ToolRegistry tools = toolsWith(registryWith(tempDir, "demo", "desc", "demo-body"));

        List<ToolExecutionResult> results = tools.executeTools(List.of(
                new ToolInvocation("call-1", "load_skill", "{\"name\":\"demo\"}"),
                new ToolInvocation("call-2", "load_skill", "{\"name\":\"demo\"}")));

        String message = LoadedSkillMessages.from(results, tools.getSkillRegistry());
        assertEquals(message.indexOf("## 已加载 Skill：demo"), message.lastIndexOf("## 已加载 Skill：demo"));
    }

    @Test
    void failsWhenNameMissing() {
        ToolRegistry tools = toolsWith(new SkillRegistry(null, null, null, null));

        String result = tools.executeTool("load_skill", "{}");
        assertTrue(result.contains("name 不能为空"), result);
    }

    private static ToolRegistry toolsWith(SkillRegistry registry) {
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        return tools;
    }

    private static List<ToolExecutionResult> load(ToolRegistry tools, String argumentsJson) {
        return tools.executeTools(List.of(new ToolInvocation("call-1", "load_skill", argumentsJson)));
    }

    private static SkillRegistry registryWith(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = writeUserSkill(tempDir, name, desc, body).getParent().getParent();
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, state);
        registry.reload();
        return registry;
    }

    private static Path writeUserSkill(Path tempDir, String name, String desc, String body) throws IOException {
        Path userRoot = tempDir.resolve("user-skills");
        Path skillDir = userRoot.resolve(name);
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd,
                "---\nname: " + name
                        + "\ndescription: " + desc
                        + "\n---\n" + body + "\n");
        return skillMd;
    }
}
