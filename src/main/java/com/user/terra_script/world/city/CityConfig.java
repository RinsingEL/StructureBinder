package com.user.terra_script.world.city;

import com.google.gson.annotations.SerializedName;

public class CityConfig {
    // 基础归属
    public String territoryId;
    public int continentId;

    // 选址
    public int centerX; // Block X
    public int centerZ; // Block Z

    // 规模 (单位: Chunk)
    public int targetChunkCount;

    // 扩张偏好
    public enum ExpansionBias {
        @SerializedName("balanced") BALANCED,
        @SerializedName("north") NORTH,
        @SerializedName("south") SOUTH,
        @SerializedName("east") EAST,
        @SerializedName("west") WEST,
        @SerializedName("coastal") COASTAL, // 倾向于水边
        @SerializedName("inland") INLAND    // 倾向于远离水
    }
    public ExpansionBias bias = ExpansionBias.BALANCED;

    // 生态策略
    public enum EcologyPolicy {
        @SerializedName("preserve") PRESERVE, // 保留树木，建筑避让
        @SerializedName("adaptive") ADAPTIVE, // 部分清除
        @SerializedName("clear") CLEAR        // 强力推平
    }
    public EcologyPolicy ecology = EcologyPolicy.ADAPTIVE;

    // 密度与风格
    public String density = "medium"; // low, medium, high

    // 运行时生成的唯一ID (例如 "city_102_55")
    public transient String cityInstanceId;
}