package com.edgedeploy.worker.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * File access for untrusted repository content. Reads never follow symbolic links and never leave
 * the given root, and are size-bounded; deletion never follows links either.
 */
public final class SafeFiles {

    /** A single path segment such as "package.json"; no separators, no "..". */
    private static final Pattern FILE_NAME = Pattern.compile("^(?!\\.\\.?$)[A-Za-z0-9._-]{1,255}$");

    private SafeFiles() {
    }

    /** True if {@code root/name} is a regular file (not a symlink, not a directory). */
    public static boolean isRegularFile(Path root, String name) {
        return resolveChild(root, name)
                .map(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .orElse(false);
    }

    /** Reads {@code root/name} as UTF-8 if it is a regular file no larger than {@code maxBytes}. */
    public static Optional<String> readRegularFile(Path root, String name, int maxBytes) throws IOException {
        Optional<Path> path = resolveChild(root, name);
        if (path.isEmpty() || !Files.isRegularFile(path.get(), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.size(path.get()) > maxBytes) {
            throw new IOException(name + " is larger than " + maxBytes + " bytes");
        }
        try (InputStream in = Files.newInputStream(path.get(), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8));
        }
    }

    /** Deletes a directory tree. Symbolic links are removed themselves; their targets are never touched. */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Git marks some files/directories read-only; we own them, so make them deletable.
                dir.toFile().setWritable(true, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Optional<Path> resolveChild(Path root, String name) {
        if (!FILE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain file name: " + name);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path child = normalizedRoot.resolve(name).normalize();
        return child.getParent().equals(normalizedRoot) ? Optional.of(child) : Optional.empty();
    }
}
