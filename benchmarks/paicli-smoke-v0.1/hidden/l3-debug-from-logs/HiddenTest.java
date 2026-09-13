import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 隐藏测试：不在工作区内，被评 agent 不可见。 */
public class HiddenTest {
    public static void main(String[] args) throws Exception {
        int failures = 0;

        failures += expect("divide(9,3) == 3", Divider.divide(9, 3) == 3);
        failures += expect("divide(-7,2) == -3（保持 Java 截断语义）", Divider.divide(-7, 2) == -3);
        failures += expect("divide(10,0) == -1（崩溃已修复）", Divider.divide(10, 0) == -1);
        failures += expect("divide(0,5) == 0", Divider.divide(0, 5) == 0);

        Path fixNote = Path.of(args[0]).resolve("fix.txt");
        if (!Files.exists(fixNote) || !Files.readString(fixNote).contains("Fixed")) {
            System.out.println("FAIL: fix.txt 缺失或未包含 Fixed");
            failures++;
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
