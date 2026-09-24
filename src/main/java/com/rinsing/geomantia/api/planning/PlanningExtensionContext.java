package com.rinsing.geomantia.api.planning;

import com.google.gson.JsonObject;
import java.nio.file.Path;

/** Host-owned scope. JSON accessors return copies; inputRevision changes when source artifacts change. */
public record PlanningExtensionContext(String runId, String realmId, String citySeedId,
        Path runDirectory, String inputRevision, String taskRevision, JsonObject realmProfile, JsonObject citySeed,
        JsonObject blueprint) {
    public PlanningExtensionContext {
        runDirectory = runDirectory.toAbsolutePath().normalize();
        realmProfile = realmProfile.deepCopy(); citySeed = citySeed.deepCopy(); blueprint = blueprint.deepCopy();
    }
    @Override public JsonObject realmProfile() { return realmProfile.deepCopy(); }
    @Override public JsonObject citySeed() { return citySeed.deepCopy(); }
    @Override public JsonObject blueprint() { return blueprint.deepCopy(); }
}
