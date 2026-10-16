package com.dsh.android;

import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class RenewingLeaseTest {
    static class Resource implements RenewingLease.Resource {
        int acquired, released; long timeout; boolean fail;
        public void acquire(long millis) { if (fail) throw new IllegalStateException(); acquired++; timeout = millis; }
        public void release() { released++; }
    }
    @Test public void renewsWithTimeoutAndStopsWithoutLateReacquire() {
        Resource resource = new Resource(); ArrayList<Runnable> callbacks = new ArrayList<>();
        RenewingLease lease = new RenewingLease(resource, (task, delay) -> { callbacks.add(task); return () -> {}; }, () -> fail(), 300000, 60000);
        lease.start(); lease.start(); assertEquals(1, resource.acquired); assertEquals(300000, resource.timeout);
        callbacks.get(0).run(); assertEquals(2, resource.acquired);
        lease.stop(); callbacks.get(1).run(); assertEquals(2, resource.acquired); assertEquals(1, resource.released);
    }
    @Test public void restartRejectsOldGenerationCallback() {
        Resource resource = new Resource(); ArrayList<Runnable> callbacks = new ArrayList<>();
        RenewingLease lease = new RenewingLease(resource, (task, delay) -> { callbacks.add(task); return () -> {}; }, () -> fail(), 300000, 60000);
        lease.start(); lease.stop(); lease.start(); callbacks.get(0).run();
        assertEquals(2, resource.acquired); lease.stop();
    }
    @Test public void failedRenewalReleasesAndNotifiesOwner() {
        Resource resource = new Resource(); ArrayList<Runnable> callbacks = new ArrayList<>(); int[] failures = {0};
        RenewingLease lease = new RenewingLease(resource, (task, delay) -> { callbacks.add(task); return () -> {}; }, () -> failures[0]++, 300000, 60000);
        lease.start(); resource.fail = true; callbacks.get(0).run(); lease.stop();
        assertEquals(1, failures[0]); assertEquals(1, resource.released);
    }
}
