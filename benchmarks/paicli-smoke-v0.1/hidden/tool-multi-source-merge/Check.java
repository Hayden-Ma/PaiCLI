import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Usage: java hidden/tool-multi-source-merge/Check.java {workspace} */
public class Check {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();

        long total = 0;
        for (String name : new String[]{"orders_north.csv", "orders_south.csv"}) {
            Path csv = workspace.resolve("data").resolve(name);
            if (!Files.exists(csv)) {
                System.out.println("FAIL: data/" + name + " missing");
                System.exit(1);
            }
            List<String> lines = Files.readAllLines(csv);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    System.out.println("FAIL: malformed csv row in " + name + ": " + line);
                    System.exit(1);
                }
                long qty = Long.parseLong(parts[1].trim());
                long price = Long.parseLong(parts[2].trim());
                total += qty * price;
            }
        }
        String expected = "total=" + total;

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
