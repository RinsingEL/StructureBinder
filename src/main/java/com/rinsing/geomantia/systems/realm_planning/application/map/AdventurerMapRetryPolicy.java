package com.rinsing.geomantia.systems.realm_planning.application.map;

/** Shared UI eligibility and authoritative server-side stale-request guard. */
public final class AdventurerMapRetryPolicy {
    private AdventurerMapRetryPolicy() {}

    public static boolean available(String runId, String cityId, String status) {
        return runId != null && !runId.isBlank() && cityId != null && !cityId.isBlank()
                && "blocked_by_program".equals(status);
    }

    public static boolean matches(String requestedCity, String currentCity, String status, String nextAction) {
        return requestedCity != null && !requestedCity.isBlank() && requestedCity.equals(currentCity)
                && "blocked_by_program".equals(status) && "city_post_d4_auto_compile_retry".equals(nextAction);
    }
}
