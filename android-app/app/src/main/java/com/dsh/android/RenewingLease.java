package com.dsh.android;

/** Renews a bounded resource lease while owned; stale callbacks cannot reacquire after stop. */
final class RenewingLease {
    interface Resource { void acquire(long timeoutMillis); void release(); }
    interface Timer { Runnable schedule(Runnable task, long delayMillis); }
    private final Resource resource;
    private final Timer timer;
    private final Runnable failed;
    private final long timeoutMillis;
    private final long renewMillis;
    private long generation;
    private boolean active;
    private Runnable cancel;

    RenewingLease(Resource resource, Timer timer, Runnable failed, long timeoutMillis, long renewMillis) {
        if (renewMillis <= 0 || timeoutMillis <= renewMillis) throw new IllegalArgumentException("renewal must precede expiry");
        this.resource = resource; this.timer = timer; this.failed = failed;
        this.timeoutMillis = timeoutMillis; this.renewMillis = renewMillis;
    }
    synchronized void start() {
        if (active) return;
        long owned = ++generation;
        resource.acquire(timeoutMillis);
        active = true;
        try { cancel = timer.schedule(() -> renew(owned), renewMillis); }
        catch (RuntimeException failure) { active = false; resource.release(); throw failure; }
    }
    synchronized void stop() {
        ++generation;
        boolean release = active;
        active = false;
        if (cancel != null) { cancel.run(); cancel = null; }
        if (release) resource.release();
    }
    private void renew(long owned) {
        boolean failure = false;
        synchronized (this) {
            if (!active || generation != owned) return;
            try {
                resource.acquire(timeoutMillis);
                cancel = timer.schedule(() -> renew(owned), renewMillis);
            } catch (RuntimeException error) {
                active = false; ++generation;
                try { resource.release(); } catch (RuntimeException ignored) {
                    // The bounded lease will still expire; the owner must stop the run.
                }
                failure = true;
            }
        }
        if (failure) failed.run();
    }
}
