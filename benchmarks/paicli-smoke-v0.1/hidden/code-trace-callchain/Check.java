import java.nio.file.Files;
import java.nio.file.Path;

/** Usage: java hidden/code-trace-callchain/Check.java {workspace} */
public class Check {
    private static final String EXPECTED = "8000";

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path answer = workspace.resolve("answer.txt");
        if (!Files.exists(answer)) {
            System.out.println("FAIL: answer.txt missing");
            System.exit(1);
        }
        String content = Files.readString(answer).trim();
        if (EXPECTED.equals(content)) {
            System.out.println("PASS");
            System.exit(0);
        }
        System.out.println("FAIL: expected " + EXPECTED + ", got \"" + content + "\"");
        System.exit(1);
    }
}
