import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) throws Exception {
        int failures = 0;

        Greeter greeter = new Greeter();
        failures += expect("greet() == Hello, PaiCLI!",
                "Hello, PaiCLI!".equals(greeter.greet()));

        // 同步更新过的可见回归检查必须自己通过（期望值与实现一致）
        failures += expect("GreeterTest.check() == 0", GreeterTest.check() == 0);

        // GreeterTest 不得仍停留在旧期望值上
        String testSource = Files.readString(Path.of(args[0]).resolve("src").resolve("GreeterTest.java"));
        failures += expect("GreeterTest 不再断言旧文案 Howdy!", !testSource.contains("Howdy!"));

        Path changelog = Path.of(args[0]).resolve("CHANGELOG.md");
        if (!Files.exists(changelog)) {
            System.out.println("FAIL: CHANGELOG.md missing");
            failures++;
        } else {
            List<String> lines = Files.readAllLines(changelog);
            boolean hasInit = lines.stream().anyMatch(l -> l.contains("- init project"));
            boolean hasNew = lines.stream().anyMatch(l -> l.contains("- greet greeting updated"));
            if (!hasInit) {
                System.out.println("FAIL: CHANGELOG.md 丢失原有内容 - init project");
                failures++;
            }
            if (!hasNew) {
                System.out.println("FAIL: CHANGELOG.md 未追加 - greet greeting updated");
                failures++;
            }
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int expect(String name, boolean condition) {
        if (!condition) {
            System.out.println("FAIL: " + name);
            return 1;
        }
        return 0;
    }
}
