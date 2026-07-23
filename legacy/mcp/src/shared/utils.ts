export function pickFirst(obj: any, keys: string[]) {
  if (!obj) return undefined;
  for (const key of keys) {
    if (obj[key] !== undefined) return obj[key];
  }
  return undefined;
}

export function compactObject(obj: Record<string, any>) {
  const compact: Record<string, any> = {};
  for (const [key, value] of Object.entries(obj)) {
    if (value !== undefined) compact[key] = value;
  }
  return compact;
}

export function normalizeDensityValue(value: any) {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") return value;
  const normalized = value.trim().toLowerCase();
  if (normalized === "medium") return "mid";
  return normalized;
}

export function normalizeWallConfig(raw: any) {
  if (!raw || typeof raw !== "object") return undefined;
  const wall = compactObject({
    type: pickFirst(raw, ["type", "类型"]),
    thickness_blocks: pickFirst(raw, ["thickness_blocks", "厚度方块"]),
    gate_count: pickFirst(raw, ["gate_count", "城门数量"]),
  });
  return Object.keys(wall).length > 0 ? wall : undefined;
}

export function normalizeLayerConfigs(raw: any) {
  if (!Array.isArray(raw)) return undefined;
  const layers: any[] = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== "object") continue;
    const wallRaw = pickFirst(entry, ["wall", "墙体"]);
    const densityRaw = pickFirst(entry, ["density", "功能密度"]);
    const layer = compactObject({
      name: pickFirst(entry, ["name", "层名"]),
      type: pickFirst(entry, ["type", "层类型"]),
      density: normalizeDensityValue(densityRaw),
      weight: pickFirst(entry, ["weight", "权重"]),
      ecology: pickFirst(entry, ["ecology", "生态策略", "ecology_policy"]),
      is_wall: pickFirst(entry, ["is_wall", "isWall", "是否城墙"]),
      wall_layer: pickFirst(entry, ["wall_layer", "是否墙层"]),
      wall: normalizeWallConfig(wallRaw),
    });
    if (Object.keys(layer).length > 0) layers.push(layer);
  }
  return layers.length > 0 ? layers : undefined;
}

export function buildCreateCityPayload(args: any) {
  const layerCount = pickFirst(args, ["layer_count", "层级数量"]);
  const layerThresholds = pickFirst(args, ["layer_thresholds", "层级阈值"]);
  const layers = normalizeLayerConfigs(pickFirst(args, ["layers", "层配置"]));
  const payload: Record<string, any> = {
    territoryId: args.territory_id,
    continentId: args.continent_id,
    centerX: args.center_x,
    centerZ: args.center_z,
    targetChunkCount: args.target_chunk_count,
    allow_water_city: pickFirst(args, ["allow_water_city", "allowWaterCity"]),
    bias: pickFirst(args, ["bias", "扩张倾向"]) || "balanced",
    ecology: pickFirst(args, ["ecology", "ecology_policy", "生态策略"]) || "adaptive",
    density: normalizeDensityValue(args.density || "medium"),
  };
  if (layerCount !== undefined) payload["layer_count"] = layerCount;
  if (layerThresholds !== undefined) payload["layer_thresholds"] = layerThresholds;
  if (layers !== undefined) payload["layers"] = layers;
  return payload;
}

export function toFiniteNumber(value: any, fallback: number) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

export function clampNumber(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}
