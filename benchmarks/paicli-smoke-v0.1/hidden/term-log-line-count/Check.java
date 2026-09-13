import java.nio.file.Files;
import java.nio.file.Path;

/** Usage: java hidden/term-log-line-count/Check.java {workspace} */
public class Check {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path log = workspace.resolve("logs").resolve("app.log");
        if (!Files.exists(log)) {
            System.out.println("FAIL: logs/app.log missing");
            System.exit(1);
        }
        String expected = String.valueOf(Files.readAllLines(log).size());

        Path answer = workspace.resolve("answer.txt");
        if (!Files.exists(answer)) {
            System.out.println("FAIL: answer.txt missing");
            System.exit(1);
        }
        String content = Files.readString(answer).trim();
        if (expected.equals(content)) {
            System.out.println("PASS");
            System.exit(0);
        }
        System.out.println("FAIL: expected " + expected + ", got \"" + content + "\"");
        System.exit(1);
    }
}
