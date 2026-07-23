package com.user.terra_script.world.city.stage.c2;

import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.district.District;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CityC3OwnershipIO {
    public static final String FILE_NAME = "C3_BlockOwnership.dat";
    private static final int VERSION = 1;

    private CityC3OwnershipIO() {}

    public static OwnershipData compute(CityInstance city) {
        if (city == null || city.claimedChunks == null || city.claimedChunks.isEmpty()) return null;
        if (city.districts == null || city.districts.isEmpty()) return null;

        Bounds bounds = computeBounds(city);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return null;

        List<District> districts = new ArrayList<>(city.districts);
        districts.sort(Comparator.comparingInt(d -> d.id));

        int[][] owner = new int[bounds.width][bounds.height];
        for (int x = 0; x < bounds.width; x++) {
            for (int z = 0; z < bounds.height; z++) {
                owner[x][z] = -1;
            }
        }

        for (Long chunkKey : city.claimedChunks.keySet()) {
            if (chunkKey == null) continue;
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int worldX = baseX + dx;
                    int worldZ = baseZ + dz;
                    int ix = worldX - bounds.minX;
                    int iz = worldZ - bounds.minZ;
                    if (ix < 0 || iz < 0 || ix >= bounds.width || iz >= bounds.height) continue;
                    owner[ix][iz] = nearestDistrictId(worldX, worldZ, districts);
                }
            }
        }

        return new OwnershipData(bounds.minX, bounds.minZ, bounds.width, bounds.height, 1, owner);
    }

    public static Path save(Path cityDir, OwnershipData data) throws Exception {
        if (cityDir == null || data == null || data.owner == null) return null;
        Files.createDirectories(cityDir);
        Path file = cityDir.resolve(FILE_NAME);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(VERSION);
            out.writeInt(data.originX);
            out.writeInt(data.originZ);
            out.writeInt(data.width);
            out.writeInt(data.height);
            out.writeInt(data.step);
            for (int x = 0; x < data.width; x++) {
                for (int z = 0; z < data.height; z++) {
                    out.writeInt(data.owner[x][z]);
                }
            }
        }
        return file;
    }

    public static OwnershipData load(Path cityDir) throws Exception {
        if (cityDir == null) return null;
        Path file = cityDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int version = in.readInt();
            if (version != VERSION) return null;
            int originX = in.readInt();
            int originZ = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            int step = in.readInt();
            int[][] owner = new int[width][height];
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < height; z++) {
                    owner[x][z] = in.readInt();
                }
            }
            return new OwnershipData(originX, originZ, width, height, step, owner);
        }
    }

    private static int nearestDistrictId(int worldX, int worldZ, List<District> districts) {
        int bestId = -1;
        double bestDist = Double.MAX_VALUE;
        for (District district : districts) {
            if (district == null) continue;
            double dx = worldX - district.centerX;
            double dz = worldZ - district.centerZ;
            double dist = dx * dx + dz * dz;
            if (dist < bestDist || (dist == bestDist && district.id < bestId)) {
                bestDist = dist;
                bestId = district.id;
            }
        }
        return bestId;
    }

    private static Bounds computeBounds(CityInstance city) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Long chunkKey : city.claimedChunks.keySet()) {
            if (chunkKey == null) continue;
            int cx = ChunkPos.getX(chunkKey);
            int cz = ChunkPos.getZ(chunkKey);
            int cMinX = cx << 4;
            int cMinZ = cz << 4;
            int cMaxX = cMinX + 15;
            int cMaxZ = cMinZ + 15;
            if (cMinX < minX) minX = cMinX;
            if (cMinZ < minZ) minZ = cMinZ;
            if (cMaxX > maxX) maxX = cMaxX;
            if (cMaxZ > maxZ) maxZ = cMaxZ;
        }
        if (minX > maxX || minZ > maxZ) return null;
        return new Bounds(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    }

    private static final class Bounds {
        final int minX;
        final int minZ;
        final int width;
        final int height;

        private Bounds(int minX, int minZ, int width, int height) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
        }
    }

    public static final class OwnershipData {
        public final int originX;
        public final int originZ;
        public final int width;
        public final int height;
        public final int step;
        public final int[][] owner;

        public OwnershipData(int originX, int originZ, int width, int height, int step, int[][] owner) {
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.height = height;
            this.step = step;
            this.owner = owner;
        }
    }
}
