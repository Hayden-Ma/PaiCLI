import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Usage: java hidden/term-backup-file/Check.java {workspace} */
public class Check {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        int failures = 0;

        Path original = workspace.resolve("data").resolve("report.txt");
        Path backup = workspace.resolve("data").resolve("report.backup.txt");
        Path baseline = Path.of("hidden", "term-backup-file", "baseline-report.txt");

        if (!Files.exists(original)) {
            System.out.println("FAIL: 原 data/report.txt 不见了");
            failures++;
        } else if (!Arrays.equals(Files.readAllBytes(original), Files.readAllBytes(baseline))) {
            System.out.println("FAIL: 原 data/report.txt 内容被修改");
            failures++;
        }
        if (!Files.exists(backup)) {
            System.out.println("FAIL: data/report.backup.txt 不存在");
            failures++;
        } else if (!Arrays.equals(Files.readAllBytes(backup), Files.readAllBytes(baseline))) {
            System.out.println("FAIL: 备份内容与原文件不一致");
            failures++;
        }

        System.out.println(failures == 0 ? "PASS" : failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
