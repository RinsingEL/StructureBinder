package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;
import com.rinsing.geomantia.harness.systems.provider.application.*;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HarnessProcessShutdownTest {
    @Test void waitsForForcedTerminationBeforeReturning() {
        var process = new DelayedProcess(false);
        HarnessAgentClient.stopProcess(process);
        assertTrue(process.forced);
        assertFalse(process.isAlive());
        assertEquals(2, process.waits);
    }

    @Test void interruptionStillReapsProcessAndRestoresInterruptFlag() {
        var process = new DelayedProcess(true);
        try {
            HarnessAgentClient.stopProcess(process);
            assertTrue(process.forced);
            assertFalse(process.isAlive());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static final class DelayedProcess extends Process {
        private final boolean interruptFirstWait;
        private boolean alive = true, forced;
        private int waits;
        DelayedProcess(boolean interruptFirstWait) { this.interruptFirstWait = interruptFirstWait; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waits++;
            if (waits == 1 && interruptFirstWait) throw new InterruptedException();
            if (forced) alive = false;
            return !alive;
        }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { forced = true; return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public int waitFor() { throw new UnsupportedOperationException(); }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    }
}
