package com.rinsing.geomantia.systems.provider.application;

/** A compact, player-safe summary of one visible Provider agent action. */
public record AgentActivityEvent(String occurredAt, String kind, String message) {
    public AgentActivityEvent {
        occurredAt = limit(occurredAt, 64);
        kind = limit(kind, 32);
        message = limit(message, 600);
    }

    private static String limit(String value, int maximum) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= maximum ? safe : safe.substring(0, maximum - 1) + "…";
    }
}
