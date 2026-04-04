package com.user.terra_script.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.domain.world.scan.ScanPixel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TerritoryManager {
    private static final double LAND_POWER_SCORE_WEIGHT = 0.25;
    private static final int LAND_POWER_CONFLICT_COST = 1;
    private static final int HIGH_RES_EXPANSION_STEP = 16;

    public static class TerritoryConfig {
        public String id;
        public String territoryId;
        public String name;
        public int regionId;
        public int selectedContinentId;
        public int capitalX;
        public int capitalZ;
        public int seedCellX;
        public int seedCellZ;
        public int maxPower;
        public int landPower;
        public int blueprintOrder;
        public double mountainCost;
        public double waterCost;
        public int color;

        public TerritoryConfig(
                String id,
                String territoryId,
                String name,
                int regionId,
                int capitalX,
                int capitalZ,
                int seedCellX,
                int seedCellZ,
                int maxPower,
                int landPower,
                int blueprintOrder,
                double mountainCost,
                double waterCost,
                int color
        ) {
            this.id = id;
            this.territoryId = territoryId;
            this.name = name;
            this.regionId = regionId;
            this.selectedContinentId = regionId;
            this.capitalX = capitalX;
            this.capitalZ = capitalZ;
            this.seedCellX = seedCellX;
            this.seedCellZ = seedCellZ;
            this.maxPower = maxPower;
            this.landPower = landPower;
            this.blueprintOrder = blueprintOrder;
            this.mountainCost = mountainCost;
            this.waterCost = waterCost;
            this.color = color;
        }

        public TerritoryConfig(String id, String name, int regionId, int x, int z, int power, double mCost, double wCost, int color) {
            this(id, id, name, regionId, x, z, x, z, power, Math.max(1, (int) Math.round(power * 0.35)), Integer.MAX_VALUE, mCost, wCost, color);
        }
    }

    public static class TerritoryResult {
        public TerritoryConfig config;
        public Set<Long> claimedChunks = new HashSet<>();
        public Set<Long> wildChunks = new HashSet<>();
        public TerritoryStats stats;

        public TerritoryResult(TerritoryConfig config) {
            this.config = config;
        }
    }

    public static class TerritoryStats {
        public long area_pixels = 0;
        public int minX = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE;
        public int minZ = Integer.MAX_VALUE;
        public int maxZ = Integer.MIN_VALUE;
        public Map<Integer, Integer> rawContinentCounts = new HashMap<>();
        public Map<String, Integer> rawBiomeCounts = new HashMap<>();
        public Set<String> neighborIds = new HashSet<>();
        public Map<Integer, Double> continent_distribution = new HashMap<>();
        public Map<String, Double> biome_composition = new HashMap<>();
        public int land_power_initial = 0;
        public int land_power_remaining = 0;
        public int land_power_spent = 0;
        public int conflict_cells = 0;
        public int conflict_wins = 0;
        public int conflict_losses = 0;
    }

    public static class SeedCell {
        public final int gridX;
        public final int gridZ;
        public final int worldX;
        public final int worldZ;

        public SeedCell(int gridX, int gridZ, int worldX, int worldZ) {
            this.gridX = gridX;
            this.gridZ = gridZ;
            this.worldX = worldX;
            this.worldZ = worldZ;
        }
    }

    public static class CellConflictRecord {
        public int cellWorldX;
        public int cellWorldZ;
        public String winnerId;
        public String winnerTerritoryId;
        public double winningCost;
        public double winningScore;
        public int landPowerSpent;
        public final List<ConflictContender> contenders = new ArrayList<>();
    }

    public static class ConflictContender {
        public String territoryInstanceId;
        public String territoryId;
        public double cost;
        public int landPowerBefore;
        public double score;
        public boolean winner;
    }

    public static class ContinentExpansionReport {
        public int continentId;
        public long claimedTotal;
        public long wildTotal;
        public final List<CellConflictRecord> conflicts = new ArrayList<>();
    }

    private static class RegionTerrainAggregate {
        public final double[][] slopeAvg;
        public final double[][] landRatio;
        public final boolean[][] hasData;

        public RegionTerrainAggregate(double[][] slopeAvg, double[][] landRatio, boolean[][] hasData) {
            this.slopeAvg = slopeAvg;
            this.landRatio = landRatio;
            this.hasData = hasData;
        }
    }

    private static class ExpansionGrid {
        public final ScanPixel[][] map;
        public final int step;
        public final int minX;
        public final int minZ;
        public final int coarseStep;

        public ExpansionGrid(ScanPixel[][] map, int step, int minX, int minZ, int coarseStep) {
            this.map = map;
            this.step = step;
            this.minX = minX;
            this.minZ = minZ;
            this.coarseStep = coarseStep;
        }
    }

    private static class FactionRuntime {
        public final TerritoryConfig config;
        public final int blueprintOrder;
        public final TerritoryResult result;
        public double[][] distMap;
        public int remainingLandPower;

        private FactionRuntime(TerritoryConfig config, TerritoryResult result) {
            this.config = config;
            this.result = result;
            this.blueprintOrder = config.blueprintOrder;
            this.remainingLandPower = Math.max(0, config.landPower);
        }
    }

    private static class CellCandidate {
        public final FactionRuntime runtime;
        public final double cost;

        private CellCandidate(FactionRuntime runtime, double cost) {
            this.runtime = runtime;
            this.cost = cost;
        }
    }

    private static class CellCompetition {
        public final int gridX;
        public final int gridZ;
        public final List<CellCandidate> contenders;
        public final double bestCost;

        private CellCompetition(int gridX, int gridZ, List<CellCandidate> contenders) {
            this.gridX = gridX;
            this.gridZ = gridZ;
            this.contenders = contenders;
            this.bestCost = contenders.stream().mapToDouble(c -> c.cost).min().orElse(Double.MAX_VALUE);
        }
    }

    private static final List<TerritoryConfig> registeredFactions = new ArrayList<>();
    private static final Map<String, TerritoryResult> results = new ConcurrentHashMap<>();
    private static final Map<Integer, ContinentExpansionReport> continentReports = new ConcurrentHashMap<>();

    public static String[][] globalOwnershipMap = null;
    private static int expansionStepBlocks = 1;
    private static int expansionMinX = 0;
    private static int expansionMinZ = 0;
    private static ScanPixel[][] expansionScanMap = null;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("terra_script_territories.json").toFile();

    public static Collection<TerritoryResult> getAllResults() {
        return results.values();
    }

    public static Collection<TerritoryResult> getResultsForContinent(int continentId) {
        List<TerritoryResult> list = new ArrayList<>();
        for (TerritoryResult result : results.values()) {
            if (result == null || result.config == null) continue;
            if (result.config.regionId == continentId) list.add(result);
        }
        return list;
    }

    public static ContinentExpansionReport getContinentReport(int continentId) {
        return continentReports.get(continentId);
    }

    public static int getExpansionStepBlocks() {
        return Math.max(1, expansionStepBlocks);
    }

    public static int getExpansionMinX() {
        return expansionMinX;
    }

    public static int getExpansionMinZ() {
        return expansionMinZ;
    }

    public static ScanPixel[][] getExpansionScanMap() {
        return expansionScanMap;
    }

    public static List<TerritoryConfig> getRegisteredFactions() {
        ensureLoaded();
        return new ArrayList<>(registeredFactions);
    }

    public static TerritoryConfig getTerritoryConfig(String id) {
        if (id == null) return null;
        ensureLoaded();
        for (TerritoryConfig cfg : registeredFactions) {
            if (id.equals(cfg.id)) return cfg;
        }
        return null;
    }

    public static void createTerritory(String id, String name, int regionId, int startX, int startZ, int maxPower, double mCost, double wCost, int color) {
        createTerritory(id, id, name, regionId, startX, startZ, startX, startZ, maxPower, Math.max(1, (int) Math.round(maxPower * 0.35)), Integer.MAX_VALUE, mCost, wCost, color);
    }

    public static void createTerritory(
            String id,
            String territoryId,
            String name,
            int regionId,
            int startX,
            int startZ,
            int seedCellX,
            int seedCellZ,
            int maxPower,
            int landPower,
            int blueprintOrder,
            double mCost,
            double wCost,
            int color
    ) {
        ensureLoaded();
        registeredFactions.removeIf(c -> c != null && id.equals(c.id));
        if (id != null && id.contains("@c")) {
            registeredFactions.removeIf(c ->
                    c != null
                            && territoryId != null
                            && territoryId.equals(c.territoryId)
                            && regionId == c.regionId
                            && c.id != null
                            && !c.id.contains("@c")
            );
        }
        registeredFactions.add(new TerritoryConfig(
                id,
                territoryId,
                name,
                regionId,
                startX,
                startZ,
                seedCellX,
                seedCellZ,
                maxPower,
                landPower,
                blueprintOrder,
                mCost,
                wCost,
                color
        ));
        registeredFactions.sort(Comparator
                .comparingInt((TerritoryConfig cfg) -> cfg.blueprintOrder)
                .thenComparing(cfg -> cfg.id == null ? "" : cfg.id));
        save();
    }

    public static void removeTerritory(String id) {
        if (id == null || id.isBlank()) return;
        ensureLoaded();
        registeredFactions.removeIf(cfg -> cfg != null && id.equals(cfg.id));
        results.remove(id);
        save();
    }

    public static void ensureLoaded() {
        if (registeredFactions.isEmpty() && CONFIG_FILE.exists()) load();
    }

    public static void refresh() {
        runExpansion();
    }

    public static void runExpansion() {
        ensureLoaded();
        recalculateAll();
    }

    public static Collection<TerritoryResult> runExpansionForContinent(int continentId) {
        runExpansion();
        return getResultsForContinent(continentId);
    }

    public static SeedCell computeSeedCell(int regionId, int pointX, int pointZ) {
        var holder = ScanResultHolder.get();
        if (holder.lastScanData == null) return null;
        ExpansionGrid grid = buildExpansionGrid(holder);
        int seedX = (pointX - grid.minX) / grid.step;
        int seedZ = (pointZ - grid.minZ) / grid.step;
        int[] snapped = snapSeedToRegion(
                seedX,
                seedZ,
                regionId,
                grid.map,
                holder.lastClusterMap,
                grid.minX,
                grid.minZ,
                grid.step,
                grid.coarseStep
        );
        int gx = snapped[0];
        int gz = snapped[1];
        if (gx < 0 || gz < 0 || gx >= grid.map.length || gz >= grid.map[0].length) return null;
        int worldX = grid.minX + gx * grid.step + grid.step / 2;
        int worldZ = grid.minZ + gz * grid.step + grid.step / 2;
        return new SeedCell(gx, gz, worldX, worldZ);
    }

    public static void clear() {
        registeredFactions.clear();
        results.clear();
        continentReports.clear();
        globalOwnershipMap = null;
        expansionScanMap = null;
        expansionStepBlocks = 1;
        expansionMinX = 0;
        expansionMinZ = 0;
        save();
    }

    private static void recalculateAll() {
        results.clear();
        continentReports.clear();
        var holder = ScanResultHolder.get();
        if (holder.lastScanData != null) {
            computeGlobal(holder);
            analyzeTerritories(holder);
        } else {
            System.out.println("[Territory] No global scan data found.");
        }
    }

    private static void computeGlobal(ScanResultHolder holder) {
        ExpansionGrid grid = buildExpansionGrid(holder);
        ScanPixel[][] map = grid.map;
        int[][] clusterMap = holder.lastClusterMap;
        int width = map.length;
        int height = map[0].length;
        int step = grid.step;
        int globalMinX = grid.minX;
        int globalMinZ = grid.minZ;
        int coarseStep = grid.coarseStep;

        expansionScanMap = map;
        expansionStepBlocks = step;
        expansionMinX = globalMinX;
        expansionMinZ = globalMinZ;
        globalOwnershipMap = new String[width][height];

        for (TerritoryConfig cfg : registeredFactions) {
            results.put(cfg.id, new TerritoryResult(cfg));
        }

        Map<Integer, RegionTerrainAggregate> regionAggregates =
                buildRegionTerrainAggregates(holder, width, height, globalMinX, globalMinZ, step);
        Map<Integer, List<TerritoryConfig>> byRegion = new LinkedHashMap<>();
        for (TerritoryConfig cfg : registeredFactions) {
            if (cfg == null || cfg.regionId <= 0) continue;
            byRegion.computeIfAbsent(cfg.regionId, ignored -> new ArrayList<>()).add(cfg);
        }

        for (Map.Entry<Integer, List<TerritoryConfig>> entry : byRegion.entrySet()) {
            computeContinentOwnership(
                    entry.getKey(),
                    entry.getValue(),
                    map,
                    clusterMap,
                    globalMinX,
                    globalMinZ,
                    step,
                    coarseStep,
                    regionAggregates.get(entry.getKey())
            );
        }

        fillEnclaves(globalOwnershipMap, width, height, globalMinX, globalMinZ, step);
        for (Map.Entry<Integer, ContinentExpansionReport> entry : continentReports.entrySet()) {
            ContinentExpansionReport report = entry.getValue();
            if (report == null) continue;
            for (TerritoryResult result : getResultsForContinent(entry.getKey())) {
                if (result == null) continue;
                report.claimedTotal += result.claimedChunks != null ? result.claimedChunks.size() : 0;
                report.wildTotal += result.wildChunks != null ? result.wildChunks.size() : 0;
            }
        }
    }

    private static void computeContinentOwnership(
            int regionId,
            List<TerritoryConfig> configs,
            ScanPixel[][] map,
            int[][] clusterMap,
            int globalMinX,
            int globalMinZ,
            int step,
            int coarseStep,
            RegionTerrainAggregate terrainAgg
    ) {
        if (configs == null || configs.isEmpty()) return;
        configs.sort(Comparator
                .comparingInt((TerritoryConfig cfg) -> cfg.blueprintOrder)
                .thenComparing(cfg -> cfg.id == null ? "" : cfg.id));

        List<FactionRuntime> runtimes = new ArrayList<>();
        for (TerritoryConfig cfg : configs) {
            TerritoryResult result = results.get(cfg.id);
            if (result == null) {
                result = new TerritoryResult(cfg);
                results.put(cfg.id, result);
            }
            TerritoryStats stats = new TerritoryStats();
            stats.land_power_initial = Math.max(0, cfg.landPower);
            stats.land_power_remaining = Math.max(0, cfg.landPower);
            result.stats = stats;
            runtimes.add(new FactionRuntime(cfg, result));
        }

        ContinentExpansionReport report = new ContinentExpansionReport();
        report.continentId = regionId;
        continentReports.put(regionId, report);

        for (FactionRuntime runtime : runtimes) {
            runtime.distMap = computeDistanceMapForFaction(
                    runtime.config,
                    map,
                    clusterMap,
                    globalMinX,
                    globalMinZ,
                    step,
                    coarseStep,
                    terrainAgg
            );
        }

        List<CellCompetition> conflicts = new ArrayList<>();
        for (int x = 0; x < map.length; x++) {
            for (int z = 0; z < map[0].length; z++) {
                if (!isCellAllowedForRegion(x, z, regionId, map, clusterMap, globalMinX, globalMinZ, step, coarseStep)) {
                    continue;
                }
                List<CellCandidate> contenders = new ArrayList<>();
                for (FactionRuntime runtime : runtimes) {
                    double cost = runtime.distMap[x][z];
                    if (cost != Double.MAX_VALUE && cost <= runtime.config.maxPower) {
                        contenders.add(new CellCandidate(runtime, cost));
                    }
                }
                if (contenders.isEmpty()) continue;
                if (contenders.size() == 1) {
                    assignCell(contenders.get(0).runtime.result, x, z, globalMinX, globalMinZ, step);
                } else {
                    conflicts.add(new CellCompetition(x, z, contenders));
                }
            }
        }

        conflicts.sort(Comparator
                .comparingDouble((CellCompetition cell) -> cell.bestCost)
                .thenComparingInt(cell -> cell.gridX)
                .thenComparingInt(cell -> cell.gridZ));

        for (CellCompetition cell : conflicts) {
            FactionRuntime winner = resolveCompetition(cell.contenders);
            assignCell(winner.result, cell.gridX, cell.gridZ, globalMinX, globalMinZ, step);

            CellConflictRecord record = new CellConflictRecord();
            record.cellWorldX = globalMinX + cell.gridX * step + step / 2;
            record.cellWorldZ = globalMinZ + cell.gridZ * step + step / 2;
            record.winnerId = winner.config.id;
            record.winnerTerritoryId = winner.config.territoryId;

            for (CellCandidate candidate : cell.contenders) {
                double score = competitionScore(candidate.cost, candidate.runtime.remainingLandPower);
                ConflictContender contender = new ConflictContender();
                contender.territoryInstanceId = candidate.runtime.config.id;
                contender.territoryId = candidate.runtime.config.territoryId;
                contender.cost = candidate.cost;
                contender.landPowerBefore = candidate.runtime.remainingLandPower;
                contender.score = score;
                contender.winner = candidate.runtime == winner;
                record.contenders.add(contender);

                TerritoryStats stats = candidate.runtime.result.stats;
                stats.conflict_cells++;
                if (candidate.runtime == winner) {
                    record.winningCost = candidate.cost;
                    record.winningScore = score;
                    stats.conflict_wins++;
                } else {
                    stats.conflict_losses++;
                }
            }

            for (CellCandidate candidate : cell.contenders) {
                if (candidate.runtime.remainingLandPower > 0) {
                    candidate.runtime.remainingLandPower = Math.max(0, candidate.runtime.remainingLandPower - LAND_POWER_CONFLICT_COST);
                    candidate.runtime.result.stats.land_power_spent += LAND_POWER_CONFLICT_COST;
                }
                candidate.runtime.result.stats.land_power_remaining = candidate.runtime.remainingLandPower;
            }
            record.landPowerSpent = LAND_POWER_CONFLICT_COST;
            report.conflicts.add(record);
        }
    }

    private static double[][] computeDistanceMapForFaction(
            TerritoryConfig config,
            ScanPixel[][] map,
            int[][] clusterMap,
            int globalMinX,
            int globalMinZ,
            int step,
            int coarseStep,
            RegionTerrainAggregate terrainAgg
    ) {
        int width = map.length;
        int height = map[0].length;
        double[][] distMap = new double[width][height];
        for (double[] row : distMap) Arrays.fill(row, Double.MAX_VALUE);

        int seedX = (config.seedCellX - globalMinX) / step;
        int seedZ = (config.seedCellZ - globalMinZ) / step;
        int[] snapped = snapSeedToRegion(seedX, seedZ, config.regionId, map, clusterMap, globalMinX, globalMinZ, step, coarseStep);
        int gx = snapped[0];
        int gz = snapped[1];
        if (gx < 0 || gz < 0 || gx >= width || gz >= height) return distMap;
        if (!isCellAllowedForRegion(gx, gz, config.regionId, map, clusterMap, globalMinX, globalMinZ, step, coarseStep)) {
            return distMap;
        }

        PriorityQueue<double[]> queue = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        queue.add(new double[]{0.0, gx, gz});
        distMap[gx][gz] = 0.0;

        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        double[] dirCosts = {1.0, 1.0, 1.0, 1.0, 1.414, 1.414, 1.414, 1.414};

        while (!queue.isEmpty()) {
            double[] current = queue.poll();
            double cost = current[0];
            int cx = (int) current[1];
            int cz = (int) current[2];
            if (cost > distMap[cx][cz]) continue;
            if (cost >= config.maxPower) continue;

            ScanPixel center = map[cx][cz];
            if (center == null) continue;

            for (int i = 0; i < dirs.length; i++) {
                int nx = cx + dirs[i][0];
                int nz = cz + dirs[i][1];
                if (nx < 0 || nz < 0 || nx >= width || nz >= height) continue;
                if (!isCellAllowedForRegion(nx, nz, config.regionId, map, clusterMap, globalMinX, globalMinZ, step, coarseStep)) {
                    continue;
                }
                ScanPixel neighbor = map[nx][nz];
                if (neighbor == null) continue;

                double slope;
                double landRatio;
                if (terrainAgg != null && terrainAgg.hasData[nx][nz]) {
                    slope = terrainAgg.slopeAvg[nx][nz];
                    landRatio = terrainAgg.landRatio[nx][nz];
                } else {
                    double hDiff = Math.abs(neighbor.height() - center.height());
                    slope = hDiff / 16.0;
                    landRatio = neighbor.isLand() ? 1.0 : 0.0;
                }

                double moveCost = 1.0 + (slope * config.mountainCost);
                double waterFactor = 1.0 + (1.0 - landRatio) * (config.waterCost - 1.0);
                double newCost = cost + (moveCost * waterFactor * dirCosts[i]);
                if (newCost <= config.maxPower && newCost < distMap[nx][nz]) {
                    distMap[nx][nz] = newCost;
                    queue.add(new double[]{newCost, nx, nz});
                }
            }
        }

        return distMap;
    }

    private static FactionRuntime resolveCompetition(List<CellCandidate> contenders) {
        FactionRuntime best = null;
        double bestScore = Double.MAX_VALUE;
        for (CellCandidate contender : contenders) {
            double score = competitionScore(contender.cost, contender.runtime.remainingLandPower);
            if (best == null || score < bestScore - 1e-9) {
                best = contender.runtime;
                bestScore = score;
                continue;
            }
            if (Math.abs(score - bestScore) <= 1e-9) {
                if (contender.runtime.blueprintOrder < best.blueprintOrder) {
                    best = contender.runtime;
                    bestScore = score;
                } else if (contender.runtime.blueprintOrder == best.blueprintOrder
                        && contender.runtime.config.id.compareTo(best.config.id) < 0) {
                    best = contender.runtime;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    static double competitionScore(double cost, int remainingLandPower) {
        return cost - (Math.max(0, remainingLandPower) * LAND_POWER_SCORE_WEIGHT);
    }

    private static void assignCell(TerritoryResult result, int gx, int gz, int minX, int minZ, int step) {
        if (result == null || result.config == null) return;
        globalOwnershipMap[gx][gz] = result.config.id;
        claimArea(result.claimedChunks, gx, gz, minX, minZ, step);
    }

    private static boolean isCellAllowedForRegion(
            int gx,
            int gz,
            int regionId,
            ScanPixel[][] map,
            int[][] clusterMap,
            int globalMinX,
            int globalMinZ,
            int step,
            int clusterStep
    ) {
        if (gx < 0 || gz < 0 || gx >= map.length || gz >= map[0].length) return false;
        ScanPixel p = map[gx][gz];
        if (p == null || !p.isLand()) return false;
        int worldX = globalMinX + gx * step + step / 2;
        int worldZ = globalMinZ + gz * step + step / 2;
        int clusterId = readClusterAtWorld(worldX, worldZ, clusterMap, globalMinX, globalMinZ, clusterStep);
        if (clusterId == regionId) return true;
        return isCellAllowedByRegionCache(regionId, worldX, worldZ);
    }

    private static int[] snapSeedToRegion(
            int gx,
            int gz,
            int regionId,
            ScanPixel[][] map,
            int[][] clusterMap,
            int globalMinX,
            int globalMinZ,
            int step,
            int clusterStep
    ) {
        int width = map.length;
        int height = map[0].length;
        int clampedX = Math.max(0, Math.min(width - 1, gx));
        int clampedZ = Math.max(0, Math.min(height - 1, gz));
        if (isCellAllowedForRegion(clampedX, clampedZ, regionId, map, clusterMap, globalMinX, globalMinZ, step, clusterStep)) {
            return new int[]{clampedX, clampedZ};
        }

        int bestX = clampedX;
        int bestZ = clampedZ;
        int bestDistSq = Integer.MAX_VALUE;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                if (!isCellAllowedForRegion(x, z, regionId, map, clusterMap, globalMinX, globalMinZ, step, clusterStep)) {
                    continue;
                }
                int dx = x - clampedX;
                int dz = z - clampedZ;
                int distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        return new int[]{bestX, bestZ};
    }

    private static ExpansionGrid buildExpansionGrid(ScanResultHolder holder) {
        ScanPixel[][] coarseMap = holder.lastScanData;
        int coarseStep = Math.max(1, holder.scanStep);
        int targetStep = selectExpansionStep(holder, coarseStep);
        int radiusBlocks = holder.scanRadiusChunks * 16;
        int globalMinX = -radiusBlocks;
        int globalMinZ = -radiusBlocks;
        int span = radiusBlocks * 2;
        int width = Math.max(1, span / targetStep);
        int height = Math.max(1, span / targetStep);

        ScanPixel[][] map = new ScanPixel[width][height];
        paintCoarseFallback(coarseMap, map, globalMinX, globalMinZ, coarseStep, targetStep);
        paintDetailCaches(holder, map, globalMinX, globalMinZ, targetStep);
        return new ExpansionGrid(map, targetStep, globalMinX, globalMinZ, coarseStep);
    }

    private static int selectExpansionStep(ScanResultHolder holder, int coarseStep) {
        if (holder.regionCacheMap == null || holder.regionCacheMap.isEmpty()) return coarseStep;
        for (RegionCache cache : holder.regionCacheMap.values()) {
            if (cache == null || cache.detailData == null) continue;
            int step = Math.max(1, cache.step);
            if (step <= HIGH_RES_EXPANSION_STEP) return HIGH_RES_EXPANSION_STEP;
        }
        return coarseStep;
    }

    private static void paintCoarseFallback(
            ScanPixel[][] coarseMap,
            ScanPixel[][] out,
            int globalMinX,
            int globalMinZ,
            int coarseStep,
            int targetStep
    ) {
        if (coarseMap == null || coarseMap.length == 0 || coarseMap[0] == null) return;
        int factor = Math.max(1, coarseStep / targetStep);
        for (int i = 0; i < coarseMap.length; i++) {
            for (int j = 0; j < coarseMap[0].length; j++) {
                ScanPixel p = coarseMap[i][j];
                if (p == null) continue;
                int gx = (p.x() - globalMinX) / targetStep;
                int gz = (p.z() - globalMinZ) / targetStep;
                paintCell(out, gx, gz, factor, p);
            }
        }
    }

    private static void paintDetailCaches(
            ScanResultHolder holder,
            ScanPixel[][] out,
            int globalMinX,
            int globalMinZ,
            int targetStep
    ) {
        if (holder.regionCacheMap == null || holder.regionCacheMap.isEmpty()) return;
        for (RegionCache cache : holder.regionCacheMap.values()) {
            if (cache == null || cache.detailData == null) continue;
            int cacheStep = Math.max(1, cache.step);
            int factor = (cacheStep % targetStep == 0) ? Math.max(1, cacheStep / targetStep) : 1;
            ScanPixel[][] data = cache.detailData;
            for (int i = 0; i < data.length; i++) {
                for (int j = 0; j < data[0].length; j++) {
                    ScanPixel p = data[i][j];
                    if (p == null) continue;
                    int gx = (p.x() - globalMinX) / targetStep;
                    int gz = (p.z() - globalMinZ) / targetStep;
                    paintCell(out, gx, gz, factor, p);
                }
            }
        }
    }

    private static void paintCell(ScanPixel[][] out, int gx, int gz, int factor, ScanPixel value) {
        if (value == null || factor <= 0) return;
        for (int dx = 0; dx < factor; dx++) {
            for (int dz = 0; dz < factor; dz++) {
                int x = gx + dx;
                int z = gz + dz;
                if (x < 0 || z < 0 || x >= out.length || z >= out[0].length) continue;
                out[x][z] = value;
            }
        }
    }

    private static int readClusterAtWorld(
            int worldX,
            int worldZ,
            int[][] clusterMap,
            int globalMinX,
            int globalMinZ,
            int clusterStep
    ) {
        if (clusterMap == null || clusterMap.length == 0 || clusterMap[0] == null) return -1;
        int step = Math.max(1, clusterStep);
        int gx = (worldX - globalMinX) / step;
        int gz = (worldZ - globalMinZ) / step;
        if (gx < 0 || gz < 0 || gx >= clusterMap.length || gz >= clusterMap[0].length) return -1;
        return clusterMap[gx][gz];
    }

    private static boolean isCellAllowedByRegionCache(int regionId, int worldX, int worldZ) {
        RegionCache cache = ScanResultHolder.get().regionCacheMap.get(regionId);
        if (cache == null || cache.detailData == null || cache.step <= 0) return false;
        int gx = (worldX - cache.minX) / cache.step;
        int gz = (worldZ - cache.minZ) / cache.step;
        if (gx < 0 || gz < 0 || gx >= cache.detailData.length || gz >= cache.detailData[0].length) return false;
        ScanPixel pixel = cache.detailData[gx][gz];
        return pixel != null && pixel.isLand();
    }

    private static Map<Integer, RegionTerrainAggregate> buildRegionTerrainAggregates(
            ScanResultHolder holder,
            int width,
            int height,
            int globalMinX,
            int globalMinZ,
            int step
    ) {
        if (holder.regionCacheMap.isEmpty()) return Collections.emptyMap();
        Map<Integer, RegionTerrainAggregate> aggregates = new HashMap<>();
        for (Map.Entry<Integer, RegionCache> entry : holder.regionCacheMap.entrySet()) {
            RegionCache cache = entry.getValue();
            if (cache == null || cache.detailData == null) continue;
            ScanPixel[][] data = cache.detailData;
            double[][] slopeSum = new double[width][height];
            int[][] landCount = new int[width][height];
            int[][] sampleCount = new int[width][height];

            for (int i = 0; i < data.length; i++) {
                for (int j = 0; j < data[0].length; j++) {
                    ScanPixel p = data[i][j];
                    if (p == null) continue;
                    int gx = (p.x() - globalMinX) / step;
                    int gz = (p.z() - globalMinZ) / step;
                    if (gx < 0 || gx >= width || gz < 0 || gz >= height) continue;
                    sampleCount[gx][gz]++;
                    if (p.isLand()) landCount[gx][gz]++;

                    double slope = 0.0;
                    if (p.isLand()) {
                        double raw;
                        if (cache.slopeData != null && i < cache.slopeData.length && j < cache.slopeData[0].length) {
                            raw = cache.slopeData[i][j];
                        } else if (i + 1 < data.length && j + 1 < data[0].length) {
                            ScanPixel px = data[i + 1][j];
                            ScanPixel pz = data[i][j + 1];
                            if (px != null && pz != null) {
                                double dx = Math.abs(p.height() - px.height());
                                double dz = Math.abs(p.height() - pz.height());
                                raw = Math.sqrt(dx * dx + dz * dz);
                            } else {
                                raw = 0.0;
                            }
                        } else {
                            raw = 0.0;
                        }
                        slope = raw;
                    }
                    slopeSum[gx][gz] += slope;
                }
            }

            double[][] slopeAvg = new double[width][height];
            double[][] landRatio = new double[width][height];
            boolean[][] hasData = new boolean[width][height];
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < height; z++) {
                    int count = sampleCount[x][z];
                    if (count == 0) continue;
                    hasData[x][z] = true;
                    slopeAvg[x][z] = slopeSum[x][z] / count;
                    landRatio[x][z] = (double) landCount[x][z] / count;
                }
            }
            aggregates.put(entry.getKey(), new RegionTerrainAggregate(slopeAvg, landRatio, hasData));
        }
        return aggregates;
    }

    private static void analyzeTerritories(ScanResultHolder holder) {
        ScanPixel[][] map = expansionScanMap != null ? expansionScanMap : holder.lastScanData;
        int[][] clusterMap = holder.lastClusterMap;
        if (globalOwnershipMap == null || map == null) return;

        int step = Math.max(1, expansionStepBlocks);
        int globalMinX = expansionMinX;
        int globalMinZ = expansionMinZ;
        int clusterStep = Math.max(1, holder.scanStep);
        int width = Math.min(map.length, globalOwnershipMap.length);
        int height = Math.min(map[0].length, globalOwnershipMap[0].length);

        Map<String, TerritoryStats> tempStats = new HashMap<>();
        for (String id : results.keySet()) {
            TerritoryResult result = results.get(id);
            TerritoryStats base = result != null && result.stats != null ? result.stats : new TerritoryStats();
            tempStats.put(id, base);
        }

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                String owner = globalOwnershipMap[x][z];
                if (owner == null) continue;
                ScanPixel p = map[x][z];
                if (p == null) continue;

                TerritoryStats stat = tempStats.get(owner);
                if (stat == null) continue;
                stat.area_pixels++;

                int wx = globalMinX + x * step;
                int wz = globalMinZ + z * step;
                if (wx < stat.minX) stat.minX = wx;
                if (wx > stat.maxX) stat.maxX = wx;
                if (wz < stat.minZ) stat.minZ = wz;
                if (wz > stat.maxZ) stat.maxZ = wz;

                stat.rawBiomeCounts.merge(p.biomeId(), 1, Integer::sum);
                int regionId = readClusterAtWorld(wx + step / 2, wz + step / 2, clusterMap, globalMinX, globalMinZ, clusterStep);
                if (regionId > 0) stat.rawContinentCounts.merge(regionId, 1, Integer::sum);

                checkNeighbor(x + 1, z, owner, width, height, stat);
                checkNeighbor(x - 1, z, owner, width, height, stat);
                checkNeighbor(x, z + 1, owner, width, height, stat);
                checkNeighbor(x, z - 1, owner, width, height, stat);
            }
        }

        for (String id : results.keySet()) {
            TerritoryResult result = results.get(id);
            TerritoryStats stat = tempStats.get(id);
            if (result == null || stat == null) continue;
            long total = Math.max(1, stat.area_pixels);
            stat.rawBiomeCounts.forEach((biomeId, count) -> {
                double pct = (double) count / total;
                if (pct > 0.05) stat.biome_composition.put(biomeId, round3(pct));
            });
            stat.rawContinentCounts.forEach((continentId, count) -> {
                double pct = (double) count / total;
                stat.continent_distribution.put(continentId, round3(pct));
            });
            result.stats = stat;
        }
        System.out.println("[Territory] Analysis complete.");
    }

    private static void checkNeighbor(int x, int z, String myOwner, int width, int height, TerritoryStats stat) {
        if (x < 0 || x >= width || z < 0 || z >= height) return;
        String neighbor = globalOwnershipMap[x][z];
        if (neighbor != null && !neighbor.equals(myOwner)) {
            stat.neighborIds.add(neighbor);
        }
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static void claimArea(Set<Long> chunks, int gx, int gz, int minX, int minZ, int step) {
        int startWorldX = minX + gx * step;
        int startWorldZ = minZ + gz * step;
        int cX1 = startWorldX >> 4;
        int cZ1 = startWorldZ >> 4;
        int cX2 = (startWorldX + step - 1) >> 4;
        int cZ2 = (startWorldZ + step - 1) >> 4;
        for (int x = cX1; x <= cX2; x++) {
            for (int z = cZ1; z <= cZ2; z++) {
                chunks.add(ChunkPos.asLong(x, z));
            }
        }
    }

    private static void fillEnclaves(String[][] ownershipMap, int width, int height, int minX, int minZ, int step) {
        boolean[][] visited = new boolean[width][height];
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                if (ownershipMap[i][j] != null || visited[i][j]) continue;
                List<int[]> holePixels = new ArrayList<>();
                Set<String> neighborFactions = new HashSet<>();
                Queue<int[]> queue = new ArrayDeque<>();
                queue.add(new int[]{i, j});
                visited[i][j] = true;
                holePixels.add(new int[]{i, j});
                boolean touchesEdge = false;

                while (!queue.isEmpty()) {
                    int[] current = queue.poll();
                    for (int[] dir : dirs) {
                        int nx = current[0] + dir[0];
                        int nz = current[1] + dir[1];
                        if (nx < 0 || nz < 0 || nx >= width || nz >= height) {
                            touchesEdge = true;
                            continue;
                        }
                        String owner = ownershipMap[nx][nz];
                        if (owner == null) {
                            if (!visited[nx][nz]) {
                                visited[nx][nz] = true;
                                holePixels.add(new int[]{nx, nz});
                                queue.add(new int[]{nx, nz});
                            }
                        } else {
                            neighborFactions.add(owner);
                        }
                    }
                }

                if (!touchesEdge && neighborFactions.size() == 1) {
                    String sovereignId = neighborFactions.iterator().next();
                    TerritoryResult result = results.get(sovereignId);
                    if (result == null) continue;
                    for (int[] pixel : holePixels) {
                        claimArea(result.wildChunks, pixel[0], pixel[1], minX, minZ, step);
                    }
                }
            }
        }
    }

    public static boolean isChunkOwnedBy(long chunkKey, String territoryId) {
        if (territoryId == null) return false;
        TerritoryResult result = results.get(territoryId);
        if (result == null) return false;
        if (result.claimedChunks.contains(chunkKey)) return true;
        return result.wildChunks.contains(chunkKey);
    }

    public static boolean isChunkWithinSovereignty(long chunkKey, String territoryId) {
        return isChunkOwnedBy(chunkKey, territoryId);
    }

    private static void save() {
        try {
            Files.writeString(CONFIG_FILE.toPath(), GSON.toJson(registeredFactions));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void load() {
        if (!CONFIG_FILE.exists()) return;
        try {
            List<TerritoryConfig> list = GSON.fromJson(Files.readString(CONFIG_FILE.toPath()), new TypeToken<List<TerritoryConfig>>() {}.getType());
            registeredFactions.clear();
            if (list != null) {
                int fallbackOrder = 0;
                for (TerritoryConfig cfg : list) {
                    if (cfg == null) continue;
                    if (cfg.territoryId == null || cfg.territoryId.isBlank()) cfg.territoryId = cfg.id;
                    if (cfg.selectedContinentId <= 0) cfg.selectedContinentId = cfg.regionId;
                    if (cfg.seedCellX == 0 && cfg.seedCellZ == 0) {
                        cfg.seedCellX = cfg.capitalX;
                        cfg.seedCellZ = cfg.capitalZ;
                    }
                    if (cfg.landPower <= 0) cfg.landPower = Math.max(1, (int) Math.round(Math.max(1, cfg.maxPower) * 0.35));
                    if (cfg.blueprintOrder <= 0) cfg.blueprintOrder = fallbackOrder++;
                    registeredFactions.add(cfg);
                }
                pruneLegacyDuplicates(registeredFactions);
                registeredFactions.sort(Comparator
                        .comparingInt((TerritoryConfig cfg) -> cfg.blueprintOrder)
                        .thenComparing(cfg -> cfg.id == null ? "" : cfg.id));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void pruneLegacyDuplicates(List<TerritoryConfig> configs) {
        Set<String> scopedInstances = new HashSet<>();
        for (TerritoryConfig cfg : configs) {
            if (cfg == null || cfg.id == null || !cfg.id.contains("@c")) continue;
            scopedInstances.add(cfg.territoryId + "#" + cfg.regionId);
        }
        if (scopedInstances.isEmpty()) return;
        configs.removeIf(cfg ->
                cfg != null
                        && cfg.id != null
                        && !cfg.id.contains("@c")
                        && scopedInstances.contains(cfg.territoryId + "#" + cfg.regionId)
        );
    }
}
