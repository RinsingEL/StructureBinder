package com.user.terra_script.world.city.stage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GroupStepStateUtil {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private GroupStepStateUtil() {}

    public static final class State {
        public String step;
        public int failure_count;
        public boolean blocked;
        public String blocked_reason = "";
    }

    public static State load(Path groupDir, String step) throws Exception {
        Path file = groupDir.resolve(step.toLowerCase() + "_state.json");
        if (!Files.exists(file)) {
            State state = new State();
            state.step = step;
            return state;
        }
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
    }

    public static Path save(Path groupDir, String step, State state) throws Exception {
        Files.createDirectories(groupDir);
        Path file = groupDir.resolve(step.toLowerCase() + "_state.json");
        Files.writeString(file, GSON.toJson(state), StandardCharsets.UTF_8);
        return file;
    }

    public static State update(Path groupDir, String step, boolean failed, String blockedReason) throws Exception {
        State state = load(groupDir, step);
        state.step = step;
        if (failed) {
            state.failure_count++;
            if (state.failure_count >= 3) {
                state.blocked = true;
                state.blocked_reason = blockedReason == null ? "validation_failed_three_times" : blockedReason;
            }
        } else {
            state.failure_count = 0;
            state.blocked = false;
            state.blocked_reason = "";
        }
        save(groupDir, step, state);
        return state;
    }
}
