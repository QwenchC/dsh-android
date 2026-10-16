package com.dsh.android;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ProjectMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File file(File directory, String name, String content) throws Exception {
        File file = new File(directory, name); file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content); return file;
    }
    @Test public void preservesProjectsAndExcludesCredentials() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home"), target = temporary.newFolder("target");
        File original = file(source, "app/main.txt", "hello"); file(home, "project/docs/a.txt", "world");
        file(home, ".dsh/credentials.yaml", "secret");
        ProjectMigration.Result result = ProjectMigration.copy(source, home, target);
        assertTrue(result.problems(), result.complete()); assertEquals(2, result.copied);
        assertEquals("hello", Files.readString(new File(target, "app/main.txt").toPath()));
        assertEquals("world", Files.readString(new File(target, "project/docs/a.txt").toPath()));
        assertTrue(original.exists()); assertFalse(new File(target, ".dsh").exists());
    }
    @Test public void retryVerifiesIdenticalFiles() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home"), target = temporary.newFolder("target");
        file(source, "a.txt", "same"); assertTrue(ProjectMigration.copy(source, home, target).complete());
        ProjectMigration.Result retry = ProjectMigration.copy(source, home, target);
        assertTrue(retry.complete()); assertEquals(0, retry.copied); assertEquals(1, retry.identical);
    }
    @Test public void sameNameDifferentContentBlocksCompletion() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home"), target = temporary.newFolder("target");
        file(source, "a.txt", "new"); File existing = file(target, "a.txt", "old");
        ProjectMigration.Result result = ProjectMigration.copy(source, home, target);
        assertFalse(result.complete()); assertEquals(1, result.conflicts.size());
        assertEquals("old", Files.readString(existing.toPath()));
    }
    @Test public void directoryFileCollisionIsReported() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home"), target = temporary.newFolder("target");
        file(source, "folder/a", "data"); file(target, "folder", "existing");
        ProjectMigration.Result result = ProjectMigration.copy(source, home, target);
        assertFalse(result.complete()); assertFalse(result.failures.isEmpty());
    }
    @Test public void destinationFailureDoesNotTouchSource() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home");
        File original = file(source, "a", "retained"), target = temporary.newFile("not-directory");
        ProjectMigration.Result result = ProjectMigration.copy(source, home, target);
        assertFalse(result.complete()); assertEquals("retained", Files.readString(original.toPath()));
    }
    @Test public void collisionsBetweenSourceRootsRemainVisible() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home"), target = temporary.newFolder("target");
        file(source, "project/a", "one"); file(home, "project/a", "two");
        ProjectMigration.Result result = ProjectMigration.copy(source, home, target);
        assertFalse(result.complete()); assertEquals(1, result.conflicts.size());
    }
    @Test public void symbolicLinksAreRefusedWithoutCopyingSecrets() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home"), target = temporary.newFolder("target");
        File secret = file(home, ".dsh/secret", "secret");
        try { Files.createSymbolicLink(new File(source, "leak").toPath(), secret.toPath()); }
        catch (java.io.IOException | UnsupportedOperationException unavailable) { org.junit.Assume.assumeNoException(unavailable); }
        ProjectMigration.Result result = ProjectMigration.copy(source, home, target);
        assertFalse(result.complete()); assertFalse(new File(target, "leak").exists());
    }
    @Test public void destinationInsideSourceCannotRecurseIntoItself() throws Exception {
        File source = temporary.newFolder("source"), home = temporary.newFolder("home");
        file(source, "a", "retained");
        ProjectMigration.Result result = ProjectMigration.copy(source, home, new File(source, "nested"));
        assertFalse(result.complete()); assertFalse(new File(source, "nested/nested").exists());
    }
}
