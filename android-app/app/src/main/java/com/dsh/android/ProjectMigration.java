package com.dsh.android;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resumable, verified copying of stopped projects; sources and conflicting targets survive. */
final class ProjectMigration {
    static final class Result {
        int copied;
        int identical;
        final List<String> conflicts = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        boolean complete() { return conflicts.isEmpty() && failures.isEmpty(); }
        String problems() {
            List<String> lines = new ArrayList<>(conflicts);
            lines.addAll(failures);
            return String.join("\n", lines);
        }
    }

    static Result copy(File workspace, File home, File destination) {
        Result result = new Result();
        Path target = destination.toPath();
        try {
            if (Files.isSymbolicLink(target)) throw new IOException("destination is a symbolic link");
            Files.createDirectories(target);
            target = target.toRealPath();
            for (File sourceRoot : new File[] { workspace, home }) {
                if (sourceRoot.exists() && target.startsWith(sourceRoot.toPath().toRealPath())) {
                    throw new IOException("destination cannot be inside a source tree");
                }
            }
            // Do not follow links in either source tree, including links into .dsh.
            copyDirectory(workspace.toPath(), target, target, result, true);
            if (Files.exists(home.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(home.toPath())) throw new IOException("home is a symbolic link");
                File[] projects = home.listFiles();
                if (projects == null) throw new IOException("cannot list " + home);
                List<File> ordered = new ArrayList<>();
                Collections.addAll(ordered, projects);
                ordered.sort((a, b) -> a.getName().compareTo(b.getName()));
                for (File project : ordered) {
                    if (!project.getName().equals(".dsh")) copyEntry(project.toPath(), target.resolve(project.getName()), target, result);
                }
            }
        } catch (IOException | RuntimeException failure) {
            result.failures.add(failure.getMessage());
        }
        return result;
    }

    private static void checkTarget(Path path, Path root) throws IOException {
        if (!path.normalize().startsWith(root)) throw new IOException("destination escapes project folder: " + path);
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("destination symbolic link refused: " + current);
        }
    }

    private static void copyDirectory(Path source, Path target, Path root, Result result, boolean optional) throws IOException {
        if (optional && !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(source)) throw new IOException("source symbolic link refused: " + source);
        checkTarget(target, root);
        Files.createDirectories(target);
        File[] children = source.toFile().listFiles();
        if (children == null) throw new IOException("cannot list " + source);
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        for (File child : children) copyEntry(child.toPath(), target.resolve(child.getName()), root, result);
    }

    private static void copyEntry(Path source, Path target, Path root, Result result) {
        try {
            checkTarget(target, root);
            if (Files.isSymbolicLink(source)) throw new IOException("source symbolic link refused: " + source);
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                copyDirectory(source, target, root, result, false);
                return;
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular file: " + source);
            byte[] expected = digest(source);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && MessageDigest.isEqual(expected, digest(target))) result.identical++;
                else result.conflicts.add("Different existing file: " + target);
                return;
            }
            Path staged = Files.createTempFile(target.getParent(), ".dsh-copy-", ".tmp");
            try {
                Files.copy(source, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!MessageDigest.isEqual(expected, digest(staged)) || !MessageDigest.isEqual(expected, digest(source))) {
                    throw new IOException("source changed or copy verification failed: " + source);
                }
                checkTarget(target, root);
                // No REPLACE_EXISTING: a target created meanwhile remains untouched.
                Files.move(staged, target);
                if (!MessageDigest.isEqual(expected, digest(target))) throw new IOException("destination verification failed: " + target);
                result.copied++;
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException | RuntimeException failure) {
            result.failures.add(source + ": " + failure.getMessage());
        }
    }

    private static byte[] digest(Path path) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
            return hash.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
