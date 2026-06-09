package com.user.terra_script.runtime.task;

import com.google.gson.JsonObject;

public final class RuntimeTaskResult {
    public final JsonObject payload;

    public RuntimeTaskResult(JsonObject payload) {
        this.payload = payload == null ? new JsonObject() : payload.deepCopy();
    }
}
