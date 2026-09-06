package com.rinsing.geomantia.platform.http;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Lifecycle-aware dispatch: a stopped server must not leave a worker waiting forever
 * or execute a queued world mutation after its requesting worker was cancelled. */
final class ServerThreadDispatch {
    private ServerThreadDispatch() { }

    static <T> T call(Executor executor, BooleanSupplier running, Callable<T> action) throws Exception {
        if (!running.getAsBoolean()) throw new CancellationException("CITY_SERVER_STOPPING");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("CITY_SERVER_TASK_INTERRUPTED");
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            if (future.isCancelled()) return;
            if (!running.getAsBoolean()) {
                future.completeExceptionally(new CancellationException("CITY_SERVER_STOPPING"));
                return;
            }
            try { future.complete(action.call()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        try {
            while (true) {
                if (!running.getAsBoolean()) {
                    future.cancel(false);
                    throw new CancellationException("CITY_SERVER_STOPPING");
                }
                try { return future.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException pending) { /* Poll lifecycle, not a fixed operation timeout. */ }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }
}
