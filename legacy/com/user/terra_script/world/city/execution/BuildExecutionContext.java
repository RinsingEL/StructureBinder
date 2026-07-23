package com.user.terra_script.world.city.execution;

import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;

public final class BuildExecutionContext {
    private final BuildWorldAccess world;
    private final CityC9BuildQueue.BuildQueue cityQueue;
    private final CityC9BuildQueue.BuildTask task;
    private final BuildTaskDebugLogger logger;
    private final Runnable persistCallback;

    public BuildExecutionContext(
            BuildWorldAccess world,
            CityC9BuildQueue.BuildQueue cityQueue,
            CityC9BuildQueue.BuildTask task,
            BuildTaskDebugLogger logger,
            Runnable persistCallback
    ) {
        this.world = world;
        this.cityQueue = cityQueue;
        this.task = task;
        this.logger = logger != null ? logger : BuildTaskDebugLogger.NO_OP;
        this.persistCallback = persistCallback;
    }

    public BuildWorldAccess world() {
        return world;
    }

    public CityC9BuildQueue.BuildQueue cityQueue() {
        return cityQueue;
    }

    public CityC9BuildQueue.BuildTask task() {
        return task;
    }

    public BuildTaskDebugLogger logger() {
        return logger;
    }

    public void persist() {
        if (persistCallback != null) {
            persistCallback.run();
        }
    }
}
