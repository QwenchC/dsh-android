package com.dsh.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;

/**
 * Every path the Android runtime payload uses, derived from one root.
 *
 * The application's private data directory is the only location Android lets the
 * process write without a runtime grant, so the Harness home, the workspace, the
 * temporary directory, and the extracted runtime all live beneath it. The Node
 * executable and the helper binaries are the exception: they are native library
 * entries, because the application's writable directories are mounted no-exec.
 */
public final class RuntimeLayout {

    /** Version stamp written beside the extracted runtime; a mismatch reinstalls it. */
    static final String STAMP_FILE = "runtime.stamp";

    /** Asset holding the whole Harness runtime tree. */
    static final String RUNTIME_ASSET = "dsh-runtime.zip";

    private RuntimeLayout() {}

    /** The single directory every other path is derived from. */
    public static File root(Context context) {
        return new File(context.getFilesDir(), "dsh");
    }

    /** The extracted Harness runtime tree; the CLI entry resolves inside it. */
    public static File runtime(Context context) {
        return new File(root(context), "runtime");
    }

    /**
     * The extracted runtime's CLI entry point. A deployed installation puts the
     * `@deepseek-ai/dsh` package at the root of the tree and its dependencies
     * under `node_modules/`, which is where Node's own resolution expects them.
     */
    public static File cliEntry(Context context) {
        return new File(runtime(context), "lib/bin.js");
    }

    /** The user's home directory as Node sees it ({@code os.homedir()}). */
    public static File home(Context context) {
        return new File(root(context), "home");
    }

    /** The Harness home ({@code $DSH_HOME}): sessions, settings, credentials. */
    public static File dshHome(Context context) {
        return new File(home(context), ".dsh");
    }

    /** Temporary files; Android has no writable {@code /tmp}. */
    public static File tmp(Context context) {
        return new File(root(context), "tmp");
    }

    /** Helper executables the runtime bundle ships (a bash, a ripgrep). */
    public static File bin(Context context) {
        return new File(root(context), "bin");
    }

    /**
     * Shared libraries the Node executable resolves through
     * {@code LD_LIBRARY_PATH}. They keep the file names their SONAMEs require
     * and sit beside the Harness tree, never in {@code lib/}, which is the CLI
     * package's own compiled output.
     */
    public static File libraries(Context context) {
        return new File(new File(runtime(context), "nativelib"), abiDirectory());
    }

    /** The Node runtime's combined stdout and stderr. */
    public static File log(Context context) {
        return new File(root(context), "dsh.log");
    }

    /**
     * The Node executable: a native library entry, renamed so the platform
     * extracts it into the one directory it mounts executable.
     */
    public static File nodeExecutable(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        return new File(info.nativeLibraryDir, "libnode.so");
    }

    /** The ripgrep binary shipped beside the Node executable. */
    public static File ripgrep(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        return new File(info.nativeLibraryDir, "librg.so");
    }

    /** The bash binary shipped beside the Node executable, or null when absent. */
    public static File bash(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        File bash = new File(info.nativeLibraryDir, "libbash.so");
        return bash.isFile() ? bash : null;
    }

    /** Whether the connected device runs a 64-bit ABI this bundle carries. */
    public static String abiDirectory() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) return abi;
        }
        return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
    }
}
