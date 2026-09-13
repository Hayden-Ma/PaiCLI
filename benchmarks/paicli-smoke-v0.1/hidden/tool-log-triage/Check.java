import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Usage: java hidden/tool-log-triage/Check.java {workspace} */
public class Check {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path log = workspace.resolve("logs").resolve("app.log");
        if (!Files.exists(log)) {
            System.out.println("FAIL: logs/app.log missing");
            System.exit(1);
        }

        int count = 0;
        String last = "";
        List<String> lines = Files.readAllLines(log);
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[1].equals("ERROR")) {
                count++;
                last = parts[0];
            }
        }
        String expected = "count=" + count + ";last=" + last;

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
