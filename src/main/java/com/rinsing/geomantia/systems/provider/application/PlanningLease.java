package com.rinsing.geomantia.systems.provider.application;

import java.time.Clock;
import java.util.UUID;

/** World-local ownership. Expiration never transfers a task while a request is executing. */
final class PlanningLease {
    private final Clock clock;
    private String owner = "", token = "";
    private long deadline;
    private int requests;
    PlanningLease() { this(Clock.systemUTC()); }
    PlanningLease(Clock clock) { this.clock = clock; }
    synchronized String acquire(String identity) {
        expire();
        if ((!owner.isEmpty() && !owner.equals(identity)) || (owner.isEmpty() && requests != 0))
            throw new IllegalStateException("PLANNING_BUSY");
        if (owner.isEmpty()) { owner = identity; token = UUID.randomUUID().toString(); }
        deadline = clock.millis() + 120_000;
        return token;
    }
    synchronized void touch(String credential) {
        require(credential);
        deadline = clock.millis() + 120_000;
    }
    synchronized void require(String credential) {
        expire();
        if (token.isEmpty() || !token.equals(credential)) throw new IllegalStateException("PLANNING_LEASE_EXPIRED");
    }
    synchronized AutoCloseable enter(String credential) {
        expire();
        if (!owner.isEmpty() && !token.equals(credential)) throw new IllegalStateException("PLANNING_BUSY");
        requests++;
        return () -> { synchronized (this) { requests--; deadline = clock.millis() + 120_000; } };
    }
    synchronized void release(String credential) {
        require(credential);
        if (requests != 0) throw new IllegalStateException("PLANNING_OPERATION_RUNNING");
        owner = token = "";
    }
    synchronized String owner() { expire(); return owner; }
    synchronized boolean owns(String credential) { expire(); return !token.isEmpty() && token.equals(credential); }
    private void expire() {
        if (requests == 0 && clock.millis() >= deadline) owner = token = "";
    }
}
