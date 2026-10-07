package com.paicli.skill;

import com.paicli.policy.PolicyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Skill 参考资料（references/）的读取与用户级写回。
 *
 * <p>读取合并两处：Skill 自身目录下的 references/（内置 skill 即 ~/.paicli/skills-cache/&lt;name&gt;/），
 * 以及用户级补充目录 ~/.paicli/skills/&lt;name&gt;/references/。补充目录不需要 SKILL.md，
 * 所以不会被 {@link SkillRegistry} 当成同名 skill 覆盖内置版本。</p>
 *
 * <p>写入只落在用户级补充目录的 references/ 下，只写 .md，拒绝 ..、隐藏段和符号链接，
 * 不经过也不放宽 PathGuard；内置缓存目录永远只读。</p>
 */
public final class SkillReferences {

    static final String REFERENCES_DIR = "references";
    static final int MAX_READ_CHARS = 20_000;
    static final int MAX_WRITE_CHARS = 8_000;
    static final int MAX_OVERLAY_FILE_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 4;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private SkillReferences() {
    }

    /**
     * 读取 references 下的目录（列出合并后的条目）或文件（依次返回 Skill 自带版本和用户级补充）。
     *
     * @param overlayDir 用户级补充目录 ~/.paicli/skills/&lt;name&gt;/，可为 null
     */
    public static String read(Skill skill, Path overlayDir, String file) {
        List<String> segments = parseFile(file);
        String display = displayPath(segments);
        List<Source> sources = sources(skill, overlayDir);

        boolean anyFile = false;
        boolean anyDir = false;
        for (Source source : sources) {
            Path target = resolveExisting(source, segments);
            if (target == null) continue;
            if (Files.isRegularFile(target)) anyFile = true;
            if (Files.isDirectory(target)) anyDir = true;
        }
        if (anyFile) {
            return readFile(sources, segments, display);
        }
        if (anyDir) {
            return listDirectory(skill, sources, segments, display);
        }
        throw new IllegalArgumentException("Skill '" + skill.name() + "' 没有参考资料 " + display
                + "，可先用 file=\"references\" 列出已有条目");
    }

    /**
     * 把 content 写进用户级补充目录的 references/ 下；默认追加，overwrite=true 时整体替换该补充文件。
     */
    public static String write(Skill skill, Path overlayDir, String file, String content, boolean overwrite)
            throws IOException {
        if (!SKILL_NAME.matcher(skill.name()).matches()) {
            throw new PolicyException("Skill 名不是 kebab-case，拒绝写回: " + skill.name());
        }
        if (overlayDir == null) {
            throw new IllegalArgumentException("用户级 Skill 目录未配置，无法写回参考资料");
        }
        List<String> segments = parseFile(file);
        if (segments.isEmpty() || !segments.get(segments.size() - 1).endsWith(".md")) {
            throw new PolicyException("只能写 references/ 下的 .md 文件: " + file);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String body = content.strip();
        if (body.length() > MAX_WRITE_CHARS) {
            throw new PolicyException("单次写回最多 " + MAX_WRITE_CHARS + " 字符，当前 " + body.length()
                    + "，只写新发现的陷阱和有效模式");
        }
        String normalized = body + "\n";

        Path referencesRoot = overlayDir.resolve(REFERENCES_DIR);
        Path parent = ensureDirectories(referencesRoot, segments.subList(0, segments.size() - 1));
        Path target = parent.resolve(segments.get(segments.size() - 1));
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new PolicyException("目标不是普通文件，拒绝写回: " + target);
        }

        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        String display = displayPath(segments);
        if (overwrite || !exists) {
            if (bytes.length > MAX_OVERLAY_FILE_BYTES) {
                throw new PolicyException("补充文件上限 " + MAX_OVERLAY_FILE_BYTES + " 字节");
            }
            Path temp = Files.createTempFile(parent, ".write-", ".tmp");
            try {
                Files.write(temp, bytes);
                moveIntoPlace(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
            return "已" + (exists ? "覆盖" : "新建") + "用户级参考资料 " + display + "（" + target + "，"
                    + normalized.length() + " 字符）。之后 load_skill(name=\"" + skill.name() + "\", file=\""
                    + display + "\") 会连同内置版本一起返回。";
        }

        long existingSize = Files.size(target);
        if (existingSize + bytes.length + 1 > MAX_OVERLAY_FILE_BYTES) {
            throw new PolicyException("补充文件将超过 " + MAX_OVERLAY_FILE_BYTES
                    + " 字节上限，请先读出整理后用 overwrite=true 重写");
        }
        // APPEND 单次写入，不做读-改-写，多个 PaiCLI 进程同时写回也不会互相覆盖
        byte[] separator = existingSize > 0 ? "\n".getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] payload = new byte[separator.length + bytes.length];
        System.arraycopy(separator, 0, payload, 0, separator.length);
        System.arraycopy(bytes, 0, payload, separator.length, bytes.length);
        Files.write(target, payload, StandardOpenOption.APPEND);
        return "已追加到用户级参考资料 " + display + "（" + target + "，" + normalized.length()
                + " 字符）。之后 load_skill(name=\"" + skill.name() + "\", file=\"" + display
                + "\") 会连同内置版本一起返回。";
    }

    /** file 可带或不带 "references/" 前缀，返回 references 之下的路径段；空列表表示 references 根。 */
    static List<String> parseFile(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        String value = file.trim();
        if (value.startsWith("/") || value.startsWith("~") || value.contains("\\") || value.contains(":")) {
            throw new PolicyException("file 必须是 Skill 内 references/ 下的相对路径: " + file);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        List<String> segments = new ArrayList<>(List.of(value.split("/", -1)));
        if (!segments.isEmpty() && REFERENCES_DIR.equals(segments.get(0))) {
            segments.remove(0);
        }
        if (segments.size() > MAX_DEPTH) {
            throw new PolicyException("file 层级过深: " + file);
        }
        for (String segment : segments) {
            // 段首必须是字母或数字，因此 ..、. 与隐藏文件都会被拒绝
            if (!SEGMENT.matcher(segment).matches()) {
                throw new PolicyException("file 含非法路径段 '" + segment + "': " + file);
            }
        }
        return List.copyOf(segments);
    }

    private static String readFile(List<Source> sources, List<String> segments, String display) {
        StringBuilder out = new StringBuilder();
        for (Source source : sources) {
            Path target = resolveExisting(source, segments);
            if (target == null || !Files.isRegularFile(target)) continue;
            String text;
            try {
                text = Files.readString(target);
            } catch (IOException e) {
                text = "(读取失败: " + e.getMessage() + ")";
            }
            if (text.length() > MAX_READ_CHARS) {
                text = text.substring(0, MAX_READ_CHARS) + "\n...(truncated)";
            }
            if (!out.isEmpty()) out.append("\n\n");
            out.append("### ").append(display).append("（").append(source.label()).append("）\n")
                    .append(text.strip());
        }
        return out.toString();
    }

    private static String listDirectory(Skill skill, List<Source> sources, List<String> segments, String display) {
        Map<String, Set<String>> entries = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        for (Source source : sources) {
            Path target = resolveExisting(source, segments);
            if (target == null || !Files.isDirectory(target)) continue;
            try (var stream = Files.list(target)) {
                stream.forEach(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) return;
                    String key = Files.isDirectory(p) ? name + "/" : name;
                    entries.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(source.shortLabel());
                });
            } catch (IOException e) {
                failures.add(source.label() + " 列目录失败: " + e.getMessage());
            }
        }
        StringBuilder out = new StringBuilder(display).append("/ 共 ").append(entries.size()).append(" 项");
        out.append("（标注来源：").append(String.join("；", sources.stream().map(Source::label).toList()))
                .append("）：\n");
        entries.forEach((name, labels) -> out.append("- ").append(name)
                .append("  [").append(String.join(", ", labels)).append("]\n"));
        failures.forEach(f -> out.append("⚠️ ").append(f).append('\n'));
        out.append("读取单个文件：load_skill(name=\"").append(skill.name()).append("\", file=\"")
                .append(display).append("/<文件名>\")");
        return out.toString();
    }

    private static List<Source> sources(Skill skill, Path overlayDir) {
        List<Source> sources = new ArrayList<>(2);
        Path own = skill.referencesDir();
        if (own != null) {
            sources.add(new Source(own, skill.displaySource(), skill.displaySource()));
        }
        if (overlayDir != null) {
            Path overlay = overlayDir.resolve(REFERENCES_DIR);
            boolean sameAsOwn = own != null
                    && own.toAbsolutePath().normalize().equals(overlay.toAbsolutePath().normalize());
            if (!sameAsOwn) {
                sources.add(new Source(overlay, "user", "用户级补充 " + overlay));
            }
        }
        return sources;
    }

    /** 解析到真实路径后仍须在该来源的 references 根之内，防止符号链接把读取带出目录。 */
    private static Path resolveExisting(Source source, List<String> segments) {
        Path root = source.root();
        if (!Files.isDirectory(root)) return null;
        Path target = root;
        for (String segment : segments) {
            target = target.resolve(segment);
        }
        if (!Files.exists(target)) return null;
        try {
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            return realTarget.startsWith(realRoot) ? realTarget : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 逐级创建 references 及其子目录；已存在的每一级都必须是真实目录，不能是符号链接。 */
    private static Path ensureDirectories(Path referencesRoot, List<String> dirSegments) throws IOException {
        Files.createDirectories(referencesRoot.getParent());
        Path current = ensureDirectory(referencesRoot);
        for (String segment : dirSegments) {
            current = ensureDirectory(current.resolve(segment));
        }
        return current;
    }

    private static Path ensureDirectory(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new PolicyException("用户级参考资料目录不能是符号链接: " + dir);
        }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dir);
        } else if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new PolicyException("路径已存在但不是目录: " + dir);
        }
        return dir;
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String displayPath(List<String> segments) {
        return segments.isEmpty() ? REFERENCES_DIR : REFERENCES_DIR + "/" + String.join("/", segments);
    }

    private record Source(Path root, String shortLabel, String label) {
    }
}
