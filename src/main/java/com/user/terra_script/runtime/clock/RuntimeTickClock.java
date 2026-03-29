package com.user.terra_script.runtime.clock;

import com.user.terra_script.event.ServerTickTracker;

public final class RuntimeTickClock {
    private RuntimeTickClock() {}

    public static long currentTick() {
        return ServerTickTracker.currentTick();
    }
}
