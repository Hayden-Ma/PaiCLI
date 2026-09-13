import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 隐藏测试判定器（cwd = suite 目录）。
 * 用法: java hidden/se-fix-calculator/Check.java {workspace}
 *
 * 把工作区源码与 hidden 目录里的 HiddenTest.java 一起编译到临时目录后运行，
 * 隐藏测试源码不进工作区，被评 agent 无法读取或篡改。
 */
public class Check {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath().normalize();
        Path hiddenTest = Path.of("hidden", "se-fix-calculator", "HiddenTest.java");
        Path classes = Files.createTempDirectory("bench-classes-");

        List<String> sources = new ArrayList<>();
        sources.add(hiddenTest.toAbsolutePath().toString());
        Path src = workspace.resolve("src");
        try (Stream<Path> walk = Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> sources.add(p.toAbsolutePath().toString()));
        }

        List<String> javacCommand = new ArrayList<>();
        javacCommand.add("javac");
        javacCommand.add("-encoding");
        javacCommand.add("UTF-8");
        javacCommand.add("-d");
        javacCommand.add(classes.toAbsolutePath().toString());
        javacCommand.addAll(sources);
        Process javac = new ProcessBuilder(javacCommand).redirectErrorStream(true).start();
        String javacOutput = new String(javac.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int javacCode = javac.waitFor();
        if (javacCode != 0) {
            System.out.println("编译失败 (exit " + javacCode + "):");
            System.out.println(javacOutput);
            System.exit(2);
        }

        Process test = new ProcessBuilder("java", "-Dfile.encoding=UTF-8",
                "-cp", classes.toAbsolutePath().toString(), "HiddenTest")
                .redirectErrorStream(true).start();
        String testOutput = new String(test.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        System.out.print(testOutput);
        System.exit(test.waitFor());
    }
}
