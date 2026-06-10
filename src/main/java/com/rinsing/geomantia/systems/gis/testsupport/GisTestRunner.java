package com.rinsing.geomantia.systems.gis.testsupport;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import com.rinsing.geomantia.systems.gis.preview.AtlasJson;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshResult;
import com.rinsing.geomantia.systems.gis.adapter.snapshot.AtlasRegionSnapshotIo;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GisTestRunner {
    private final GisSampleConfig sampleConfig;
    private final GisClassifierConfig classifierConfig;

    public GisTestRunner(GisSampleConfig sampleConfig, GisClassifierConfig classifierConfig) {
        this.sampleConfig = sampleConfig;
        this.classifierConfig = classifierConfig;
    }

    public GisTestReport runCase(GisTestCase testCase, Path debugRoot) throws IOException {
        long started = System.currentTimeMillis();
        AtlasRegionStore store = new AtlasRegionStore(sampleConfig);
        GisRefreshService service = new GisRefreshService(sampleConfig, classifierConfig, store,
                new SyntheticAtlasSampler(testCase.profile()));
        RefreshResult result = service.refresh(testCase.dimensionId(), testCase.centerBlockX(), testCase.centerBlockZ(),
                testCase.radiusChunks(), testCase.sampleMode(), RefreshPriority.DEBUG, debugRoot);
        Path runDirectory = result.runDirectory();
        new AtlasRegionSnapshotIo().write(result.region(), runDirectory.resolve("region_snapshot.json"));
        List<String> failures = assertResult(testCase, result);
        long duration = System.currentTimeMillis() - started;
        GisTestReport report = GisTestReport.from(testCase, result, failures.isEmpty(), duration, failures);
        Files.writeString(runDirectory.resolve("test_report.json"), AtlasJson.GSON.toJson(report.asJson()));
        return report;
    }

    private static List<String> assertResult(GisTestCase testCase, RefreshResult result) {
        List<String> failures = new ArrayList<>();
        int failed = result.cellCounts().getOrDefault("failed", 0);
        if (failed != testCase.failedCellCount()) {
            failures.add("failedCellCount expected " + testCase.failedCellCount() + " but was " + failed);
        }
        int totalClassified = result.landformCounts().values().stream().mapToInt(Integer::intValue).sum();
        int unknown = result.landformCounts().getOrDefault(LandformType.UNKNOWN.contractName(), 0);
        double unknownRatio = totalClassified == 0 ? 1.0 : unknown / (double) totalClassified;
        if (unknownRatio > testCase.unknownMaxRatio()) {
            failures.add("unknownRatio expected <= " + testCase.unknownMaxRatio() + " but was " + unknownRatio);
        }
        for (LandformType type : testCase.mustContain()) {
            if (result.landformCounts().getOrDefault(type.contractName(), 0) <= 0) {
                failures.add("missing landform type " + type.contractName());
            }
        }
        if (testCase.dominantLandform() != null) {
            String dominant = dominant(result.landformCounts());
            if (!testCase.dominantLandform().contractName().equals(dominant)) {
                failures.add("dominant landform expected " + testCase.dominantLandform().contractName()
                        + " but was " + dominant);
            }
        }
        if (result.patches().isEmpty()) {
            failures.add("patch count must be > 0");
        }
        return failures;
    }

    private static String dominant(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .filter(entry -> !LandformType.UNKNOWN.contractName().equals(entry.getKey()))
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(LandformType.UNKNOWN.contractName());
    }

    public record GisTestReport(
            String runId,
            String caseId,
            String command,
            String sampleMode,
            boolean passed,
            long durationMs,
            Map<String, Integer> cellCounts,
            Map<String, Integer> landformCounts,
            Map<String, Object> patchCounts,
            List<String> failures,
            Map<String, String> artifacts
    ) {
        static GisTestReport from(GisTestCase testCase, RefreshResult result, boolean passed, long durationMs,
                List<String> failures) {
            int maxPatchCells = result.patches().stream().mapToInt(patch -> patch.cellCount()).max().orElse(0);
            Map<String, Object> patchCounts = new LinkedHashMap<>();
            patchCounts.put("total", result.patches().size());
            patchCounts.put("maxPatchCells", maxPatchCells);
            Map<String, String> artifacts = new LinkedHashMap<>();
            artifacts.put("progress", "progress.png");
            artifacts.put("progressManifest", "progress_manifest.json");
            artifacts.put("previewManifest", "preview/preview_manifest.json");
            artifacts.put("testReport", "test_report.json");
            artifacts.put("regionSnapshot", "region_snapshot.json");
            return new GisTestReport(result.job().jobId(), testCase.id(), "geomantia gis test_run " + testCase.id(),
                    testCase.sampleMode().contractName(), passed, durationMs, result.cellCounts(),
                    result.landformCounts(), patchCounts, failures, artifacts);
        }

        public Map<String, Object> asJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("runId", runId);
            json.put("caseId", caseId);
            json.put("command", command);
            json.put("sampleMode", sampleMode);
            json.put("passed", passed);
            json.put("durationMs", durationMs);
            json.put("cellCounts", cellCounts);
            json.put("landformCounts", landformCounts);
            json.put("patchCounts", patchCounts);
            json.put("failures", failures);
            json.put("artifacts", artifacts);
            return json;
        }
    }
}
