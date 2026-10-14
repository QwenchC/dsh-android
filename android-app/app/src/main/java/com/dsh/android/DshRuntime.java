package com.dsh.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the DeepSeek Harness process: installs its runtime payload, starts the
 * Node executable that serves the Web UI, and reports readiness.
 *
 * One runtime serves the whole application. The activity attaches to it and
 * renders its state; the foreground service keeps the process alive while the
 * user is elsewhere.
 */
public final class DshRuntime {

    private static final String TAG = "DshRuntime";

    /** Preferences holding the workspace choice and the last installed stamp. */
    private static final String PREFS = "dsh";
    private static final String PREF_WORKSPACE = "workspace";
    private static final String PREF_SHARED_STORAGE = "shared_storage";

    /** Lines of process output the log view keeps in memory. */
    private static final int LOG_RING = 400;

    /** How long the Web UI may take to answer before startup is called failed. */
    private static final long READY_TIMEOUT_MS = 180_000L;

    /** Lifecycle of the managed process. */
    public enum State { IDLE, INSTALLING, STARTING, READY, FAILED, STOPPED }

    /** Receives every state transition on the runtime's worker thread. */
    public interface Listener {
        /** @param state the new state; {@code detail} explains a failure. */
        void onRuntimeState(State state, String detail);
    }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dsh-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> recentLog = new ArrayDeque<>();

    private volatile State state = State.IDLE;
    private volatile String detail = "";
    /**
     * Keeps the CPU running while the Harness does.
     *
     * A foreground service keeps the process alive but not the processor:
     * once the screen goes off the device suspends, the event loop stops
     * turning, an in-flight model request stalls mid-stream, and nothing
     * resumes until something wakes the device. The lock is what makes a turn
     * survive the screen going off, and it is released the moment the runtime
     * stops.
     */
    private android.os.PowerManager.WakeLock wakeLock;

    private volatile Process process;
    private volatile int port;
    private volatile String baseUrl;
    /** The origin the runtime printed, which carries the session's access token. */
    private volatile String announcedUrl;

    DshRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The current state. */
    public State state() {
        return state;
    }

    /** The explanation attached to the current state; empty when there is none. */
    public String detail() {
        return detail;
    }

    /** The loopback origin the runtime prints, token included. */
    private static final java.util.regex.Pattern LOOPBACK_URL =
            java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+");

    /** The Web UI origin once {@link State#READY}, otherwise null. */
    public String baseUrl() {
        return baseUrl;
    }

    /** The port the Web UI is served on once {@link State#READY}, otherwise 0. */
    public int port() {
        return port;
    }

    /** Registers a listener and immediately reports the current state to it. */
    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onRuntimeState(state, detail);
    }

    /** Removes a listener. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Snapshot of the process output retained for the log view. */
    public synchronized String logText() {
        StringBuilder text = new StringBuilder();
        for (String line : recentLog) text.append(line).append('\n');
        return text.toString();
    }

    /** Starts installation and the server unless a run is already in flight. */
    public synchronized void start() {
        if (state == State.INSTALLING || state == State.STARTING || state == State.READY) return;
        worker.execute(this::run);
    }

    /** Stops the server and everything it spawned. */
    public synchronized void stop() {
        Process running = process;
        process = null;
        if (running != null) {
            running.destroy();
            try {
                if (!running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) running.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                running.destroyForcibly();
            }
        }
        baseUrl = null;
        releaseWakeLock();
        transition(State.STOPPED, "");
    }

    /** Whether the process is alive. */
    public boolean isRunning() {
        Process running = process;
        return running != null && running.isAlive();
    }

    private void run() {
        try {
            announcedUrl = null;
            File root = RuntimeLayout.root(context);
            if (!root.isDirectory() && !root.mkdirs()) {
                fail("cannot create the runtime root at " + root);
                return;
            }
            installRuntime();
            prepareDirectories();
            acquireWakeLock();
            int selected = freePort();
            transition(State.STARTING, "port " + selected);
            Process started = launch(selected);
            process = started;
            port = selected;
            if (!awaitReady(started)) {
                if (state == State.STARTING) {
                    fail(started.isAlive()
                            ? "the Web UI did not announce its origin on port " + selected + " within "
                              + (READY_TIMEOUT_MS / 1000) + "s"
                            : "the runtime exited with code " + started.exitValue());
                }
                return;
            }
            baseUrl = announcedUrl;
            transition(State.READY, baseUrl);
            started.waitFor();
            if (process == started) {
                process = null;
                baseUrl = null;
                releaseWakeLock();
                transition(State.STOPPED, "the runtime exited with code " + started.exitValue());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            releaseWakeLock();
        } catch (Exception failure) {
            Log.e(TAG, "runtime start failed", failure);
            releaseWakeLock();
            fail(String.valueOf(failure.getMessage()));
        }
    }

    /** Hold the processor awake for as long as the Harness runs. */
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        android.os.PowerManager.WakeLock lock =
                power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dsh:harness");
        lock.setReferenceCounted(false);
        lock.acquire();
        wakeLock = lock;
        appendLog("[runtime] holding the processor awake while the harness runs");
    }

    /** Release the processor; the runtime is no longer running. */
    private void releaseWakeLock() {
        android.os.PowerManager.WakeLock lock = wakeLock;
        wakeLock = null;
        if (lock != null && lock.isHeld()) lock.release();
    }

    /** Extracts the packaged runtime tree when the installed stamp is stale. */
    private void installRuntime() throws IOException {
        String stamp = stampValue();
        File installed = new File(RuntimeLayout.root(context), RuntimeLayout.STAMP_FILE);
        if (RuntimeLayout.cliEntry(context).isFile() && stamp.equals(readStamp(installed))) return;

        transition(State.INSTALLING, "unpacking the runtime");
        File runtime = RuntimeLayout.runtime(context);
        deleteTree(runtime);
        if (!runtime.mkdirs() && !runtime.isDirectory()) throw new IOException("cannot create " + runtime);

        long entries = 0;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open(RuntimeLayout.RUNTIME_ASSET))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(runtime, entry.getName());
                if (!isInside(runtime, target)) throw new IOException("unsafe entry " + entry.getName());
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("cannot create " + target);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("cannot create " + parent);
                    }
                    try (OutputStream out = new FileOutputStream(target)) {
                        int read;
                        while ((read = zip.read(buffer)) > 0) out.write(buffer, 0, read);
                    }
                    // Helper executables travel inside the archive: the
                    // runtime's own bin/ scripts and node-pty's spawn-helper,
                    // which lives beside the PTY binding it belongs to.
                    if (entry.getName().startsWith("bin/") || entry.getName().endsWith("/spawn-helper")) {
                        target.setExecutable(true, false);
                    }
                }
                entries++;
                zip.closeEntry();
            }
        }
        try (OutputStream out = new FileOutputStream(installed)) {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
        }
        appendLog("[runtime] installed " + entries + " entries");
    }

    /** Creates the writable directories and makes the shipped binaries executable. */
    private void prepareDirectories() throws IOException {
        for (File directory : new File[] {
                RuntimeLayout.home(context), RuntimeLayout.dshHome(context),
                RuntimeLayout.tmp(context), RuntimeLayout.bin(context), workspace() }) {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("cannot create " + directory);
            }
        }
        for (File executable : new File[] {
                RuntimeLayout.nodeExecutable(context), RuntimeLayout.ripgrep(context), RuntimeLayout.bash(context) }) {
            if (executable != null && executable.isFile() && !executable.canExecute()) {
                executable.setExecutable(true, false);
            }
        }
        if (!RuntimeLayout.nodeExecutable(context).canExecute()) {
            throw new IOException("the Node runtime is not executable at " + RuntimeLayout.nodeExecutable(context));
        }
    }

    /** Starts the CLI's {@code web} profile and streams its output into the log. */
    private Process launch(int selectedPort) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(RuntimeLayout.nodeExecutable(context).getAbsolutePath());
        // The Cordis loader reaches Node's internal module loader directly when
        // internals are exposed, and only falls back to a native addon
        // otherwise. That addon publishes no Android build, so the fallback is
        // disabled by exposing internals here.
        command.add("--expose-internals");
        command.add(RuntimeLayout.cliEntry(context).getAbsolutePath());
        command.add("web");
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(String.valueOf(selectedPort));
        command.add("--no-open");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        // The Harness's directory picker offers its process home as "Home", and
        // a project created there is what the user expects to find in a file
        // manager. With shared storage granted the home is that shared folder,
        // so the picker starts where the projects belong; the Harness's own
        // state stays in DSH_HOME, which is private either way.
        File home = hasSharedStorage() ? workspace() : RuntimeLayout.home(context);
        env.put("HOME", home.getAbsolutePath());
        env.put("DSH_HOME", RuntimeLayout.dshHome(context).getAbsolutePath());
        env.put("TMPDIR", RuntimeLayout.tmp(context).getAbsolutePath());
        env.put("TMP", RuntimeLayout.tmp(context).getAbsolutePath());
        env.put("TEMP", RuntimeLayout.tmp(context).getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        // The runtime's shared libraries keep the names their SONAMEs require,
        // so the linker finds them through LD_LIBRARY_PATH (searched before the
        // executable's RUNPATH) rather than from a rewritten ELF.
        env.put("LD_LIBRARY_PATH", RuntimeLayout.libraries(context).getAbsolutePath());
        env.put("PATH", RuntimeLayout.bin(context).getAbsolutePath()
                + ":" + RuntimeLayout.nodeExecutable(context).getParentFile().getAbsolutePath()
                + ":/system/bin:/system/xbin");
        // The application sandbox is the boundary on Android, and the file-effect
        // policy stays available per session; the default states that split.
        env.put("DSH_PERMISSION_MODE", "danger-full-access");
        File bash = RuntimeLayout.bash(context);
        if (bash != null) env.put("DSH_ANDROID_SHELL", bash.getAbsolutePath());
        File ripgrep = RuntimeLayout.ripgrep(context);
        if (ripgrep.isFile()) env.put("DSH_RIPGREP_PATH", ripgrep.getAbsolutePath());

        appendLog("[runtime] " + String.join(" ", command));
        Process started = builder.start();
        Thread pump = new Thread(() -> pump(started.getInputStream()), "dsh-runtime-log");
        pump.setDaemon(true);
        pump.start();
        return started;
    }

    private void pump(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                captureUrl(line);
                appendLog(line);
            }
        } catch (IOException ignored) {
            // The stream ends when the process exits; nothing is left to drain.
        }
    }

    /**
     * Record the origin the runtime announces.
     *
     * The server authorizes requests with a token it generates per run and
     * prints in that URL, so the WebView must load exactly what was printed
     * rather than an origin this application guessed from the port number.
     * @param line - one line of runtime output.
     */
    private void captureUrl(String line) {
        Matcher match = LOOPBACK_URL.matcher(line);
        if (match.find()) announcedUrl = match.group();
    }

    /**
     * Wait until the runtime prints the origin it serves.
     *
     * Readiness is that line, not an answered request: the server refuses an
     * unauthenticated request with 401 — which is still an answer — so probing
     * the port can win the race against the printed URL and hand the WebView an
     * origin that carries no token. The printed URL is the only origin this
     * activation authorizes.
     * @param started - the runtime process, watched for early exit.
     * @returns whether the origin was announced before the deadline.
     */
    private boolean awaitReady(Process started) throws InterruptedException {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive()) return false;
            if (announcedUrl != null) return true;
            Thread.sleep(200);
        }
        return false;
    }

    /** A loopback port no other process holds. */
    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /** The directory the model works in, honouring the shared-storage choice. */
    public File workspace() {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String configured = preferences.getString(PREF_WORKSPACE, null);
        if (configured != null && configured.length() > 0) {
            File chosen = new File(configured);
            if (chosen.isDirectory() || chosen.mkdirs()) return chosen;
        }
        return new File(RuntimeLayout.root(context), "workspace");
    }

    /**
     * The workspace inside the application's own storage.
     *
     * It is where projects live before the user grants shared storage, and it
     * keeps whatever earlier sessions wrote afterwards — which is why moving to
     * shared storage copies from here rather than starting empty.
     */
    public File privateWorkspace() {
        return new File(RuntimeLayout.root(context), "workspace");
    }

    /** Records a new workspace root for sessions created afterwards. */
    public void setWorkspace(File directory) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_WORKSPACE, directory.getAbsolutePath()).apply();
    }

    /** Whether the runtime may write outside the application sandbox. */
    public boolean hasSharedStorage() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SHARED_STORAGE, false);
    }

    /** Records the outcome of the all-files-access request. */
    public void setSharedStorage(boolean granted) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_SHARED_STORAGE, granted).apply();
    }

    private void transition(State next, String why) {
        state = next;
        detail = why == null ? "" : why;
        for (Listener listener : listeners) listener.onRuntimeState(next, detail);
    }

    private void fail(String why) {
        Log.e(TAG, "runtime failed: " + why);
        transition(State.FAILED, why == null ? "unknown failure" : why);
    }

    private synchronized void appendLog(String line) {
        recentLog.addLast(line);
        while (recentLog.size() > LOG_RING) recentLog.removeFirst();
        try (OutputStream out = new FileOutputStream(RuntimeLayout.log(context), true)) {
            out.write((line + '\n').getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The log file is diagnostic; losing a line never stops the runtime.
        }
    }

    private String readStamp(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            return new String(bytes, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * Identity of the packaged runtime. The packaging step writes the archive's
     * digest beside it, so an installed tree is replaced exactly when the
     * payload it came from changes — a version number alone can move without it.
     */
    private String stampValue() {
        try (InputStream in = context.getAssets().open("runtime-stamp.txt")) {
            byte[] bytes = new byte[128];
            int read = in.read(bytes);
            return new String(bytes, 0, Math.max(read, 0), StandardCharsets.UTF_8).trim();
        } catch (IOException missingStamp) {
            return "0";
        }
    }

    private static boolean isInside(File root, File candidate) throws IOException {
        String rootPath = root.getCanonicalPath() + File.separator;
        return candidate.getCanonicalPath().startsWith(rootPath);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
