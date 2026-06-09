package com.user.terra_script.world.city.execution;

import com.google.gson.JsonObject;

public interface BuildTaskDebugLogger {
    BuildTaskDebugLogger NO_OP = new BuildTaskDebugLogger() {
        @Override
        public void progress(String message, JsonObject details) {
        }

        @Override
        public void completed(String message, JsonObject details) {
        }

        @Override
        public void failed(String message, JsonObject details) {
        }
    };

    void progress(String message, JsonObject details);

    void completed(String message, JsonObject details);

    void failed(String message, JsonObject details);
}
