package com.user.terra_script.world.city.stage.c2;

import com.user.terra_script.domain.world.scan.ScanPixel;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public final class CityC2ScanBinaryIO {
    private static final int VERSION = 1;

    private CityC2ScanBinaryIO() {}

    public static File save(
            String cityId,
            int originX,
            int originZ,
            int widthBlocks,
            int heightBlocks,
            int step,
            ScanPixel[][] map
    ) throws Exception {
        if (cityId == null || cityId.isBlank() || map == null || map.length == 0 || map[0] == null) {
            throw new IllegalArgumentException("invalid C2 scan input");
        }
        File file = dataFile(cityId);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(VERSION);
            out.writeInt(originX);
            out.writeInt(originZ);
            out.writeInt(widthBlocks);
            out.writeInt(heightBlocks);
            out.writeInt(step);
            out.writeInt(map.length);
            out.writeInt(map[0].length);
            for (int x = 0; x < map.length; x++) {
                for (int z = 0; z < map[0].length; z++) {
                    ScanPixel p = map[x][z];
                    out.writeBoolean(p != null);
                    if (p == null) continue;
                    out.writeInt(p.height());
                    out.writeBoolean(p.isLand());
                    out.writeUTF(p.biomeId() == null ? "" : p.biomeId());
                    out.writeFloat(p.temperature());
                }
            }
        }
        return file;
    }

    public static C2ScanData load(String cityId) throws Exception {
        File file = dataFile(cityId);
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int version = in.readInt();
            if (version != VERSION) return null;
            int originX = in.readInt();
            int originZ = in.readInt();
            int widthBlocks = in.readInt();
            int heightBlocks = in.readInt();
            int step = in.readInt();
            int w = in.readInt();
            int h = in.readInt();
            ScanPixel[][] map = new ScanPixel[w][h];
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < h; z++) {
                    boolean has = in.readBoolean();
                    if (!has) continue;
                    int height = in.readInt();
                    boolean isLand = in.readBoolean();
                    String biome = in.readUTF();
                    float temp = in.readFloat();
                    int worldX = originX + x * step;
                    int worldZ = originZ + z * step;
                    map[x][z] = new ScanPixel(worldX, worldZ, height, biome, isLand, temp);
                }
            }
            return new C2ScanData(originX, originZ, widthBlocks, heightBlocks, step, map);
        }
    }

    public static File dataFile(String cityId) {
        return FMLPaths.GAMEDIR.get()
                .resolve("terra_script_city_c2_scan_" + cityId + ".dat")
                .toFile();
    }

    public static final class C2ScanData {
        public final int originX;
        public final int originZ;
        public final int widthBlocks;
        public final int heightBlocks;
        public final int step;
        public final ScanPixel[][] map;

        public C2ScanData(int originX, int originZ, int widthBlocks, int heightBlocks, int step, ScanPixel[][] map) {
            this.originX = originX;
            this.originZ = originZ;
            this.widthBlocks = widthBlocks;
            this.heightBlocks = heightBlocks;
            this.step = step;
            this.map = map;
        }
    }
}
