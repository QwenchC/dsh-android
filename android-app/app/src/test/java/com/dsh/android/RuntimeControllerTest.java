package com.dsh.android;

import org.junit.Test;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RuntimeControllerTest {
    static final class Child extends Process {
        volatile boolean alive = true;
        volatile boolean force;
        boolean ignoreTerm;
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public synchronized int waitFor() throws InterruptedException { while (alive) wait(); return 0; }
        public synchronized boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long until = System.nanoTime() + unit.toNanos(timeout);
            while (alive && System.nanoTime() < until) TimeUnit.NANOSECONDS.timedWait(this, until - System.nanoTime());
            return !alive;
        }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public synchronized void destroy() { if (!ignoreTerm) { alive = false; notifyAll(); } }
        public synchronized Process destroyForcibly() { force = true; alive = false; notifyAll(); return this; }
        public boolean isAlive() { return alive; }
    }

    static class Backend implements RuntimeController.Backend {
        final AtomicInteger launches = new AtomicInteger();
        final AtomicInteger held = new AtomicInteger();
        final Child child = new Child();
        final CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch installBlock;
        CountDownLatch launchBlock;
        boolean timeout;
        boolean failPrepare;
        public void install(RuntimeController.Run run) throws Exception {
            run.installing(); entered.countDown();
            if (installBlock != null) installBlock.await();
        }
        public void prepare(RuntimeController.Run run) { if (failPrepare) throw new IllegalStateException("prepare failed"); }
        public void acquire() { held.incrementAndGet(); }
        public Process launch(RuntimeController.Run run) throws Exception {
            launches.incrementAndGet();
            if (launchBlock != null) {
                // Simulate an uncancellable OS spawn returning after stop.
                while (true) { try { launchBlock.await(); break; } catch (InterruptedException ignored) {} }
            }
            return child;
        }
        public String readyUrl(RuntimeController.Run run) { return timeout ? null : "http://127.0.0.1:1234/?token=test"; }
        public void release() { held.set(0); }
    }

    private RuntimeController controller(Backend backend) {
        return new RuntimeController(backend, (state, detail) -> {}, 100, 25);
    }
    private void settled(RuntimeController controller) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (controller.busy() && System.nanoTime() < until) Thread.sleep(5);
        assertFalse("runtime must finish cleanup", controller.busy());
    }
    private void ready(RuntimeController controller) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (controller.state() != RuntimeController.State.READY && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(RuntimeController.State.READY, controller.state());
    }

    @Test public void repeatedStartOwnsOneProcess() throws Exception {
        Backend backend = new Backend(); RuntimeController runtime = controller(backend);
        assertTrue(runtime.start()); assertFalse(runtime.start()); ready(runtime);
        assertEquals(1, backend.launches.get()); runtime.stop(); settled(runtime);
        assertFalse(backend.child.isAlive()); assertEquals(0, backend.held.get());
        assertNull(runtime.url()); assertEquals(0, runtime.port());
    }

    @Test public void stopDuringInstallCannotLaunchLater() throws Exception {
        Backend backend = new Backend(); backend.installBlock = new CountDownLatch(1);
        RuntimeController runtime = controller(backend); runtime.start();
        assertTrue(backend.entered.await(2, TimeUnit.SECONDS));
        runtime.stop(); backend.installBlock.countDown(); settled(runtime);
        assertEquals(0, backend.launches.get()); assertEquals(RuntimeController.State.STOPPED, runtime.state());
    }

    @Test public void stopDuringSpawnStillOwnsReturnedChild() throws Exception {
        Backend backend = new Backend(); backend.launchBlock = new CountDownLatch(1);
        RuntimeController runtime = controller(backend); runtime.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (backend.launches.get() == 0 && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(1, backend.launches.get()); runtime.stop(); backend.launchBlock.countDown(); settled(runtime);
        assertFalse(backend.child.isAlive()); assertEquals(0, backend.held.get()); assertNull(runtime.url());
    }

    @Test public void timeoutKillsChildAndReleasesWakeResource() throws Exception {
        Backend backend = new Backend(); backend.timeout = true;
        RuntimeController runtime = controller(backend); runtime.start(); settled(runtime);
        assertEquals(RuntimeController.State.FAILED, runtime.state());
        assertTrue(runtime.detail().contains("timed out")); assertFalse(backend.child.isAlive()); assertEquals(0, backend.held.get());
    }

    @Test public void forceKillDoesNotBlockStopCaller() throws Exception {
        Backend backend = new Backend(); backend.child.ignoreTerm = true;
        RuntimeController runtime = controller(backend); runtime.start(); ready(runtime);
        long before = System.nanoTime(); runtime.stop(); runtime.stop();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 100);
        settled(runtime); assertTrue(backend.child.force); assertEquals(0, backend.held.get());
    }

    @Test public void prepareFailureIsCleanAndCanRetry() throws Exception {
        Backend backend = new Backend(); backend.failPrepare = true;
        RuntimeController runtime = controller(backend); runtime.start(); settled(runtime);
        assertEquals(RuntimeController.State.FAILED, runtime.state()); assertEquals(0, backend.launches.get());
        backend.failPrepare = false; assertTrue(runtime.start()); ready(runtime); runtime.stop(); settled(runtime);
    }

    @Test public void throwingObserverCannotStrandProcess() throws Exception {
        Backend backend = new Backend();
        RuntimeController runtime = new RuntimeController(backend, (s, d) -> { throw new IllegalStateException(); }, 100, 25);
        runtime.start(); ready(runtime); runtime.stop(); settled(runtime); assertFalse(backend.child.isAlive());
    }

    @Test public void maintenanceWaitsForExitAndPreventsRestart() throws Exception {
        Backend backend = new Backend(); RuntimeController runtime = controller(backend);
        runtime.start(); ready(runtime);
        CountDownLatch copying = new CountDownLatch(1), finish = new CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Boolean> result = runtime.maintain(() -> {
            assertFalse(backend.child.isAlive()); assertEquals(0, backend.held.get());
            copying.countDown(); finish.await(); return true;
        });
        assertTrue(copying.await(2, TimeUnit.SECONDS)); assertFalse(runtime.start());
        finish.countDown(); assertTrue(result.get(2, TimeUnit.SECONDS)); settled(runtime);
    }

    @Test public void maintenanceFailureReleasesAdmission() throws Exception {
        Backend backend = new Backend(); RuntimeController runtime = controller(backend);
        try { runtime.maintain(() -> { throw new java.io.IOException("disk full"); }).get(2, TimeUnit.SECONDS); fail(); }
        catch (java.util.concurrent.ExecutionException expected) { assertEquals("disk full", expected.getCause().getMessage()); }
        assertTrue(runtime.start()); ready(runtime); runtime.stop(); settled(runtime);
    }

    @Test public void earlyExitCannotReportReady() throws Exception {
        Backend backend = new Backend(); backend.child.destroy();
        RuntimeController runtime = controller(backend); runtime.start(); settled(runtime);
        assertEquals(RuntimeController.State.FAILED, runtime.state()); assertNull(runtime.url()); assertEquals(0, backend.held.get());
    }
    @Test public void logSetupFailureAfterSpawnStillCleansChild() throws Exception {
        Backend backend = new Backend() {
            @Override public Process launch(RuntimeController.Run run) {
                run.own(child);
                throw new IllegalStateException("log setup failed");
            }
        };
        RuntimeController runtime = controller(backend); runtime.start(); settled(runtime);
        assertEquals(RuntimeController.State.FAILED, runtime.state());
        assertFalse(backend.child.isAlive()); assertEquals(0, backend.held.get());
    }
    @Test public void cleanupFailureRefusesMigrationAndRestart() throws Exception {
        Backend backend = new Backend() {
            @Override public void release() { super.release(); throw new IllegalStateException("release failed"); }
        };
        RuntimeController runtime = controller(backend); runtime.start(); ready(runtime); runtime.stop(); settled(runtime);
        assertEquals(RuntimeController.State.FAILED, runtime.state()); assertFalse(runtime.start());
        AtomicInteger copies = new AtomicInteger();
        try { runtime.maintain(() -> copies.incrementAndGet()).get(2, TimeUnit.SECONDS); fail(); }
        catch (java.util.concurrent.ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("copy was refused")); }
        assertEquals(0, copies.get());
    }
}
