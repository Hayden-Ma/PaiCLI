import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Usage: java hidden/tool-batch-transform/Check.java {workspace} */
public class Check {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path data = workspace.resolve("data");
        int failures = 0;
        failures += checkSum(data, "sales_q1.txt", 60);
        failures += checkSum(data, "sales_q2.txt", 20);
        failures += checkSum(data, "sales_q3.txt", 100);

        Path note = data.resolve("note.md");
        if (!Files.exists(note)) {
            System.out.println("FAIL: note.md 被删除了");
            failures++;
        }

        System.out.println(failures == 0 ? "PASS" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int checkSum(Path data, String sourceName, long expectedTotal) throws Exception {
        Path source = data.resolve(sourceName);
        String summaryName = sourceName.replace(".txt", "") + ".sum.txt";
        Path summary = data.resolve(summaryName);
        if (!Files.exists(source)) {
            System.out.println("FAIL: 源文件 " + sourceName + " 被删除或改名了");
            return 1;
        }
        if (!Files.exists(summary)) {
            System.out.println("FAIL: 缺少汇总文件 " + summaryName);
            return 1;
        }
        String content = Files.readString(summary).trim();
        if (!("total=" + expectedTotal).equals(content)) {
            System.out.println("FAIL: " + summaryName + " 期望 total=" + expectedTotal + "，实际 \"" + content + "\"");
            return 1;
        }
        return 0;
    }
}
