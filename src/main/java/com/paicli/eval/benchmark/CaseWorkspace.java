package com.paicli.eval.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** 把评测 fixture 物化到一次性临时工作区，并提供递归清理。 */
public final class CaseWorkspace {
    private CaseWorkspace() {
    }

    /** 复制 fixture 目录到新的临时工作区，返回工作区路径。 */
    public static Path materialize(Path fixtureDirectory) throws IOException {
        if (fixtureDirectory == null || !Files.isDirectory(fixtureDirectory)) {
            throw new IOException("fixture 不是目录或不存在: " + fixtureDirectory);
        }
        try (Stream<Path> entries = Files.list(fixtureDirectory)) {
            if (entries.findAny().isEmpty()) {
                throw new IOException("fixture 目录为空: " + fixtureDirectory);
            }
        }
        Path workspace = Files.createTempDirectory("paicli-bench-ws-");
        copyTree(fixtureDirectory, workspace);
        return workspace;
    }

    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理尽力而为；残留目录不影响评测结果，只影响磁盘。
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path from : paths.toList()) {
                Path relative = source.relativize(from);
                Path to = target.resolve(relative);
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
