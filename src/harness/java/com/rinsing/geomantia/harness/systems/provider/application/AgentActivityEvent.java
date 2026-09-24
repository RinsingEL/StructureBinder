package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;

/** A compact, player-safe summary of one visible Provider agent action. */
public record AgentActivityEvent(String occurredAt, String kind, String message) {
    public AgentActivityEvent {
        occurredAt = limit(occurredAt, 64);
        kind = limit(kind, 32);
        message = message == null ? "" : message;
        if (message.length() > 600) message = message.substring(0, 599) + "…";
    }

    public static void emitText(java.util.function.Consumer<AgentActivityEvent> listener, String kind, String text) {
        if (listener == null || text == null || text.isEmpty()) return;
        String time = java.time.Instant.now().toString();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 600);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            listener.accept(new AgentActivityEvent(time, kind, text.substring(start, end)));
            start = end;
        }
    }

    private static String limit(String value, int maximum) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= maximum ? safe : safe.substring(0, maximum - 1) + "…";
    }
}
