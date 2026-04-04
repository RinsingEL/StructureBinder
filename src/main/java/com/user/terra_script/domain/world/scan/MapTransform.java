package com.user.terra_script.domain.world.scan;


public class MapTransform {

    private final int originX;
    private final int originZ;
    private final float scale;

    public MapTransform(int originX, int originZ, float scale) {
        this.originX = originX;
        this.originZ = originZ;
        this.scale = scale;
    }

    public int worldToScreenX(int worldX) {
        return Math.round((worldX - originX) * scale);
    }

    public int worldToScreenZ(int worldZ) {
        return Math.round((worldZ - originZ) * scale);
    }

    public int blockToScreenX(int chunkX) {
        return worldToScreenX(chunkX * 16);
    }

    public int blockToScreenZ(int chunkZ) {
        return worldToScreenZ(chunkZ * 16);
    }

    public int chunkPixelSize() {
        return Math.round(16 * scale);
    }
}

