"""Structure classification taxonomy (Specialty, Common, Infrastructure, Landscape)."""

CATEGORIES = [
    {"id": "specialty", "label": "文化特色"},
    {"id": "common", "label": "通用功能"},
    {"id": "infrastructure", "label": "基础设施"},
    {"id": "landscape", "label": "自然景观"},
]

ACTIVE_CATALOG_DIRS = {
    "S13_chinese_timber",
    "R01_elven_reborn",
    "R02_arcane_reborn",
    "R03_dwarven_reborn",
    "R04_european_reborn",
    "03_desert_stars",
}

# Explicit specialty model families / ids across the ready civilizations
SPECIALTY_PREFIXES = {
    # 中式木构: 宫殿、书院、宗祠、庙宇、戏台院、园林
    "CH-13", "CH-16", "CH-17", "CH-18", "CH-21", "CH-22",
    # 欧洲中世纪新制: 城门主堡/城堡、十字钟塔教堂、修道院、城镇钟楼
    "EU-01", "EU-02", "EU-13", "EU-16",
    # 精灵新制: 白枝议庭、月泉圣所、悬廊藏卷馆、林间艺学堂、双塔王庭、永歌纪念庭、灵木疗养庭
    "EL-01", "EL-02", "EL-03", "EL-04", "EL-13", "EL-14", "EL-16",
    # 矮人新制: 山砧氏族殿、高炉锻造院、祖脉纪念殿、王座山门、矿山升降塔、地热浴堂
    "DV-01", "DV-02", "DV-04", "DV-13", "DV-15", "DV-16",
    # 魔法学院新制: 星穹礼堂、八瓣藏书馆、子午观星台、三院炼金实验所、三环传送厅、愈疗圣堂、四柱晶核工坊
    "MG-01", "MG-02", "MG-03", "MG-04", "MG-14", "MG-15", "MG-16",
    # 沙漠绿洲: 星象台、地下蓄水厅、半埋旧驿站遗址、古井管理所
    "DS-03", "DS-10", "DS-11", "DS-12",
}


def resolve_category(meta: dict) -> str:
    """Determine structure category: specialty, infrastructure, landscape, or common."""
    asset_tags = meta.get("asset_tags") or []
    if "infrastructure" in asset_tags:
        return "infrastructure"
    if "landscape" in asset_tags:
        return "landscape"

    asset_id = meta.get("id", "")
    family = meta.get("family", "")

    # Check specialty markers
    for prefix in SPECIALTY_PREFIXES:
        if asset_id.startswith(prefix) or family.startswith(prefix):
            return "specialty"

    return "common"
