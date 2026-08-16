package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Produces one height-colored, shaded and candidate-highlighted terrain preview. */
public final class PatchCandidateTerrainPreviewService {
    public static final String SCHEMA_VERSION = "patch_candidate_terrain_preview.v0.1";
    public static final String REQUIRED_NEXT_GATE = RealmT4CoarseTerrainPreviewService.REQUIRED_NEXT_GATE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] NEIGHBORHOOD = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0}, {1, 0},
            {-1, 1}, {0, 1}, {1, 1}
    };
    private static final double MAX_BUILDABLE_SLOPE_DEGREES = 12.0;
    private static final double MAX_BUILDABLE_LOCAL_RELIEF_BLOCKS = 12.0;

    private final Path debugRoot;

    public PatchCandidateTerrainPreviewService(Path debugRoot) {
        this.debugRoot = Objects.requireNonNull(debugRoot, "debugRoot").toAbsolutePath().normalize();
    }

    public Result ensure(String runId, String realmId, String dimensionId, String scopeSourceIdentity,
            Target target, Level level, TerrainPreviewProviderSelection selection) throws IOException {
        String safeRunId = safeId(runId, "runId");
        String safeRealmId = safeId(realmId, "realmId");
        String normalizedDimension = requireText(dimensionId, "dimensionId");
        String normalizedScopeIdentity = requireText(scopeSourceIdentity, "scopeSourceIdentity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(selection, "selection");
        target.validate();

        ViewGrid viewGrid = viewGrid(target, level);
        String sourceIdentity = sourceIdentity(safeRunId, safeRealmId, normalizedDimension,
                normalizedScopeIdentity, target, level, viewGrid, selection);
        Path runDirectory = debugRoot.resolve(safeRunId).normalize();
        if (!runDirectory.startsWith(debugRoot)) {
            throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_RUN_OUTSIDE_DEBUG_ROOT");
        }
        Path artifactDirectory = runDirectory.resolve("patch_candidate_terrain_preview")
                .resolve(safeId(target.scopeType(), "scopeType"))
                .resolve(safeRealmId)
                .resolve(safeId(target.candidateId(), "candidateId"))
                .resolve(level.contractName() + "_" + shortHash(sourceIdentity));
        Path evidencePath = artifactDirectory.resolve("terrain_preview_evidence.json");
        Path terrainPreviewPath = artifactDirectory.resolve("terrain.png");
        Result cached = readCached(evidencePath, terrainPreviewPath, sourceIdentity);
        if (cached != null) {
            return cached;
        }

        List<SampleCell> sampled = sample(target, viewGrid, selection);
        List<SampleCell> measured = withTerrainMetrics(sampled, viewGrid.sampleStepBlocks());
        BuildableResult buildable = classifyBuildable(measured, viewGrid.sampleStepBlocks());
        List<SampleCell> classified = buildable.cells();
        Files.createDirectories(artifactDirectory);
        renderTerrainPreview(classified, viewGrid.sampleStepBlocks(), terrainPreviewPath);

        JsonObject evidence = evidence(safeRunId, safeRealmId, normalizedDimension,
                normalizedScopeIdentity, sourceIdentity, target, level, viewGrid, selection, classified,
                buildable, runDirectory, terrainPreviewPath);
        writeJson(evidencePath, evidence);
        return result(evidencePath, terrainPreviewPath, evidence, false);
    }

    private Result readCached(Path evidencePath, Path terrainPreviewPath, String sourceIdentity) {
        if (!regularNonEmpty(evidencePath) || !regularNonEmpty(terrainPreviewPath)) {
            return null;
        }
        try {
            JsonObject evidence = readObject(evidencePath);
            if (!SCHEMA_VERSION.equals(stringValue(evidence, "schemaVersion", ""))
                    || !sourceIdentity.equals(stringValue(evidence, "sourceIdentity", ""))) {
                return null;
            }
            return result(evidencePath, terrainPreviewPath, evidence, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean regularNonEmpty(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0L;
        } catch (IOException ex) {
            return false;
        }
    }

    private static Result result(Path evidencePath, Path terrainPreviewPath,
            JsonObject evidence, boolean cacheHit) throws IOException {
        return new Result(evidencePath, terrainPreviewPath,
                evidence, "sha256:" + sha256(Files.readAllBytes(evidencePath)), cacheHit);
    }

    private static ViewGrid viewGrid(Target target, Level level) {
        if (level == Level.CITY_SCALE_CONFIRMATION) {
            int step = 16;
            int diameter = 1024;
            int minBlockX = Math.floorDiv(target.anchorBlockX() - diameter / 2, step) * step;
            int minBlockZ = Math.floorDiv(target.anchorBlockZ() - diameter / 2, step) * step;
            return new ViewGrid(step, minBlockX, minBlockZ, 64, diameter, "city_scale_anchor");
        }
        int minGridX = target.memberCells().stream().mapToInt(CoarseCell::gridX).min().orElseThrow();
        int maxGridX = target.memberCells().stream().mapToInt(CoarseCell::gridX).max().orElseThrow();
        int minGridZ = target.memberCells().stream().mapToInt(CoarseCell::gridZ).min().orElseThrow();
        int maxGridZ = target.memberCells().stream().mapToInt(CoarseCell::gridZ).max().orElseThrow();
        int coarseStep = target.coarseCellStepBlocks();
        long minCandidateX = (long) minGridX * coarseStep;
        long maxCandidateX = ((long) maxGridX + 1L) * coarseStep;
        long minCandidateZ = (long) minGridZ * coarseStep;
        long maxCandidateZ = ((long) maxGridZ + 1L) * coarseStep;
        long candidateSpan = Math.max(maxCandidateX - minCandidateX, maxCandidateZ - minCandidateZ);
        long context = Math.max(128L, candidateSpan / 2L);
        long requiredDiameter = candidateSpan + context * 2L;
        int step = 8;
        while ((long) step * 64L < requiredDiameter && step <= 16_384) {
            step *= 2;
        }
        int diameter = Math.multiplyExact(step, 64);
        long centerX = Math.floorDiv(minCandidateX + maxCandidateX, 2L);
        long centerZ = Math.floorDiv(minCandidateZ + maxCandidateZ, 2L);
        int minBlockX = Math.toIntExact(Math.floorDiv(centerX - diameter / 2L, step) * step);
        int minBlockZ = Math.toIntExact(Math.floorDiv(centerZ - diameter / 2L, step) * step);
        return new ViewGrid(step, minBlockX, minBlockZ, 64, diameter,
                "candidate_bounds_with_context");
    }

    private static List<SampleCell> sample(Target target, ViewGrid viewGrid,
            TerrainPreviewProviderSelection selection) {
        int step = viewGrid.sampleStepBlocks();
        int minBlockX = viewGrid.minBlockX();
        int minBlockZ = viewGrid.minBlockZ();
        int width = viewGrid.sampleWidth();
        Set<String> candidateCells = new HashSet<>();
        target.memberCells().forEach(cell -> candidateCells.add(key(cell.gridX(), cell.gridZ())));
        List<SampleCell> result = new ArrayList<>(width * width);
        for (int localZ = 0; localZ < width; localZ++) {
            int blockZ = minBlockZ + localZ * step + step / 2;
            for (int localX = 0; localX < width; localX++) {
                int blockX = minBlockX + localX * step + step / 2;
                TerrainPreviewSample sample = Objects.requireNonNull(selection.sample(blockX, blockZ),
                        "Terrain preview provider returned null.");
                if (sample.blockX() != blockX || sample.blockZ() != blockZ) {
                    throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_PROVIDER_COORDINATE_MISMATCH: requested "
                            + blockX + "," + blockZ + " but got " + sample.blockX() + "," + sample.blockZ());
                }
                boolean inside = candidateCells.contains(key(Math.floorDiv(blockX, target.coarseCellStepBlocks()),
                        Math.floorDiv(blockZ, target.coarseCellStepBlocks())));
                result.add(new SampleCell(localX, localZ, blockX, blockZ, sample.elevation(), sample.water(),
                        sample.biomeId(), sample.terrainId(), sample.sourceBiomeId(), inside,
                        0.0, 0.0, false, "unclassified"));
            }
        }
        return List.copyOf(result);
    }

    private static List<SampleCell> withTerrainMetrics(List<SampleCell> source, int step) {
        Map<String, SampleCell> indexed = new HashMap<>();
        source.forEach(cell -> indexed.put(key(cell.gridX(), cell.gridZ()), cell));
        List<SampleCell> result = new ArrayList<>(source.size());
        for (SampleCell cell : source) {
            double maximumCardinalDelta = 0.0;
            double minimumElevation = cell.elevation();
            double maximumElevation = cell.elevation();
            for (int[] direction : CARDINAL) {
                SampleCell neighbor = indexed.get(key(cell.gridX() + direction[0], cell.gridZ() + direction[1]));
                if (neighbor != null) {
                    maximumCardinalDelta = Math.max(maximumCardinalDelta,
                            Math.abs(cell.elevation() - neighbor.elevation()));
                }
            }
            for (int[] direction : NEIGHBORHOOD) {
                SampleCell neighbor = indexed.get(key(cell.gridX() + direction[0], cell.gridZ() + direction[1]));
                if (neighbor != null) {
                    minimumElevation = Math.min(minimumElevation, neighbor.elevation());
                    maximumElevation = Math.max(maximumElevation, neighbor.elevation());
                }
            }
            double slopeDegrees = Math.toDegrees(Math.atan2(maximumCardinalDelta, step));
            result.add(cell.withMetrics(slopeDegrees, maximumElevation - minimumElevation));
        }
        return List.copyOf(result);
    }

    private static BuildableResult classifyBuildable(List<SampleCell> source, int step) {
        List<SampleCell> classified = new ArrayList<>(source.size());
        for (SampleCell cell : source) {
            String reason = !cell.insideCandidate() ? "outside_candidate"
                    : cell.water() ? "water"
                    : cell.slopeDegrees() > MAX_BUILDABLE_SLOPE_DEGREES ? "slope_exceeded"
                    : cell.localRelief() > MAX_BUILDABLE_LOCAL_RELIEF_BLOCKS ? "relief_exceeded"
                    : "buildable";
            classified.add(cell.withBuildable("buildable".equals(reason), reason));
        }
        Map<String, SampleCell> remaining = new LinkedHashMap<>();
        classified.stream().filter(SampleCell::buildable)
                .forEach(cell -> remaining.put(key(cell.gridX(), cell.gridZ()), cell));
        Set<String> largest = Set.of();
        List<Integer> componentSizes = new ArrayList<>();
        while (!remaining.isEmpty()) {
            SampleCell seed = remaining.values().iterator().next();
            remaining.remove(key(seed.gridX(), seed.gridZ()));
            ArrayDeque<SampleCell> queue = new ArrayDeque<>();
            Set<String> component = new HashSet<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                SampleCell current = queue.removeFirst();
                component.add(key(current.gridX(), current.gridZ()));
                for (int[] direction : CARDINAL) {
                    SampleCell next = remaining.remove(key(current.gridX() + direction[0],
                            current.gridZ() + direction[1]));
                    if (next != null) {
                        queue.addLast(next);
                    }
                }
            }
            componentSizes.add(component.size());
            if (component.size() > largest.size()) {
                largest = Set.copyOf(component);
            }
        }
        componentSizes.sort(Comparator.reverseOrder());
        return new BuildableResult(List.copyOf(classified), largest, List.copyOf(componentSizes),
                (long) largest.size() * step * step);
    }

    private static JsonObject evidence(String runId, String realmId, String dimensionId,
            String scopeSourceIdentity, String sourceIdentity, Target target, Level level,
            ViewGrid viewGrid, TerrainPreviewProviderSelection selection, List<SampleCell> cells,
            BuildableResult buildable, Path runDirectory, Path terrainPreviewPath) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("runId", runId);
        root.addProperty("realmId", realmId);
        root.addProperty("dimensionId", dimensionId);
        root.addProperty("candidateId", target.candidateId());
        root.addProperty("scopeType", target.scopeType());
        root.addProperty("scopeSourceIdentity", scopeSourceIdentity);
        root.addProperty("sourceIdentity", sourceIdentity);
        root.addProperty("evaluationLevel", level.contractName());
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("advisoryOnly", true);
        root.addProperty("requiredNextGate", requiredNextGate(target.scopeType()));

        JsonObject provider = new JsonObject();
        provider.addProperty("providerId", selection.providerId());
        provider.addProperty("sourceKind", selection.sourceKind());
        provider.addProperty("fastPath", selection.fastPath());
        provider.addProperty("fallbackReason", selection.fallbackReason());
        provider.addProperty("sourceFingerprint", selection.sourceFingerprint());
        provider.addProperty("samplingSemantics", selection.samplingSemantics());
        root.add("provider", provider);

        JsonObject grid = new JsonObject();
        grid.addProperty("sampleStepBlocks", viewGrid.sampleStepBlocks());
        grid.addProperty("radiusBlocks", viewGrid.windowDiameterBlocks() / 2);
        grid.addProperty("windowDiameterBlocks", viewGrid.windowDiameterBlocks());
        grid.addProperty("framingMode", viewGrid.framingMode());
        grid.addProperty("minBlockX", viewGrid.minBlockX());
        grid.addProperty("minBlockZ", viewGrid.minBlockZ());
        grid.addProperty("samplingPattern", "bounded_square_cell_centers_with_candidate_mask");
        grid.addProperty("sampleCount", cells.size());
        grid.addProperty("candidateSampleCount", cells.stream().filter(SampleCell::insideCandidate).count());
        grid.addProperty("anchorBlockX", target.anchorBlockX());
        grid.addProperty("anchorBlockZ", target.anchorBlockZ());
        grid.addProperty("coarseCandidateCellStepBlocks", target.coarseCellStepBlocks());
        root.add("grid", grid);
        root.add("buildabilityPolicy", buildabilityPolicy());
        root.add("summary", summary(cells, buildable, viewGrid.sampleStepBlocks()));

        JsonArray items = new JsonArray();
        cells.forEach(cell -> items.add(cell.asJson()));
        root.add("cells", items);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("terrainPreview", relative(runDirectory, terrainPreviewPath));
        root.add("artifacts", artifacts);
        return root;
    }

    private static JsonObject buildabilityPolicy() {
        JsonObject policy = new JsonObject();
        policy.addProperty("policyId", "patch_candidate_buildability_advisory_v0_1");
        policy.addProperty("maximumSlopeDegrees", MAX_BUILDABLE_SLOPE_DEGREES);
        policy.addProperty("maximumLocalReliefBlocks", MAX_BUILDABLE_LOCAL_RELIEF_BLOCKS);
        policy.addProperty("waterAllowed", false);
        policy.addProperty("connectivity", "cardinal");
        policy.addProperty("role", "advisory_patch_comparison");
        return policy;
    }

    private static String requiredNextGate(String scopeType) {
        return switch (scopeType) {
            case "realm_t2" -> "realm_t2_selection_review";
            case "realm_t4" -> REQUIRED_NEXT_GATE;
            case "city_d4" -> "city_d4_placement_review";
            default -> throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_SCOPE_TYPE_INVALID");
        };
    }

    private static JsonObject summary(List<SampleCell> cells, BuildableResult buildable, int sampleStepBlocks) {
        List<SampleCell> inside = cells.stream().filter(SampleCell::insideCandidate).toList();
        List<Double> heights = inside.stream().map(SampleCell::elevation).sorted().toList();
        List<Double> slopes = inside.stream().map(SampleCell::slopeDegrees).sorted().toList();
        List<Double> relief = inside.stream().map(SampleCell::localRelief).sorted().toList();
        long water = inside.stream().filter(SampleCell::water).count();
        long buildableCount = inside.stream().filter(SampleCell::buildable).count();
        JsonObject summary = new JsonObject();
        summary.addProperty("candidateSampleCount", inside.size());
        summary.addProperty("heightP10", percentile(heights, 0.10));
        summary.addProperty("heightP50", percentile(heights, 0.50));
        summary.addProperty("heightP90", percentile(heights, 0.90));
        summary.addProperty("robustRelief", percentile(heights, 0.90) - percentile(heights, 0.10));
        summary.addProperty("waterFrac", inside.isEmpty() ? 0.0 : water / (double) inside.size());
        summary.addProperty("slopeDegreesP90", percentile(slopes, 0.90));
        summary.addProperty("localReliefP90", percentile(relief, 0.90));
        summary.addProperty("buildableSampleCount", buildableCount);
        summary.addProperty("buildableRatio", inside.isEmpty() ? 0.0 : buildableCount / (double) inside.size());
        summary.addProperty("largestContinuousBuildableAreaBlocks", buildable.largestAreaBlocks());
        JsonArray componentAreas = new JsonArray();
        buildable.componentSizes().stream().limit(8)
                .forEach(size -> componentAreas.add((long) size * sampleStepBlocks * sampleStepBlocks));
        summary.add("largestBuildableComponentAreasBlocks", componentAreas);
        return summary;
    }

    private static void renderTerrainPreview(List<SampleCell> cells, int sampleStepBlocks, Path output)
            throws IOException {
        Map<String, SampleCell> indexed = index(cells);
        int width = cells.stream().mapToInt(SampleCell::gridX).max().orElse(0) + 1;
        int height = cells.stream().mapToInt(SampleCell::gridZ).max().orElse(0) + 1;
        int scale = Math.max(2, Math.min(10, 768 / Math.max(width, height)));
        BufferedImage image = new BufferedImage(Math.max(1, width * scale), Math.max(1, height * scale),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(new Color(24, 27, 30));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (SampleCell cell : cells) {
                int x = cell.gridX() * scale;
                int z = cell.gridZ() * scale;
                Color color = shadedHeightColor(cell, indexed, sampleStepBlocks);
                if (cell.insideCandidate()) {
                    color = mix(color, new Color(244, 190, 63), 0.10);
                } else {
                    color = mix(color, new Color(45, 50, 51), 0.48);
                }
                graphics.setColor(color);
                graphics.fillRect(x, z, scale, scale);
            }
            graphics.setStroke(new BasicStroke(Math.max(2.0f, scale * 0.35f),
                    BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            graphics.setColor(new Color(244, 190, 63));
            for (SampleCell cell : cells) {
                if (!cell.insideCandidate()) {
                    continue;
                }
                int x = cell.gridX() * scale;
                int z = cell.gridZ() * scale;
                if (!inside(indexed, cell.gridX(), cell.gridZ() - 1)) graphics.drawLine(x, z, x + scale, z);
                if (!inside(indexed, cell.gridX() + 1, cell.gridZ())) graphics.drawLine(x + scale, z, x + scale, z + scale);
                if (!inside(indexed, cell.gridX(), cell.gridZ() + 1)) graphics.drawLine(x, z + scale, x + scale, z + scale);
                if (!inside(indexed, cell.gridX() - 1, cell.gridZ())) graphics.drawLine(x, z, x, z + scale);
            }
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("No PNG writer is available.");
        }
    }

    private static Color shadedHeightColor(SampleCell cell, Map<String, SampleCell> indexed,
            int sampleStepBlocks) {
        Color base = cell.water() ? new Color(78, 139, 181) : heightColor(cell.elevation());
        double west = elevation(indexed, cell.gridX() - 1, cell.gridZ(), cell.elevation());
        double east = elevation(indexed, cell.gridX() + 1, cell.gridZ(), cell.elevation());
        double north = elevation(indexed, cell.gridX(), cell.gridZ() - 1, cell.elevation());
        double south = elevation(indexed, cell.gridX(), cell.gridZ() + 1, cell.elevation());
        double dx = (east - west) / (2.0 * sampleStepBlocks);
        double dz = (south - north) / (2.0 * sampleStepBlocks);
        double length = Math.sqrt(dx * dx + dz * dz + 1.0);
        double light = ((-dx / length) + (1.0 / length) + (-dz / length)) / Math.sqrt(3.0);
        double factor = 0.78 + 0.40 * Math.max(0.0, light);
        return multiply(base, factor);
    }

    private static Color heightColor(double elevation) {
        double[] heights = {-64.0, 50.0, 70.0, 90.0, 120.0, 160.0, 220.0, 320.0};
        Color[] colors = {
                new Color(48, 75, 71), new Color(67, 112, 73),
                new Color(112, 145, 79), new Color(164, 159, 88),
                new Color(154, 140, 111), new Color(160, 158, 149),
                new Color(207, 207, 201), new Color(242, 242, 238)
        };
        if (elevation <= heights[0]) return colors[0];
        for (int index = 1; index < heights.length; index++) {
            if (elevation <= heights[index]) {
                double fraction = (elevation - heights[index - 1]) / (heights[index] - heights[index - 1]);
                return mix(colors[index - 1], colors[index], fraction);
            }
        }
        return colors[colors.length - 1];
    }

    private static Color multiply(Color color, double factor) {
        return new Color(clampColor((int) Math.round(color.getRed() * factor)),
                clampColor((int) Math.round(color.getGreen() * factor)),
                clampColor((int) Math.round(color.getBlue() * factor)));
    }

    private static Color mix(Color first, Color second, double fraction) {
        double weight = clamp01(fraction);
        return new Color(clampColor((int) Math.round(first.getRed() * (1.0 - weight) + second.getRed() * weight)),
                clampColor((int) Math.round(first.getGreen() * (1.0 - weight) + second.getGreen() * weight)),
                clampColor((int) Math.round(first.getBlue() * (1.0 - weight) + second.getBlue() * weight)));
    }

    private static boolean inside(Map<String, SampleCell> indexed, int x, int z) {
        SampleCell cell = indexed.get(key(x, z));
        return cell != null && cell.insideCandidate();
    }

    private static Map<String, SampleCell> index(List<SampleCell> cells) {
        Map<String, SampleCell> indexed = new HashMap<>();
        cells.forEach(cell -> indexed.put(key(cell.gridX(), cell.gridZ()), cell));
        return indexed;
    }

    private static double elevation(Map<String, SampleCell> indexed, int x, int z, double fallback) {
        SampleCell cell = indexed.get(key(x, z));
        return cell == null ? fallback : cell.elevation();
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String sourceIdentity(String runId, String realmId, String dimensionId,
            String scopeSourceIdentity, Target target, Level level, ViewGrid viewGrid,
            TerrainPreviewProviderSelection selection) {
        StringBuilder source = new StringBuilder(String.join("\n", SCHEMA_VERSION, runId, realmId, dimensionId,
                scopeSourceIdentity, target.scopeType(), target.candidateId(), level.contractName(),
                Integer.toString(viewGrid.sampleStepBlocks()), Integer.toString(viewGrid.minBlockX()),
                Integer.toString(viewGrid.minBlockZ()), Integer.toString(viewGrid.windowDiameterBlocks()),
                viewGrid.framingMode(),
                Integer.toString(target.anchorBlockX()), Integer.toString(target.anchorBlockZ()),
                Integer.toString(target.coarseCellStepBlocks()),
                Double.toString(MAX_BUILDABLE_SLOPE_DEGREES),
                Double.toString(MAX_BUILDABLE_LOCAL_RELIEF_BLOCKS),
                selection.providerId(), selection.sourceKind(),
                Boolean.toString(selection.fastPath()), selection.fallbackReason(), selection.sourceFingerprint(),
                selection.samplingSemantics())).append('\n');
        target.memberCells().stream().sorted(Comparator.comparingInt(CoarseCell::gridZ)
                        .thenComparingInt(CoarseCell::gridX))
                .forEach(cell -> source.append(cell.gridX()).append(',').append(cell.gridZ()).append('\n'));
        return "sha256:" + sha256(source.toString());
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.round((sorted.size() - 1) * fraction);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String relative(Path root, Path child) {
        return root.relativize(child).toString().replace('\\', '/');
    }

    private static String key(int x, int z) {
        return x + "," + z;
    }

    private static String shortHash(String value) {
        String hash = value.startsWith("sha256:") ? value.substring("sha256:".length()) : sha256(value);
        return hash.substring(0, Math.min(16, hash.length()));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_ARTIFACT_INVALID: " + path);
        }
        return parsed.getAsJsonObject();
    }

    private static void writeJson(Path path, JsonElement value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static String safeId(String value, String field) {
        String safe = requireText(value, field);
        if (!safe.matches("[A-Za-z0-9._-]{1,160}")) {
            throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_"
                    + field.toUpperCase(Locale.ROOT) + "_INVALID");
        }
        return safe;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    public enum Level {
        CANDIDATE_COMPARISON("candidate_comparison"),
        CITY_SCALE_CONFIRMATION("city_scale_confirmation");

        private final String contractName;

        Level(String contractName) {
            this.contractName = contractName;
        }

        public String contractName() {
            return contractName;
        }

    }

    public record CoarseCell(int gridX, int gridZ) {
    }

    public record Target(String scopeType, String candidateId, int anchorBlockX, int anchorBlockZ,
                         int coarseCellStepBlocks, List<CoarseCell> memberCells) {
        public Target {
            scopeType = requireText(scopeType, "scopeType");
            candidateId = requireText(candidateId, "candidateId");
            memberCells = List.copyOf(Objects.requireNonNull(memberCells, "memberCells"));
        }

        private void validate() {
            if (!Set.of("realm_t2", "realm_t4", "city_d4").contains(scopeType)) {
                throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_SCOPE_TYPE_INVALID");
            }
            safeId(candidateId, "candidateId");
            if (coarseCellStepBlocks <= 0) {
                throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_COARSE_STEP_INVALID");
            }
            if (memberCells.isEmpty()) {
                throw new IllegalArgumentException("PATCH_CANDIDATE_TERRAIN_PREVIEW_CANDIDATE_EMPTY");
            }
        }
    }

    public record Result(Path evidencePath, Path terrainPreviewPath, JsonObject evidence,
                         String evidenceIdentity, boolean cacheHit) {
        public Result {
            evidencePath = Objects.requireNonNull(evidencePath, "evidencePath");
            terrainPreviewPath = Objects.requireNonNull(terrainPreviewPath, "terrainPreviewPath");
            evidence = Objects.requireNonNull(evidence, "evidence").deepCopy();
            evidenceIdentity = requireText(evidenceIdentity, "evidenceIdentity");
        }

        public String sourceIdentity() {
            return stringValue(evidence, "sourceIdentity", "");
        }
    }

    private record SampleCell(int gridX, int gridZ, int blockX, int blockZ, double elevation,
                              boolean water, String biomeId, String terrainId, String sourceBiomeId,
                              boolean insideCandidate, double slopeDegrees, double localRelief,
                              boolean buildable, String rejectionReason) {
        private SampleCell withMetrics(double slopeDegrees, double localRelief) {
            return new SampleCell(gridX, gridZ, blockX, blockZ, elevation, water, biomeId, terrainId,
                    sourceBiomeId, insideCandidate, slopeDegrees, localRelief, buildable, rejectionReason);
        }

        private SampleCell withBuildable(boolean buildable, String rejectionReason) {
            return new SampleCell(gridX, gridZ, blockX, blockZ, elevation, water, biomeId, terrainId,
                    sourceBiomeId, insideCandidate, slopeDegrees, localRelief, buildable, rejectionReason);
        }

        private JsonObject asJson() {
            JsonObject result = new JsonObject();
            result.addProperty("gridX", gridX);
            result.addProperty("gridZ", gridZ);
            result.addProperty("blockX", blockX);
            result.addProperty("blockZ", blockZ);
            result.addProperty("elevation", elevation);
            result.addProperty("water", water);
            result.addProperty("biomeId", biomeId);
            result.addProperty("terrainId", terrainId);
            result.addProperty("sourceBiomeId", sourceBiomeId);
            result.addProperty("insideCandidate", insideCandidate);
            result.addProperty("slopeDegrees", slopeDegrees);
            result.addProperty("localRelief", localRelief);
            result.addProperty("buildable", buildable);
            result.addProperty("rejectionReason", rejectionReason);
            return result;
        }
    }

    private record BuildableResult(List<SampleCell> cells, Set<String> largestComponent,
                                   List<Integer> componentSizes, long largestAreaBlocks) {
    }

    private record ViewGrid(int sampleStepBlocks, int minBlockX, int minBlockZ, int sampleWidth,
                            int windowDiameterBlocks, String framingMode) {
    }
}
