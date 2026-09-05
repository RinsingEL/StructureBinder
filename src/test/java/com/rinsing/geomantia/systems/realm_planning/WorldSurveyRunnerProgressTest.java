package com.rinsing.geomantia.systems.realm_planning;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.gis.application.sample.SampledCell;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.domain.cell.AtlasCell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class WorldSurveyRunnerProgressTest {
    @TempDir
    Path tempDir;

    @Test
    void ownerInterruptStopsBeforeParallelMicroSamplingStarts() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config("owner_interrupt", testCase.dimensionId(),
                "synthetic", 0, 0, 0, 512, 128, 32, 8, testCase.sampleMode(), WorldSurveyRunner.ResumePolicy.RESCAN);
        List<WorldSurveyRunner.ProgressUpdate> updates = new ArrayList<>();
        try {
            assertThrows(CancellationException.class,
                    () -> new WorldSurveyRunner(tempDir, GisClassifierConfig.defaults()).run(config,
                            new SyntheticAtlasSampler(testCase.profile()), update -> {
                                updates.add(update);
                                if ("micro_sampling".equals(update.phase())) Thread.currentThread().interrupt();
                            }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("cancelled", updates.get(updates.size() - 1).status());
            assertFalse(java.nio.file.Files.exists(tempDir.resolve("owner_interrupt/world_survey_manifest.json")));
        } finally {
            Thread.interrupted(); // Do not leak interrupt state into other JUnit tests.
        }
    }

    @Test
    void worldCloseCancelsParallelMicroSamplingAndDoesNotSeal() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger samples = new AtomicInteger();
        CountDownLatch microStarted = new CountDownLatch(1);
        SyntheticAtlasSampler delegate = new SyntheticAtlasSampler(testCase.profile());
        AtlasSampler sampler = new AtlasSampler() {
            @Override public SampledCell sample(AtlasCell cell, SampleMode mode) { return delegate.sample(cell, mode); }
            @Override public SampledCell sampleFeature(AtlasCell cell, SampleMode mode) {
                samples.incrementAndGet();
                microStarted.countDown();
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                return delegate.sampleFeature(cell, mode);
            }
            @Override public double sampleElevation(AtlasCell cell, SampleMode mode) {
                return delegate.sampleElevation(cell, mode);
            }
        };
        List<WorldSurveyRunner.ProgressUpdate> updates = new CopyOnWriteArrayList<>();
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config("cancel_parallel", testCase.dimensionId(),
                "synthetic", 0, 0, 0, 512, 128, 32, 8, testCase.sampleMode(), WorldSurveyRunner.ResumePolicy.RESCAN);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = executor.submit(() -> new WorldSurveyRunner(tempDir, GisClassifierConfig.defaults())
                    .run(config, sampler, updates::add, closed::get));
            assertTrue(microStarted.await(10, TimeUnit.SECONDS));
            closed.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException, failure.toString());
            Thread.sleep(100); // Allow samples already executing at cancellation to return.
            int stopped = samples.get();
            Thread.sleep(100);
            assertEquals(stopped, samples.get(), "Common-pool workers must also stop");
            assertEquals("cancelled", updates.get(updates.size() - 1).status());
            assertFalse(java.nio.file.Files.exists(tempDir.resolve("cancel_parallel/world_survey_manifest.json")));
        } finally {
            closed.set(true);
            executor.shutdownNow();
        }
    }

    @Test
    void publishesTileMicroSamplingAndCompletionUpdates() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                "realm_progress_listener_test",
                testCase.dimensionId(),
                "synthetic",
                0.0,
                0,
                0,
                512,
                128,
                32,
                8,
                testCase.sampleMode(),
                WorldSurveyRunner.ResumePolicy.RESCAN
        );
        List<WorldSurveyRunner.ProgressUpdate> updates = new ArrayList<>();

        new WorldSurveyRunner(tempDir.resolve("realm_debug"), GisClassifierConfig.defaults()).run(config,
                new SyntheticAtlasSampler(testCase.profile()), updates::add);

        assertTrue(updates.stream().anyMatch(update -> "tile_scan".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "micro_sampling".equals(update.phase())));
        WorldSurveyRunner.ProgressUpdate completed = updates.get(updates.size() - 1);
        assertEquals("completed", completed.status());
        assertEquals("complete", completed.phase());
        assertEquals(100.0, completed.phaseProgressPercent());
    }
}
