package com.user.terra_script.world.city;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

public class CityStage1BinaryIO {
    private static final int VERSION = 1;

    public static File save(CityStage1Processor.Stage1Result result) throws Exception {
        File file = FMLPaths.GAMEDIR.get()
                .resolve("terra_script_city_stage1_" + result.cityId + ".dat")
                .toFile();
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(VERSION);
            out.writeInt(result.originX);
            out.writeInt(result.originZ);
            out.writeInt(result.width);
            out.writeInt(result.height);
            writeHeight(out, result.heightMap);
            writeDoubleGrid(out, result.slopeMap);
            writeDoubleGrid(out, result.roughnessMap);
            writeForbidden(out, result.forbidden);
            writeBuildableGroups(out, result.buildableGroups);
        }
        return file;
    }

    public static HeightData loadHeightData(String cityId) throws Exception {
        File file = FMLPaths.GAMEDIR.get()
                .resolve("terra_script_city_stage1_" + cityId + ".dat")
                .toFile();
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int version = in.readInt();
            if (version != VERSION) return null;
            int originX = in.readInt();
            int originZ = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            int[][] heightMap = readHeight(in, width, height);
            // Skip slope + roughness if present
            skipDoubleGrid(in, width, height);
            skipDoubleGrid(in, width, height);
            // Skip forbidden list
            skipForbidden(in);
            skipBuildableGroups(in);
            return new HeightData(originX, originZ, width, height, heightMap);
        }
    }

    public static List<CityStage1Processor.ForbiddenBlock> loadForbidden(String cityId) throws Exception {
        File file = FMLPaths.GAMEDIR.get()
                .resolve("terra_script_city_stage1_" + cityId + ".dat")
                .toFile();
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int version = in.readInt();
            if (version != VERSION) return null;
            int originX = in.readInt();
            int originZ = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            readHeight(in, width, height);
            skipDoubleGrid(in, width, height);
            skipDoubleGrid(in, width, height);
            return readForbidden(in);
        }
    }

    public static List<List<CityStage1Processor.BlockCoord>> loadBuildableGroups(String cityId) throws Exception {
        File file = FMLPaths.GAMEDIR.get()
                .resolve("terra_script_city_stage1_" + cityId + ".dat")
                .toFile();
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int version = in.readInt();
            if (version != VERSION) return null;
            int originX = in.readInt();
            int originZ = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            readHeight(in, width, height);
            skipDoubleGrid(in, width, height);
            skipDoubleGrid(in, width, height);
            skipForbidden(in);
            return readBuildableGroups(in);
        }
    }

    private static void writeHeight(DataOutputStream out, int[][] height) throws Exception {
        int w = height.length;
        int h = height[0].length;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                out.writeShort((short) height[x][z]);
            }
        }
    }

    private static int[][] readHeight(DataInputStream in, int w, int h) throws Exception {
        int[][] height = new int[w][h];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                height[x][z] = in.readShort();
            }
        }
        return height;
    }

    private static void writeDoubleGrid(DataOutputStream out, double[][] grid) throws Exception {
        int w = grid.length;
        int h = grid[0].length;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                out.writeFloat((float) grid[x][z]);
            }
        }
    }

    private static void skipDoubleGrid(DataInputStream in, int w, int h) throws Exception {
        int total = w * h;
        for (int i = 0; i < total; i++) {
            in.readFloat();
        }
    }

    private static void writeForbidden(DataOutputStream out, List<CityStage1Processor.ForbiddenBlock> forbidden) throws Exception {
        if (forbidden == null || forbidden.isEmpty()) {
            out.writeInt(0);
            return;
        }
        out.writeInt(forbidden.size());
        for (CityStage1Processor.ForbiddenBlock fb : forbidden) {
            out.writeInt(fb.x);
            out.writeInt(fb.z);
            out.writeUTF(fb.reason != null ? fb.reason : "");
        }
    }

    private static void skipForbidden(DataInputStream in) throws Exception {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            in.readInt();
            in.readInt();
            in.readUTF();
        }
    }

    private static List<CityStage1Processor.ForbiddenBlock> readForbidden(DataInputStream in) throws Exception {
        int count = in.readInt();
        List<CityStage1Processor.ForbiddenBlock> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            CityStage1Processor.ForbiddenBlock fb = new CityStage1Processor.ForbiddenBlock();
            fb.x = in.readInt();
            fb.z = in.readInt();
            fb.reason = in.readUTF();
            list.add(fb);
        }
        return list;
    }

    private static void writeBuildableGroups(DataOutputStream out, List<List<CityStage1Processor.BlockCoord>> groups) throws Exception {
        if (groups == null || groups.isEmpty()) {
            out.writeInt(0);
            return;
        }
        out.writeInt(groups.size());
        for (List<CityStage1Processor.BlockCoord> group : groups) {
            out.writeInt(group.size());
            for (CityStage1Processor.BlockCoord bc : group) {
                out.writeInt(bc.x);
                out.writeInt(bc.z);
            }
        }
    }

    private static void skipBuildableGroups(DataInputStream in) throws Exception {
        int groups = in.readInt();
        for (int i = 0; i < groups; i++) {
            int size = in.readInt();
            for (int j = 0; j < size; j++) {
                in.readInt();
                in.readInt();
            }
        }
    }

    private static List<List<CityStage1Processor.BlockCoord>> readBuildableGroups(DataInputStream in) throws Exception {
        int groups = in.readInt();
        List<List<CityStage1Processor.BlockCoord>> list = new java.util.ArrayList<>();
        for (int i = 0; i < groups; i++) {
            int size = in.readInt();
            List<CityStage1Processor.BlockCoord> group = new java.util.ArrayList<>();
            for (int j = 0; j < size; j++) {
                CityStage1Processor.BlockCoord bc = new CityStage1Processor.BlockCoord();
                bc.x = in.readInt();
                bc.z = in.readInt();
                group.add(bc);
            }
            list.add(group);
        }
        return list;
    }

    public static class HeightData {
        public final int originX;
        public final int originZ;
        public final int width;
        public final int height;
        public final int[][] heightMap;

        public HeightData(int originX, int originZ, int width, int height, int[][] heightMap) {
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.height = height;
            this.heightMap = heightMap;
        }
    }
}
