import java.nio.file.Files;
import java.nio.file.Path;

/** Usage: java hidden/term-compile-run/Check.java {workspace} */
public class Check {
    private static final String EXPECTED = "HELLO-PAICLI-42";

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        int failures = 0;

        Path answer = workspace.resolve("answer.txt");
        if (!Files.exists(answer)) {
            System.out.println("FAIL: answer.txt missing");
            failures++;
        } else if (!EXPECTED.equals(Files.readString(answer).trim())) {
            System.out.println("FAIL: answer.txt expected " + EXPECTED
                    + ", got \"" + Files.readString(answer).trim() + "\"");
            failures++;
        }

        boolean compiled = Files.exists(workspace.resolve("out").resolve("Hello.class"));
        if (!compiled) {
            System.out.println("FAIL: out/Hello.class 不存在，说明没有完成编译步骤");
            failures++;
        }

        System.out.println(failures == 0 ? "PASS" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
