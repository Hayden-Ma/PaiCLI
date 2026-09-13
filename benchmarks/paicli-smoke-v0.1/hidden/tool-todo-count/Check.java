import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Usage: java hidden/tool-todo-count/Check.java {workspace} */
public class Check {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path src = workspace.resolve("src");
        if (!Files.isDirectory(src)) {
            System.out.println("FAIL: src directory missing");
            System.exit(1);
        }

        int total = 0;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                total += countOccurrences(Files.readString(file, StandardCharsets.UTF_8), "TODO");
            }
        }

        Path answer = workspace.resolve("answer.txt");
        if (!Files.exists(answer)) {
            System.out.println("FAIL: answer.txt missing");
            System.exit(1);
        }
        String content = Files.readString(answer).trim();
        if (String.valueOf(total).equals(content)) {
            System.out.println("PASS (" + total + " TODOs)");
            System.exit(0);
        }
        System.out.println("FAIL: expected " + total + ", got \"" + content + "\"");
        System.exit(1);
    }

    private static int countOccurrences(String text, String marker) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }
}
