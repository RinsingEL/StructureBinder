package com.user.terra_script.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.world.TerritoryManager;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.CityProjectSnapshot;
import com.user.terra_script.world.city.CityStage1Processor;
import com.user.terra_script.world.city.district.District;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;

public class NationGenManager {
    public static class SnapshotManager {
        private static final String SNAPSHOT_FILE = "terra_script_city_project.json";
        private static final String SCAN_CACHE_FILE = "terra_script_cache.dat";
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        public static boolean hasSnapshot() {
            return getSnapshotFile().exists();
        }

        public static CityProjectSnapshot.FreezeResult freezeIfNotFrozen() {
            if (hasSnapshot()) {
                return new CityProjectSnapshot.FreezeResult(false, "already frozen");
            }
            ScanResultHolder holder = ScanResultHolder.get();
            if (holder.lastScanData == null) {
                return new CityProjectSnapshot.FreezeResult(false, "scan data missing");
            }
            try {
                CityProjectSnapshot snapshot = buildFromCurrent(holder);
                save(snapshot);
                return new CityProjectSnapshot.FreezeResult(true, "frozen");
            } catch (Exception e) {
                return new CityProjectSnapshot.FreezeResult(false, "freeze failed: " + e.getMessage());
            }
        }

        public static CityProjectSnapshot load() {
            File file = getSnapshotFile();
            if (!file.exists()) return null;
            try {
                return GSON.fromJson(Files.readString(file.toPath()), CityProjectSnapshot.class);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        private static CityProjectSnapshot buildFromCurrent(ScanResultHolder holder) {
            CityProjectSnapshot snapshot = new CityProjectSnapshot();
            snapshot.createdAtEpochMs = System.currentTimeMillis();
            snapshot.seedUsed = holder.seedUsed;
            snapshot.scan = buildScanMeta(holder);

            for (var res : TerritoryManager.getAllResults()) {
                CityProjectSnapshot.TerritorySnapshot ts = new CityProjectSnapshot.TerritorySnapshot();
                ts.id = res.config.id;
                ts.name = res.config.name;
                ts.regionId = res.config.regionId;
                ts.capitalX = res.config.capitalX;
                ts.capitalZ = res.config.capitalZ;
                ts.maxPower = res.config.maxPower;
                ts.mountainCost = res.config.mountainCost;
                ts.waterCost = res.config.waterCost;
                ts.color = res.config.color;
                if (res.stats != null) {
                    CityProjectSnapshot.TerritoryStatsSnapshot stats = new CityProjectSnapshot.TerritoryStatsSnapshot();
                    stats.areaPixels = res.stats.area_pixels;
                    stats.minX = res.stats.minX;
                    stats.maxX = res.stats.maxX;
                    stats.minZ = res.stats.minZ;
                    stats.maxZ = res.stats.maxZ;
                    stats.continentDistribution.putAll(res.stats.continent_distribution);
                    stats.biomeComposition.putAll(res.stats.biome_composition);
                    stats.neighbors.addAll(res.stats.neighborIds);
                    ts.stats = stats;
                }
                snapshot.territories.add(ts);
            }

            for (CityInstance city : CityManager.get().getAllCities()) {
                CityProjectSnapshot.CitySnapshot cs = new CityProjectSnapshot.CitySnapshot();
                cs.id = city.id;
                cs.config = copyCityConfig(city.config);

                city.claimedChunks.forEach((key, zone) -> {
                    CityProjectSnapshot.ClaimedChunk cc = new CityProjectSnapshot.ClaimedChunk();
                    cc.x = ChunkPos.getX(key);
                    cc.z = ChunkPos.getZ(key);
                    cc.zone = zone.name();
                    cs.claimedChunks.add(cc);
                });

                if (city.districts != null) {
                    for (District d : city.districts) {
                        CityProjectSnapshot.DistrictSnapshot ds = new CityProjectSnapshot.DistrictSnapshot();
                        ds.id = d.id;
                        ds.centerX = d.centerX;
                        ds.centerZ = d.centerZ;
                        ds.zoneType = d.zoneType;
                        for (long k : d.memberChunks) {
                            CityProjectSnapshot.ChunkCoord coord = new CityProjectSnapshot.ChunkCoord();
                            coord.x = ChunkPos.getX(k);
                            coord.z = ChunkPos.getZ(k);
                            ds.memberChunks.add(coord);
                        }
                        for (double[] v : d.polygonVertices) {
                            if (v == null || v.length < 2) continue;
                            CityProjectSnapshot.Point2D p = new CityProjectSnapshot.Point2D();
                            p.x = v[0];
                            p.z = v[1];
                            ds.polygon.add(p);
                        }
                        cs.districts.add(ds);
                    }
                }
                snapshot.cities.add(cs);
            }
            return snapshot;
        }

        private static CityProjectSnapshot.ScanMeta buildScanMeta(ScanResultHolder holder) {
            CityProjectSnapshot.ScanMeta meta = new CityProjectSnapshot.ScanMeta();
            meta.scanRadiusChunks = holder.scanRadiusChunks;
            meta.scanStep = holder.scanStep;

            File cacheFile = FMLPaths.GAMEDIR.get().resolve(SCAN_CACHE_FILE).toFile();
            meta.cacheFile = cacheFile.getName();
            meta.cacheSizeBytes = cacheFile.exists() ? cacheFile.length() : 0;
            meta.cacheLastModified = cacheFile.exists() ? cacheFile.lastModified() : 0;
            return meta;
        }

        private static CityProjectSnapshot.CityConfigSnapshot copyCityConfig(CityConfig config) {
            CityProjectSnapshot.CityConfigSnapshot cfg = new CityProjectSnapshot.CityConfigSnapshot();
            if (config == null) return cfg;
            cfg.territoryId = config.territoryId;
            cfg.continentId = config.continentId;
            cfg.centerX = config.centerX;
            cfg.centerZ = config.centerZ;
            cfg.targetChunkCount = config.targetChunkCount;
            cfg.bias = config.bias != null ? config.bias.name() : null;
            cfg.ecology = config.ecology != null ? config.ecology.name() : null;
            cfg.density = config.density;
            return cfg;
        }

        private static File getSnapshotFile() {
            return FMLPaths.GAMEDIR.get().resolve(SNAPSHOT_FILE).toFile();
        }

        private static void save(CityProjectSnapshot snapshot) throws Exception {
            Files.writeString(getSnapshotFile().toPath(), GSON.toJson(snapshot));
        }
    }

    public static class Stage1Manager {
        public static CityStage1Processor.Stage1Result computeAndSave(ServerLevel level, String cityId) throws Exception {
            CityInstance city = CityManager.get().getCity(cityId);
            if (city == null) return null;
            CityStage1Processor.Stage1Result result = CityStage1Processor.compute(level, city);
            CityStage1Processor.save(result);
            return result;
        }

        public static CityStage1Processor.Stage1Result load(String cityId) {
            try {
                File file = FMLPaths.GAMEDIR.get().resolve("terra_script_city_stage1_" + cityId + ".json").toFile();
                if (!file.exists()) return null;
                return new Gson().fromJson(Files.readString(file.toPath()), CityStage1Processor.Stage1Result.class);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }
}
