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

    /** Lines of process output the log view keeps in memory. */
    private static final int LOG_RING = 400;

    /** How long the Web UI may take to answer before startup is called failed. */
    private static final long READY_TIMEOUT_MS = 180_000L;

    /** Lifecycle of the managed process. */
    public enum State { IDLE, INSTALLING, STARTING, READY, STOPPING, FAILED, STOPPED }

    /** Receives transitions on the calling thread; UI consumers post to the main thread. */
    public interface Listener {
        /** @param state the new state; {@code detail} explains a failure. */
        void onRuntimeState(State state, String detail);
    }

    private final Context context;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> recentLog = new ArrayDeque<>();

    private RenewingLease wakeLease;
    private final java.util.concurrent.ScheduledExecutorService wakeTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "dsh-wake-lease"); thread.setDaemon(true); return thread;
    });
    private InputStream outputStream;
    private Thread outputPump;
    private volatile File runningWorkspace;
    private final RuntimeController controller;

    DshRuntime(Context context) {
        this.context = context.getApplicationContext();
        controller = new RuntimeController(new RuntimeController.Backend() {
            public void install(RuntimeController.Run run) throws Exception { installRuntime(run); }
            public void prepare(RuntimeController.Run run) throws Exception {
                prepareDirectories();
                installPolicy();
                runningWorkspace = workspace();
            }
            public void acquire() { acquireWakeLock(); }
            public Process launch(RuntimeController.Run run) throws Exception {
                run.port = freePort();
                return DshRuntime.this.launch(run);
            }
            public String readyUrl(RuntimeController.Run run) { return run.approvalReady ? run.announcedUrl : null; }
            public void release() {
                try { closeOutput(); } finally { releaseWakeLock(); runningWorkspace = null; }
            }
        }, (next, why) -> transition(State.valueOf(next.name()), why), READY_TIMEOUT_MS, 5000);
    }

    /** The current state. */
    public State state() {
        return State.valueOf(controller.state().name());
    }

    /** The explanation attached to the current state; empty when there is none. */
    public String detail() {
        return controller.detail();
    }

    /** The loopback origin the runtime prints, token included. */
    private static final java.util.regex.Pattern LOOPBACK_URL =
            java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+");

    /** The Web UI origin once {@link State#READY}, otherwise null. */
    public String baseUrl() {
        return controller.url();
    }

    /** The port the Web UI is served on once {@link State#READY}, otherwise 0. */
    public int port() {
        return controller.port();
    }

    /** Registers a listener and immediately reports the current state to it. */
    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        notifyListener(listener, state(), detail());
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

    /** Starts one run; repeated calls while starting, stopping, or copying are ignored. */
    public void start() { controller.start(); }

    /** Cancels startup or execution without blocking the caller. */
    public void stop() { controller.stop(); }

    public boolean isRunning() { return controller.busy(); }

    public boolean autoStart() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_start", true);
    }

    public void setAutoStart(boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_start", enabled).apply();
    }

    public boolean autonomous() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autonomous", false);
    }

    /** The changed authority applies on the next start. */
    public void setAutonomous(boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autonomous", enabled).apply();
    }

    public File servedWorkspace() {
        File active = runningWorkspace;
        return active == null ? workspace() : active;
    }

    /** Stop writers before copying; switch roots only after every file passes verification. */
    java.util.concurrent.CompletableFuture<ProjectMigration.Result> migrate(File destination) {
        setAutoStart(false);
        return controller.maintain(() -> {
            if (!hasSharedStorage()) throw new IOException("shared-storage permission is unavailable");
            ProjectMigration.Result result = ProjectMigration.copy(privateWorkspace(), RuntimeLayout.home(context), destination);
            if (result.complete()) {
                boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(PREF_WORKSPACE, destination.getAbsolutePath()).commit();
                if (!saved) result.failures.add("could not save the new workspace");
            }
            return result;
        });
    }

    /** A five-minute CPU lease renews each minute while this runtime owns it. */
    private void acquireWakeLock() {
        android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) throw new IllegalStateException("power service is unavailable");
        android.os.PowerManager.WakeLock lock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dsh:harness");
        lock.setReferenceCounted(false);
        wakeLease = new RenewingLease(new RenewingLease.Resource() {
            public void acquire(long timeoutMillis) { lock.acquire(timeoutMillis); }
            public void release() { if (lock.isHeld()) lock.release(); }
        }, (task, delay) -> {
            java.util.concurrent.ScheduledFuture<?> future = wakeTimer.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, () -> {
            appendLog("[runtime] CPU lease renewal failed; stopping the harness");
            stop();
        }, 300_000, 60_000);
        wakeLease.start();
        appendLog("[runtime] holding a renewable, bounded CPU lease while the harness runs");
    }

    private void releaseWakeLock() {
        RenewingLease lease = wakeLease;
        if (lease != null) { lease.stop(); wakeLease = null; }
    }

    /** Extracts the packaged runtime tree when the installed stamp is stale. */
    private void installRuntime(RuntimeController.Run run) throws IOException {
        String stamp = stampValue();
        File root = RuntimeLayout.root(context);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
        File live = RuntimeLayout.runtime(context);
        File previous = new File(root, "runtime.previous");
        if (!live.exists() && previous.exists() && !previous.renameTo(live)) throw new IOException("cannot restore previous runtime");
        File installed = new File(live, RuntimeLayout.STAMP_FILE);
        if (RuntimeLayout.cliEntry(context).isFile() && stamp.equals(readStamp(installed))) return;

        run.installing();
        File runtime = new File(root, "runtime.installing");
        deleteTree(runtime);
        if (!runtime.mkdirs() && !runtime.isDirectory()) throw new IOException("cannot create " + runtime);

        long entries = 0;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open(RuntimeLayout.RUNTIME_ASSET))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                run.check();
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
                        while ((read = zip.read(buffer)) > 0) { run.check(); out.write(buffer, 0, read); }
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
        run.check();
        try (OutputStream out = new FileOutputStream(new File(runtime, RuntimeLayout.STAMP_FILE))) {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
        }
        if (!new File(runtime, "lib/bin.js").isFile()) throw new IOException("runtime CLI is missing");
        run.check();
        deleteTree(previous);
        if (live.exists() && !live.renameTo(previous)) throw new IOException("cannot retain previous runtime");
        if (!runtime.renameTo(live)) {
            if (previous.exists()) previous.renameTo(live);
            throw new IOException("cannot activate runtime");
        }
        deleteTree(previous);
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
    private Process launch(RuntimeController.Run run) throws IOException {
        int selectedPort = run.port;
        List<String> command = new ArrayList<>();
        command.add(RuntimeLayout.nodeExecutable(context).getAbsolutePath());
        // The Cordis loader reaches Node's internal module loader directly when
        // internals are exposed, and only falls back to a native addon
        // otherwise. That addon publishes no Android build, so the fallback is
        // disabled by exposing internals here.
        command.add("--expose-internals");
        command.add(RuntimeLayout.cliEntry(context).getAbsolutePath());
        command.add("--profile");
        command.add("web");
        command.add("--patch");
        command.add(new File(RuntimeLayout.root(context), "android-policy.patch.yml").getAbsolutePath());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(String.valueOf(selectedPort));
        command.add("--no-open");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(servedWorkspace());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        // The Harness's directory picker offers its process home as "Home", and
        // a project created there is what the user expects to find in a file
        // manager. With shared storage granted the home is that shared folder,
        // so the picker starts where the projects belong; the Harness's own
        // state stays in DSH_HOME, which is private either way.
        File home = hasSharedStorage() ? servedWorkspace() : RuntimeLayout.home(context);
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
        // File tools use workspace-write; command admission is a separate gate.
        env.put("DSH_PERMISSION_MODE", "workspace-write");
        env.put("DSH_ANDROID_AUTONOMOUS", autonomous() ? "1" : "0");
        File bash = RuntimeLayout.bash(context);
        if (bash != null) env.put("DSH_ANDROID_SHELL", bash.getAbsolutePath());
        File ripgrep = RuntimeLayout.ripgrep(context);
        if (ripgrep.isFile()) env.put("DSH_RIPGREP_PATH", ripgrep.getAbsolutePath());

        appendLog("[runtime] " + String.join(" ", command));
        Process started = builder.start();
        run.own(started);
        InputStream stream = started.getInputStream();
        outputStream = stream;
        Thread pump = new Thread(() -> pump(run, stream), "dsh-runtime-log");
        outputPump = pump;
        pump.setDaemon(true);
        pump.start();
        return started;
    }

    /** Drain the exited process, then close a pipe held open by an unmanaged descendant. */
    private void closeOutput() {
        Thread pump = outputPump;
        InputStream stream = outputStream;
        outputPump = null;
        outputStream = null;
        try {
            if (pump != null) pump.join(500);
            if (stream != null) stream.close();
            if (pump != null && pump.isAlive()) pump.join(500);
        } catch (IOException closed) {
            Log.w(TAG, "runtime output could not be closed", closed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void pump(RuntimeController.Run run, InputStream stream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                captureUrl(run, line);
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
    private void captureUrl(RuntimeController.Run run, String line) {
        if ("[android-policy] active".equals(line)) run.approvalReady = true;
        Matcher match = LOOPBACK_URL.matcher(line);
        if (match.find() && TrustedOrigin.allows("http://127.0.0.1:" + run.port, match.group())) run.announcedUrl = match.group();
    }

    private void installPolicy() throws IOException {
        for (String name : new String[] { "android-policy.mjs", "android-policy.patch.yml" }) {
            try (InputStream input = context.getAssets().open(name)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096]; int count;
                while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
                byte[] content = bytes.toByteArray();
                if (name.endsWith(".yml")) {
                    String template = new String(content, StandardCharsets.UTF_8);
                    String module = new File(RuntimeLayout.root(context), "android-policy.mjs").toURI().toASCIIString();
                    content = PolicyConfig.materialize(template, module).getBytes(StandardCharsets.UTF_8);
                }
                java.nio.file.Files.write(new File(RuntimeLayout.root(context), name).toPath(), content);
            }
        }
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
            return new File(configured);
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

    /** Read the OS grant each time; a revoked permission cannot survive in preferences. */
    public boolean hasSharedStorage() {
        return android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager();
    }

    private void transition(State next, String why) {
        for (Listener listener : listeners) notifyListener(listener, next, why);
    }

    private void notifyListener(Listener listener, State state, String detail) {
        try { listener.onRuntimeState(state, detail); }
        catch (RuntimeException failure) { Log.w(TAG, "runtime listener failed", failure); }
    }

    private synchronized void appendLog(String line) {
        line = line.replaceAll("([?&]token=)[A-Za-z0-9_-]+", "$1[redacted]");
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
        if (file == null || !java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) { file.delete(); return; }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
