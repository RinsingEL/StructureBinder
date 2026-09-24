package com.rinsing.geomantia.harness.systems.provider.application;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Host-side probes and Harness requests share the OpenCode session affinity contract. */
final class ProviderRequestHeaders {
    private ProviderRequestHeaders() { }

    static String session(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? UUID.randomUUID().toString()
                : UUID.nameUUIDFromBytes(conversationId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static HttpRequest.Builder request(URI endpoint, String sessionId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("User-Agent", "Geomantia/0.1.0");
        if ("opencode.ai".equalsIgnoreCase(endpoint.getHost())) {
            if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Provider session is required");
            request.header("x-opencode-session", sessionId);
        }
        return request;
    }
}
