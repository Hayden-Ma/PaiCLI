import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Usage: java hidden/code-find-answer/Check.java {workspace} */
public class Check {
    private static final Pattern RETRY_PATTERN = Pattern.compile("MAX_RETRY_ATTEMPTS\\s*=\\s*(\\d+)");

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path src = workspace.resolve("src");
        if (!Files.isDirectory(src)) {
            System.out.println("FAIL: src directory missing");
            System.exit(1);
        }

        String expected = null;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = RETRY_PATTERN.matcher(Files.readString(file));
                if (matcher.find()) {
                    expected = matcher.group(1);
                    break;
                }
            }
        }
        if (expected == null) {
            System.out.println("FAIL: MAX_RETRY_ATTEMPTS not found in src");
            System.exit(1);
        }

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
