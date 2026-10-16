package com.dsh.android;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Serializes runtime ownership, cancellation, cleanup, and offline project maintenance. */
final class RuntimeController {
    enum State { IDLE, INSTALLING, STARTING, READY, STOPPING, FAILED, STOPPED }

    interface Backend {
        void install(Run run) throws Exception;
        void prepare(Run run) throws Exception;
        void acquire() throws Exception;
        Process launch(Run run) throws Exception;
        String readyUrl(Run run);
        void release();
    }

    interface Observer { void changed(State state, String detail); }

    final class Run {
        private volatile boolean cancelled;
        private Thread thread;
        private volatile Process process;
        volatile String announcedUrl;
        volatile boolean approvalReady;
        volatile int port;

        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }

        /** Register the child before log setup or any other post-spawn operation can fail. */
        void own(Process child) {
            process = child;
            check();
        }

        void installing() {
            synchronized (RuntimeController.this) {
                check();
                publish(State.INSTALLING, "unpacking the runtime");
            }
        }

        void starting() {
            synchronized (RuntimeController.this) {
                check();
                publish(State.STARTING, "");
            }
        }
    }

    private final Backend backend;
    private final Observer observer;
    private final long readyTimeoutMillis;
    private final long stopGraceMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dsh-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private Run active;
    private boolean maintenance;
    private Throwable cleanupProblem;
    private volatile State state = State.IDLE;
    private volatile String detail = "";
    private volatile String url;

    RuntimeController(Backend backend, Observer observer, long readyTimeoutMillis, long stopGraceMillis) {
        this.backend = backend;
        this.observer = observer;
        this.readyTimeoutMillis = readyTimeoutMillis;
        this.stopGraceMillis = stopGraceMillis;
    }

    State state() { return state; }
    String detail() { return detail; }
    String url() { return url; }
    synchronized boolean busy() { return active != null || maintenance; }
    synchronized int port() { return active == null || state != State.READY ? 0 : active.port; }

    synchronized boolean start() {
        if (active != null || maintenance) return false;
        if (cleanupProblem != null) {
            publish(State.FAILED, "runtime cleanup failed; restart the application: " + cleanupProblem.getMessage());
            return false;
        }
        Run run = new Run();
        active = run;
        publish(State.STARTING, "");
        worker.execute(() -> execute(run));
        return true;
    }

    /** Requests cancellation immediately; all waits and cleanup stay on the worker. */
    synchronized void stop() {
        if (active == null) return;
        if (active.cancelled) return;
        active.cancelled = true;
        url = null;
        publish(State.STOPPING, "");
        if (active.thread != null) active.thread.interrupt();
    }

    /** Prevents starts until cancellation, process exit, and the operation have settled. */
    synchronized <T> CompletableFuture<T> maintain(Callable<T> operation) {
        if (maintenance) throw new IllegalStateException("project maintenance is already running");
        maintenance = true;
        stop();
        CompletableFuture<T> result = new CompletableFuture<>();
        worker.execute(() -> {
            T value = null;
            Throwable failure = null;
            try {
                synchronized (RuntimeController.this) {
                    if (cleanupProblem != null) throw new IllegalStateException("runtime cleanup failed; project copy was refused", cleanupProblem);
                }
                value = operation.call();
            } catch (Throwable error) { failure = error; }
            synchronized (RuntimeController.this) { maintenance = false; }
            if (failure == null) result.complete(value);
            else result.completeExceptionally(failure);
        });
        return result;
    }

    private void execute(Run run) {
        Throwable failure = null;
        String stopped = "";
        synchronized (this) { run.thread = Thread.currentThread(); }
        try {
            run.check();
            backend.install(run);
            run.check();
            backend.prepare(run);
            run.check();
            run.starting();
            backend.acquire();
            run.check();
            // Cancellation between spawn and assignment is caught by the next check;
            // the finally block always owns the returned process.
            run.process = backend.launch(run);
            run.check();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMillis);
            String ready;
            while ((ready = backend.readyUrl(run)) == null) {
                run.check();
                if (!run.process.isAlive()) throw new IllegalStateException("runtime exited with code " + run.process.exitValue());
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Web UI startup timed out");
                Thread.sleep(50);
            }
            synchronized (this) {
                run.check();
                if (!run.process.isAlive()) throw new IllegalStateException("runtime exited before readiness");
                url = ready;
                publish(State.READY, "port " + run.port);
            }
            stopped = "runtime exited with code " + run.process.waitFor();
        } catch (CancellationException | InterruptedException cancelled) {
            // Stop owns the terminal state; interruption only wakes blocking work.
        } catch (Throwable error) {
            failure = error;
        } finally {
            synchronized (this) { run.thread = null; }
            Thread.interrupted();
            try {
                if (run.process != null && run.process.isAlive()) {
                    run.process.destroy();
                    if (!run.process.waitFor(stopGraceMillis, TimeUnit.MILLISECONDS)) {
                        run.process.destroyForcibly();
                        run.process.waitFor();
                    }
                }
            } catch (Throwable error) {
                synchronized (this) { cleanupProblem = error; }
                failure = error;
            }
            try { backend.release(); } catch (Throwable error) {
                synchronized (this) { cleanupProblem = error; }
                failure = error;
            }
            synchronized (this) {
                url = null;
                active = null;
                boolean failed = cleanupProblem != null || (failure != null && !run.cancelled);
                publish(failed ? State.FAILED : State.STOPPED,
                        cleanupProblem != null ? "runtime cleanup failed; restart the application: " + cleanupProblem.getMessage()
                                : failed ? String.valueOf(failure.getMessage()) : stopped);
            }
            Thread.interrupted();
        }
    }

    private void publish(State next, String why) {
        state = next;
        detail = why;
        try { observer.changed(next, why); } catch (RuntimeException ignored) {
            // Observers cannot break process cleanup or strand the next run.
        }
    }
}
