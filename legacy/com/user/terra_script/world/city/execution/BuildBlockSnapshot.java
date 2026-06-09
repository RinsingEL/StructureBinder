package com.user.terra_script.world.city.execution;

public final class BuildBlockSnapshot {
    private final String blockId;
    private final boolean air;
    private final boolean protectedBlock;
    private final String softClearReason;

    private BuildBlockSnapshot(String blockId, boolean air, boolean protectedBlock, String softClearReason) {
        this.blockId = blockId == null || blockId.isBlank() ? "unknown" : blockId;
        this.air = air;
        this.protectedBlock = protectedBlock;
        this.softClearReason = softClearReason;
    }

    public static BuildBlockSnapshot of(String blockId, boolean air, boolean protectedBlock, String softClearReason) {
        return new BuildBlockSnapshot(blockId, air, protectedBlock, softClearReason);
    }

    public static BuildBlockSnapshot airBlock() {
        return new BuildBlockSnapshot("minecraft:air", true, false, null);
    }

    public String blockId() {
        return blockId;
    }

    public boolean air() {
        return air;
    }

    public boolean protectedBlock() {
        return protectedBlock;
    }

    public boolean softClearable() {
        return softClearReason != null && !softClearReason.isBlank();
    }

    public String softClearReason() {
        return softClearReason;
    }
}
