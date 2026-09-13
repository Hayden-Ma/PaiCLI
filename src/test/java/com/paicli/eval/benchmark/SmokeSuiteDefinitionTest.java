package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 校验仓库内置 smoke suite 的结构有效性（权重守恒、verifier 命令合法）。 */
class SmokeSuiteDefinitionTest {
    private static final Path SUITE = Path.of("benchmarks", "paicli-smoke-v0.1", "suite.json");

    @Test
    void shouldLoadBuiltInSmokeSuite() throws Exception {
        SuiteDefinition suite = SuiteDefinition.load(SUITE);
        List<CaseDefinition> active = suite.activeCases();
        assertEquals(28, active.size());

        int weightSum = active.stream().mapToInt(CaseDefinition::weight).sum();
        assertEquals(100, weightSum);

        for (CaseDefinition definition : active) {
            assertEquals(CaseDefinition.Mode.REACT, definition.mode(),
                    "smoke suite v0.1 只支持 react 模式: " + definition.id());
            assertEquals(CaseDefinition.VerifierType.COMMAND, definition.verifierType(),
                    "smoke suite 每个 case 都应有可执行 verifier: " + definition.id());
            assertTrue(Files_isDirectory(suite.resolveFixture(definition)),
                    "fixture 必须是存在的目录: " + definition.id());
            assertTrue(suite.resolveVerifier(definition).arguments().stream()
                            .anyMatch(arg -> arg.contains("{workspace}")),
                    "verifier 命令必须引用 {workspace}: " + definition.id());
        }
    }

    @Test
    void shouldKeepAtLeastOneTrajectoryInstance() throws Exception {
        // 叠加层轨迹规则必须有真实实例，防止 verifierType 分支退化成死代码
        SuiteDefinition suite = SuiteDefinition.load(SUITE);
        long withRules = suite.activeCases().stream()
                .filter(definition -> java.nio.file.Files.exists(
                        SUITE.toAbsolutePath().getParent()
                                .resolve("hidden").resolve(definition.id()).resolve("trajectory.json")))
                .count();
        assertTrue(withRules >= 1, "至少一个 case 配置了叠加轨迹规则");
    }

    private static boolean Files_isDirectory(Path path) {
        return java.nio.file.Files.isDirectory(path);
    }
}
