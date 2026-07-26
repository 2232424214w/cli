package com.bettercli.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 本地 Skill ZIP 导入/导出（对标 1024 上传 ZIP；不做远程 marketplace）。
 *
 * <p>包内须含 {@code SKILL.md}（可在根或单层 skill 目录下）。导入时防 Zip Slip。
 */
public final class SkillZipPackager {

    public static final long MAX_UNCOMPRESSED_BYTES = 8L * 1024 * 1024;
    public static final int MAX_ENTRIES = 500;

    public record ImportResult(String skillName, Path skillDir, Path skillMd) {
    }

    private SkillZipPackager() {
    }

    public static Path exportZip(Skill skill, Path zipPath) throws IOException {
        if (skill == null || skill.skillMdPath() == null) {
            throw new IllegalArgumentException("skill 缺少 SKILL.md 路径");
        }
        Path skillDir = skill.skillMdPath().getParent();
        if (skillDir == null || !Files.isDirectory(skillDir)) {
            throw new IOException("skill 目录不存在: " + skill.skillMdPath());
        }
        Path target = zipPath;
        if (target == null) {
            throw new IllegalArgumentException("zip 路径不能为空");
        }
        if (Files.isDirectory(target)) {
            target = target.resolve(skill.name() + ".zip");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream fos = Files.newOutputStream(target);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            String rootPrefix = skill.name() + "/";
            Files.walkFileTree(skillDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = skillDir.relativize(file).toString().replace('\\', '/');
                    if (rel.startsWith(".git/") || rel.equals(".version")) {
                        return FileVisitResult.CONTINUE;
                    }
                    ZipEntry entry = new ZipEntry(rootPrefix + rel);
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return target.toAbsolutePath().normalize();
    }

    public static ImportResult importZip(Path zipPath, Path skillsRoot, boolean force) throws IOException {
        if (zipPath == null || !Files.isRegularFile(zipPath)) {
            throw new IOException("ZIP 不存在: " + zipPath);
        }
        if (skillsRoot == null) {
            throw new IllegalArgumentException("skills 根目录不能为空");
        }
        String lower = zipPath.getFileName().toString().toLowerCase();
        if (!lower.endsWith(".zip")) {
            throw new IOException("仅支持 .zip 文件");
        }

        Path staging = Files.createTempDirectory("bettercli-skill-import-");
        try {
            unzipSecure(zipPath, staging);
            Path skillMd = findSkillMd(staging);
            if (skillMd == null) {
                throw new IOException("ZIP 内未找到 SKILL.md");
            }
            Path sourceDir = skillMd.getParent();
            SkillFrontmatterParser.ParseResult parsed =
                    SkillFrontmatterParser.parse(Files.readString(skillMd));
            Object nameObj = parsed.frontmatter().get("name");
            String name = nameObj instanceof String s && !s.isBlank()
                    ? s.trim()
                    : (sourceDir != null ? sourceDir.getFileName().toString() : null);
            if (!SkillQuality.isValidName(name)) {
                throw new IOException("SKILL.md name 非法（需 kebab-case）: " + name);
            }

            Path dest = skillsRoot.resolve(name);
            if (Files.exists(dest) && !force) {
                throw new IOException("同名 skill 已存在: " + dest + "（加 --force 覆盖）");
            }
            if (Files.exists(dest)) {
                deleteRecursive(dest);
            }
            Files.createDirectories(skillsRoot);
            copyDirectory(sourceDir, dest);
            Path destMd = dest.resolve("SKILL.md");
            return new ImportResult(name, dest.toAbsolutePath().normalize(), destMd);
        } finally {
            deleteRecursive(staging);
        }
    }

    private static void unzipSecure(Path zipPath, Path destDir) throws IOException {
        Path destAbs = destDir.toAbsolutePath().normalize();
        long total = 0;
        int entries = 0;
        try (InputStream fis = Files.newInputStream(zipPath);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("ZIP 条目过多（>" + MAX_ENTRIES + "）");
                }
                Path out = destAbs.resolve(entry.getName()).normalize();
                if (!out.startsWith(destAbs)) {
                    throw new IOException("拒绝危险 ZIP 路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) >= 0) {
                        total += n;
                        if (total > MAX_UNCOMPRESSED_BYTES) {
                            throw new IOException("解压体积超过上限 " + MAX_UNCOMPRESSED_BYTES + " bytes");
                        }
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static Path findSkillMd(Path root) throws IOException {
        Path direct = root.resolve("SKILL.md");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        List<Path> found = new ArrayList<>();
        try (var stream = Files.walk(root, 2)) {
            stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .forEach(found::add);
        }
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            // 优先选仅一层目录的
            for (Path p : found) {
                Path parent = p.getParent();
                if (parent != null && parent.getParent() != null
                        && parent.getParent().equals(root)) {
                    return p;
                }
            }
        }
        return found.get(0);
    }

    private static void copyDirectory(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(dest.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Files.copy(file, dest.resolve(rel.toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
