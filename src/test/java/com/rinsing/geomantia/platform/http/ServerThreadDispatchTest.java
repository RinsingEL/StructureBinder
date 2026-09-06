package com.rinsing.geomantia.platform.http;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerThreadDispatchTest {
    @Test void returnsValuesAndUnwrapsFailures() throws Exception {
        assertEquals(42, ServerThreadDispatch.call(Runnable::run, () -> true, () -> 42));
        assertThrows(IOException.class, () -> ServerThreadDispatch.call(Runnable::run, () -> true,
                () -> { throw new IOException("expected"); }));
    }
    @Test void stoppedServerDoesNotQueueWork() {
        assertThrows(CancellationException.class, () -> ServerThreadDispatch.call(
                task -> fail("must not enqueue"), () -> false, () -> 42));
    }
    @Test void shutdownReleasesWaiterAndPreventsLateQueuedMutation() throws Exception { checkCancellation(false); }
    @Test void interruptReleasesWaiterAndPreventsLateQueuedMutation() throws Exception { checkCancellation(true); }

    private void checkCancellation(boolean interrupt) throws Exception {
        AtomicBoolean running = new AtomicBoolean(true), mutated = new AtomicBoolean(), flag = new AtomicBoolean();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                ServerThreadDispatch.call(task -> { queued.set(task); submitted.countDown(); }, running::get,
                        () -> { mutated.set(true); return 42; });
            } catch (Throwable ex) { failure.set(ex); flag.set(Thread.currentThread().isInterrupted()); }
        });
        worker.start();
        try {
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            if (interrupt) worker.interrupt(); else running.set(false);
            worker.join(2000);
            assertFalse(worker.isAlive());
            if (interrupt) assertInstanceOf(InterruptedException.class, failure.get());
            else assertInstanceOf(CancellationException.class, failure.get());
            if (interrupt) assertTrue(flag.get());
            running.set(true); // Even a later server must not revive this cancelled action.
            queued.get().run();
            assertFalse(mutated.get());
        } finally { worker.interrupt(); worker.join(2000); }
    }
}
