package com.user.terra_script.runtime.task;

import java.util.concurrent.Callable;

public final class RuntimeTaskRunner {
    private RuntimeTaskRunner() {}

    public static RuntimeTaskResult runImmediate(Callable<RuntimeTaskResult> action) throws Exception {
        return action.call();
    }
}
