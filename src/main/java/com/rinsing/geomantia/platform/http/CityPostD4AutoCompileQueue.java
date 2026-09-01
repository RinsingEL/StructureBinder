package com.rinsing.geomantia.platform.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Runs the program-owned half of City planning after an accepted D4 Blueprint revision. */
final class CityPostD4AutoCompileQueue implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SCHEMA = "city_post_d4_auto_compile_job.v0.1";

    private final Path debugRoot;
    private final WorkflowRunner runner;
    private final StateListener stateListener;
    private final ExecutorService executor;
    private final Map<JobKey, Boolean> active = new ConcurrentHashMap<>();

    CityPostD4AutoCompileQueue(Path debugRoot, WorkflowRunner runner) {
        this(debugRoot, runner, state -> { });
    }

    CityPostD4AutoCompileQueue(Path debugRoot, WorkflowRunner runner, StateListener stateListener) {
        this.debugRoot = Objects.requireNonNull(debugRoot, "debugRoot").toAbsolutePath().normalize();
        this.runner = Objects.requireNonNull(runner, "runner");
        this.stateListener = Objects.requireNonNull(stateListener, "stateListener");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Geomantia-City-Post-D4");
            thread.setDaemon(true);
            return thread;
        });
        recoverIncompleteJobs();
    }

    JsonObject enqueue(String runId, String citySeedId) throws IOException {
        JobKey key = JobKey.of(runId, citySeedId);
        JsonObject existing = read(key);
        if (active.putIfAbsent(key, Boolean.TRUE) != null) {
            return existing == null ? state(key, "queued", "ALREADY_QUEUED", 0) : existing;
        }
        int attempt = existing == null ? 1 : intValue(existing, "attempt", 0) + 1;
        JsonObject queued = state(key, "queued", "D4_ACCEPTED", attempt);
        if (existing != null && existing.has("createdAt")) {
            queued.add("createdAt", existing.get("createdAt").deepCopy());
        }
        write(key, queued);
        submit(key, attempt);
        return queued.deepCopy();
    }

    JsonObject status(String runId, String citySeedId) throws IOException {
        JobKey key = JobKey.of(runId, citySeedId);
        JsonObject state = read(key);
        if (state == null) {
            state = state(key, "not_queued", "D4_AUTO_COMPILE_JOB_NOT_FOUND", 0);
        }
        state.addProperty("active", active.containsKey(key));
        state.addProperty("statePath", debugRoot.relativize(path(key)).toString().replace('\\', '/'));
        return state;
    }

    private void submit(JobKey key, int attempt) throws IOException {
        try {
            executor.execute(() -> run(key, attempt));
        } catch (RejectedExecutionException ex) {
            active.remove(key);
            JsonObject failed = state(key, "needs_agent", "QUEUE_STOPPED", attempt);
            failed.addProperty("error", ex.getMessage());
            write(key, failed);
            throw ex;
        }
    }

    private void run(JobKey key, int attempt) {
        try {
            write(key, state(key, "running", "POST_D4_WORKFLOW_RUNNING", attempt));
            JsonObject response = runner.run(key.runId, key.citySeedId);
            String workflowStatus = stringValue(response, "status", "");
            boolean ready = "waiting_for_generation".equals(workflowStatus);
            JsonObject finished = state(key, ready ? "waiting_for_generation" : "needs_agent",
                    ready ? "WAITING_FOR_GENERATION" : "POST_D4_WORKFLOW_UNEXPECTED_STATUS", attempt);
            finished.addProperty("workflowStatus", workflowStatus);
            finished.addProperty("ok", booleanValue(response, "ok", false));
            if (response.has("artifacts")) {
                finished.add("artifacts", response.get("artifacts").deepCopy());
            }
            if (!ready) {
                finished.add("workflowResponse", response.deepCopy());
            }
            write(key, finished);
        } catch (Exception ex) {
            LOGGER.error("Post-D4 auto compile failed for {}/{}", key.runId, key.citySeedId, ex);
            JsonObject failed = state(key, "needs_agent", "POST_D4_WORKFLOW_FAILED", attempt);
            failed.addProperty("errorType", ex.getClass().getName());
            failed.addProperty("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            try {
                write(key, failed);
            } catch (IOException writeFailure) {
                LOGGER.error("Could not persist failed post-D4 job for {}/{}", key.runId, key.citySeedId,
                        writeFailure);
            }
        } finally {
            active.remove(key);
        }
    }

    private void recoverIncompleteJobs() {
        if (!Files.isDirectory(debugRoot)) return;
        try (var runs = Files.list(debugRoot)) {
            runs.filter(Files::isDirectory).forEach(runDir -> {
                Path queueDir = runDir.resolve("automation").resolve("post_d4");
                if (!Files.isDirectory(queueDir)) return;
                try (var jobs = Files.list(queueDir)) {
                    jobs.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                        try {
                            JsonObject state = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                            String status = stringValue(state, "status", "");
                            if (!"queued".equals(status) && !"running".equals(status)) return;
                            JobKey key = JobKey.of(stringValue(state, "runId", ""),
                                    stringValue(state, "citySeedId", ""));
                            if (active.putIfAbsent(key, Boolean.TRUE) == null) {
                                int attempt = intValue(state, "attempt", 0) + 1;
                                JsonObject recovered = state(key, "queued", "RECOVERED_AFTER_RESTART", attempt);
                                if (state.has("createdAt")) recovered.add("createdAt", state.get("createdAt").deepCopy());
                                write(key, recovered);
                                submit(key, attempt);
                            }
                        } catch (Exception ex) {
                            LOGGER.warn("Could not recover post-D4 job {}: {}", path, ex.getMessage());
                        }
                    });
                } catch (IOException ex) {
                    LOGGER.warn("Could not inspect post-D4 queue {}: {}", queueDir, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            LOGGER.warn("Could not inspect post-D4 jobs under {}: {}", debugRoot, ex.getMessage());
        }
    }

    private JsonObject read(JobKey key) throws IOException {
        Path path = path(key);
        return Files.isRegularFile(path)
                ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : null;
    }

    private void write(JobKey key, JsonObject state) throws IOException {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, state.toString());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                stateListener.onState(state.deepCopy());
            } catch (RuntimeException listenerFailure) {
                LOGGER.warn("Post-D4 state listener failed for {}/{}: {}",
                        key.runId, key.citySeedId, listenerFailure.getMessage());
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path path(JobKey key) {
        Path runDirectory = debugRoot.resolve(key.runId).normalize();
        if (!runDirectory.startsWith(debugRoot)) {
            throw new IllegalArgumentException("POST_D4_QUEUE_RUN_OUTSIDE_DEBUG_ROOT");
        }
        return runDirectory.resolve("automation").resolve("post_d4")
                .resolve(safe(key.citySeedId) + ".json");
    }

    private static JsonObject state(JobKey key, String status, String reasonCode, int attempt) {
        JsonObject state = new JsonObject();
        state.addProperty("schema", SCHEMA);
        state.addProperty("runId", key.runId);
        state.addProperty("citySeedId", key.citySeedId);
        state.addProperty("status", status);
        state.addProperty("reasonCode", reasonCode);
        state.addProperty("attempt", attempt);
        state.addProperty("updatedAt", Instant.now().toString());
        state.addProperty("createdAt", Instant.now().toString());
        return state;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsInt() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsBoolean() : fallback;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface WorkflowRunner {
        JsonObject run(String runId, String citySeedId) throws Exception;
    }

    @FunctionalInterface
    interface StateListener {
        void onState(JsonObject state);
    }

    private record JobKey(String runId, String citySeedId) {
        static JobKey of(String runId, String citySeedId) {
            if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is required");
            if (citySeedId == null || citySeedId.isBlank()) {
                throw new IllegalArgumentException("citySeedId is required");
            }
            return new JobKey(runId.trim(), citySeedId.trim());
        }
    }
}
