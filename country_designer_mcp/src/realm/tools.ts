import type { ToolDefinition } from "../shared/types.js";

const decorationShapeSchema: Record<string, unknown> = {
  oneOf: [
    decorationVariant("target_mask", {}),
    decorationVariant("rectangle", {
      minU: integer("局部 U 最小值。"),
      minV: integer("局部 V 最小值。"),
      maxU: integer("局部 U 最大值。"),
      maxV: integer("局部 V 最大值。"),
    }, ["minU", "minV", "maxU", "maxV"]),
    decorationVariant("ellipse", {
      centerU: integer("局部 U 中心。"),
      centerV: integer("局部 V 中心。"),
      radiusU: positiveInteger("U 半径；与 radiusV 相等时为圆形。"),
      radiusV: positiveInteger("V 半径；与 radiusU 相等时为圆形。"),
    }, ["centerU", "centerV", "radiusU", "radiusV"]),
    decorationVariant("ring", {
      centerU: integer("局部 U 中心。"),
      centerV: integer("局部 V 中心。"),
      innerRadiusU: positiveInteger("U 内半径。"),
      innerRadiusV: positiveInteger("V 内半径。"),
      outerRadiusU: positiveInteger("U 外半径，必须大于内半径。"),
      outerRadiusV: positiveInteger("V 外半径，必须大于内半径。"),
    }, ["centerU", "centerV", "innerRadiusU", "innerRadiusV", "outerRadiusU", "outerRadiusV"]),
    decorationVariant("polygon", {
      vertices: {
        type: "array",
        minItems: 3,
        description: "局部 U/V 顶点；不是世界坐标。",
        items: strictObject({ u: integer("局部 U。"), v: integer("局部 V。") }, ["u", "v"]),
      },
    }, ["vertices"]),
  ],
};

const decorationPatternSchema: Record<string, unknown> = {
  oneOf: [
    decorationVariant("uniform_fill", {
      paletteSlotId: nonEmptyString("要填充的 content palette slot。"),
    }, ["paletteSlotId"]),
    decorationVariant("cross_section_repeat", {
      axis: { type: "string", enum: ["u", "v"] },
      offsetBlocks: integer("横断面相对局部原点偏移。"),
      bands: {
        type: "array",
        minItems: 1,
        items: strictObject({
          paletteSlotId: nonEmptyString("该带使用的 content palette slot。"),
          widthBlocks: positiveInteger("带宽。"),
        }, ["paletteSlotId", "widthBlocks"]),
      },
    }, ["axis", "offsetBlocks", "bands"]),
    decorationVariant("parallel_rows", {
      axis: { type: "string", enum: ["u", "v"] },
      paletteSlotId: nonEmptyString("行列使用的 content palette slot。"),
      rowWidthBlocks: positiveInteger("单行宽度。"),
      spacingBlocks: positiveInteger("行列周期，必须不小于行宽。"),
      offsetBlocks: integer("相对局部原点偏移。"),
    }, ["axis", "paletteSlotId", "rowWidthBlocks", "spacingBlocks", "offsetBlocks"]),
    decorationVariant("edge_repeat", {
      paletteSlotId: nonEmptyString("边缘节点使用的 content palette slot。"),
      spacingBlocks: positiveInteger("沿边间距。"),
      offsetBlocks: integer("沿边起始偏移。"),
    }, ["paletteSlotId", "spacingBlocks", "offsetBlocks"]),
    decorationVariant("grid_repeat", {
      paletteSlotId: nonEmptyString("网格节点使用的 content palette slot。"),
      spacingUBlocks: positiveInteger("U 方向间距。"),
      spacingVBlocks: positiveInteger("V 方向间距。"),
      offsetUBlocks: integer("U 方向偏移。"),
      offsetVBlocks: integer("V 方向偏移。"),
    }, ["paletteSlotId", "spacingUBlocks", "spacingVBlocks", "offsetUBlocks", "offsetVBlocks"]),
    decorationVariant("deterministic_scatter", {
      paletteSlotId: nonEmptyString("散点使用的 content palette slot。"),
      cellSizeBlocks: positiveInteger("确定性散点网格尺寸。"),
      densityPermille: { type: "integer", minimum: 0, maximum: 1000, description: "千分比密度。" },
    }, ["paletteSlotId", "cellSizeBlocks", "densityPermille"]),
  ],
};

const decorationContentPaletteSchema = strictObject({
  slots: {
    type: "array",
    minItems: 1,
    items: strictObject({
      slotId: nonEmptyString("Pattern 引用的稳定 slot ID。"),
      layers: {
        type: "array",
        minItems: 1,
        items: strictObject({
          layerId: nonEmptyString("同一 palette slot 内稳定且唯一的 layer ID。"),
          phase: { type: "string", enum: ["skeleton", "surface", "major", "minor"] },
          entries: {
            type: "array",
            minItems: 1,
            description: "填写当前 style profile 暴露的语义槽位；不填写具体 NBT/content ID。",
            items: strictObject({
              contentRef: nonEmptyString("来自 city_query_decoration_catalog 的 semanticRefs。"),
              weight: { type: "number", exclusiveMinimum: 0 },
            }, ["contentRef", "weight"]),
          },
          required: { type: "boolean" },
          dependsOnLayerId: nonEmptyString("可选；只允许依赖同一 slot 内更早声明的 layer。"),
        }, ["layerId", "phase", "entries", "required"]),
      },
    }, ["slotId", "layers"]),
  },
}, ["slots"]);

const decorationProgramPlanSchema = strictObject({
  schema: { type: "string", enum: ["city_decoration_program_plan"] },
  cityId: nonEmptyString("必须与 citySeedId 对应的 City 一致。"),
  catalogHash: nonEmptyString("city_query_decoration_catalog 返回的当前 catalogHash。"),
  styleProfileId: nonEmptyString("city_query_decoration_catalog 返回的 styleProfileId。"),
  styleProfileHash: nonEmptyString("所选 styleProfile 对应的 styleProfileHash。"),
  programs: {
    type: "array",
    minItems: 1,
    items: strictObject({
      programId: nonEmptyString("稳定 program ID。"),
      targetArea: strictObject({
        sourceType: {
          type: "string",
          enum: ["patch", "land_use_area"],
          description: "引用 D3 patch 或已完成规划的 LandUse area；不得提交 targetBounds/memberBounds。",
        },
        ref: nonEmptyString("D3 landform patch ref 或 LandUse areaId。"),
        insetBlocks: { type: "integer", minimum: 0 },
      }, ["sourceType", "ref", "insetBlocks"]),
      coordinateFrame: strictObject({
        originMode: { type: "string", enum: ["target_centroid"] },
        orientationMode: {
          type: "string",
          enum: ["world_x", "world_z", "patch_long_axis", "area_long_axis", "target_long_axis"],
        },
        quarterTurns: { type: "integer", minimum: 0, maximum: 3 },
        offsetUBlocks: integer("局部 U 偏移。"),
        offsetVBlocks: integer("局部 V 偏移。"),
      }, ["originMode", "orientationMode", "quarterTurns", "offsetUBlocks", "offsetVBlocks"]),
      shape: decorationShapeSchema,
      pattern: decorationPatternSchema,
      contentPalette: decorationContentPaletteSchema,
      terrainPolicy: strictObject({
        maxSlopeDelta: { type: "integer", minimum: 0 },
        allowWater: { type: "boolean" },
        invalidTerrainAction: { type: "string", enum: ["skip", "clip"] },
        maxContinuousDropBlocks: { type: "integer", minimum: 0 },
        continuousDropWindowBlocks: { type: "integer", minimum: 1 },
        foundationMode: { type: "string", enum: ["none", "fill_only"] },
        maxFoundationDepthBlocks: { type: "integer", minimum: 0 },
        foundationShoulderBlocks: { type: "integer", minimum: 0 },
      }, ["maxSlopeDelta", "allowWater", "invalidTerrainAction", "maxContinuousDropBlocks",
        "continuousDropWindowBlocks", "foundationMode", "maxFoundationDepthBlocks", "foundationShoulderBlocks"]),
      conflictPolicy: strictObject({
        onConflict: { type: "string", enum: ["skip", "replace_lower_priority"] },
        clearanceBlocks: { type: "integer", minimum: 0 },
      }, ["onConflict", "clearanceBlocks"]),
      priority: integer("跨 program 优先级，高值先执行。"),
      seed: integer("全局固定随机种子；使用 JavaScript 安全整数。"),
    }, ["programId", "targetArea", "coordinateFrame", "shape", "pattern", "contentPalette",
      "terrainPolicy", "conflictPolicy", "priority", "seed"]),
  },
}, ["schema", "cityId", "catalogHash", "styleProfileId", "styleProfileHash", "programs"]);

const landUseIntentPlanSchema = strictObject({
  schema: { type: "string", enum: ["city_land_use_intent_plan"] },
  cityId: nonEmptyString("必须与 citySeedId 对应的 City 一致。"),
  seedSalt: nonEmptyString("可选确定性扰动盐；相同输入与 seedSalt 必须得到相同结果。"),
  groupOverrides: {
    type: "array",
    description: "把 D4 anchors 显式归为同一土地使用主体；不提交面积、行动力或成本参数。",
    items: strictObject({
      groupId: nonEmptyString("稳定的土地使用 group ID。"),
      memberAnchorIds: {
        type: "array",
        minItems: 1,
        uniqueItems: true,
        items: nonEmptyString("D6 locked plan 中存在的 anchorId。"),
      },
      ruleRef: nonEmptyString("可选 LandUseRuleCatalog 规则引用；省略时由成员结构语义解析。"),
    }, ["groupId", "memberAnchorIds"]),
  },
  subjectOverrides: {
    type: "array",
    description: "对已解析的 group 或独立 anchor 设置规则或排除；后项不得提交裸数值参数。",
    items: {
      oneOf: [
        strictObject({
          targetType: { type: "string", enum: ["group", "anchor"] },
          targetId: nonEmptyString("目标 groupId 或 anchorId。"),
          mode: { type: "string", enum: ["set_rule"] },
          ruleRef: nonEmptyString("LandUseRuleCatalog 中存在的规则引用。"),
        }, ["targetType", "targetId", "mode", "ruleRef"]),
        strictObject({
          targetType: { type: "string", enum: ["group", "anchor"] },
          targetId: nonEmptyString("目标 groupId 或 anchorId。"),
          mode: { type: "string", enum: ["exclude"] },
        }, ["targetType", "targetId", "mode"]),
      ],
    },
  },
  surfaceAlgorithmDefaults: {
    type: "array",
    description: "按刷地算法提供本次城市的材料默认值；同一算法最多一项。",
    items: strictObject({
      surfaceAlgorithm: { type: "string", enum: ["uniform", "contour_bands"] },
      surfaceBlockId: nonEmptyString("合法 Minecraft block ID。"),
      cropBlockId: nonEmptyString("可选作物 block ID。"),
      channelBankBlockId: nonEmptyString("可选沟渠岸体 block ID。"),
      channelWaterBlockId: nonEmptyString("可选沟渠水体 block ID。"),
      channelBankOverlayBlockId: nonEmptyString("可选沟渠岸边覆盖 block ID。"),
    }, ["surfaceAlgorithm", "surfaceBlockId"]),
  },
  surfaceOverrides: {
    type: "array",
    description: "对已解析 group 覆写刷地开关、连接、算法、材料或程序派生的 contour anchor。",
    items: strictObject({
      targetGroupId: nonEmptyString("已解析的 LandUse group ID。"),
      surfacePrintEnabled: { type: "boolean" },
      autoConnect: { type: "boolean" },
      surfaceAlgorithm: { type: "string", enum: ["uniform", "contour_bands"] },
      surfaceBlockId: nonEmptyString("可选地表 block ID。"),
      cropBlockId: nonEmptyString("可选作物 block ID。"),
      channelBankBlockId: nonEmptyString("可选沟渠岸体 block ID。"),
      channelWaterBlockId: nonEmptyString("可选沟渠水体 block ID。"),
      channelBankOverlayBlockId: nonEmptyString("可选沟渠岸边覆盖 block ID。"),
      algorithmAnchor: strictObject({
        x: integer("程序派生的世界 block X；只用于 contour_bands 相位。"),
        z: integer("程序派生的世界 block Z；只用于 contour_bands 相位。"),
      }, ["x", "z"]),
    }, ["targetGroupId"]),
  },
}, ["schema", "cityId"]);

const minecraftBlockIdSchema = {
  type: "string",
  pattern: "^[a-z0-9_.-]+:[a-z0-9/._-]+$",
  description: "合法 Minecraft block ID。",
};

const landUseRuleSchema = strictObject({
  ruleRef: nonEmptyString("冻结 LandUse rule 引用。"),
  landUseType: nonEmptyString("稳定土地使用类型。"),
  semanticTerms: {
    type: "array", uniqueItems: true,
    items: nonEmptyString("用于结构语义匹配的 term。"),
  },
  footprintMultiplier: { type: "number", minimum: 0 },
  extraAreaBlocks: { type: "integer", minimum: 0 },
  minAreaBlocks: { type: "integer", minimum: 0 },
  maxAreaBlocks: { type: "integer", minimum: 0 },
  actionBudget: { type: "number", exclusiveMinimum: 0 },
  baseStepCost: { type: "number", exclusiveMinimum: 0 },
  slopeCost: { type: "number" },
  reliefCost: { type: "number" },
  waterCost: { type: "number" },
  forestAffinity: { type: "number" },
  competitionWeight: { type: "number" },
  mergeSameType: { type: "boolean" },
  surfacePolicy: { type: "string", enum: ["PRESERVE", "PAVE", "CULTIVATE", "WATER_ADAPTIVE"] },
  vegetationPolicy: { type: "string", enum: ["PRESERVE", "SELECTIVE_CLEAR", "CLEAR"] },
  boundaryPolicy: { type: "string", enum: ["OPEN", "FENCE", "HEDGE", "LOW_WALL", "SHORELINE"] },
  decorationPolicy: nonEmptyString("冻结的装饰策略引用；户外编译不把它解释为第二份设计权威。"),
}, [
  "ruleRef", "landUseType", "semanticTerms", "footprintMultiplier", "extraAreaBlocks",
  "minAreaBlocks", "maxAreaBlocks", "actionBudget", "baseStepCost", "slopeCost", "reliefCost",
  "waterCost", "forestAffinity", "competitionWeight", "mergeSameType", "surfacePolicy",
  "vegetationPolicy", "boundaryPolicy", "decorationPolicy",
]);

const surfaceRecipeCommonProperties = {
  surfaceRecipeRef: nonEmptyString("冻结 surface recipe 引用。"),
  surfacePrintEnabled: { type: "boolean" },
  autoConnectDefault: { type: "boolean" },
  surfaceAlgorithm: { type: "string", enum: ["UNIFORM", "CONTOUR_BANDS"] },
};

const surfaceRecipeSchema: Record<string, unknown> = {
  oneOf: [
    strictObject({
      ...surfaceRecipeCommonProperties,
      surfacePrintEnabled: { type: "boolean", enum: [false] },
      autoConnectDefault: { type: "boolean", enum: [false] },
    }, ["surfaceRecipeRef", "surfacePrintEnabled", "autoConnectDefault", "surfaceAlgorithm"]),
    strictObject({
      ...surfaceRecipeCommonProperties,
      surfacePrintEnabled: { type: "boolean", enum: [true] },
      surfaceAlgorithm: { type: "string", enum: ["UNIFORM"] },
      surfaceBlockId: minecraftBlockIdSchema,
      cropBlockId: minecraftBlockIdSchema,
      channelBankBlockId: minecraftBlockIdSchema,
      channelWaterBlockId: minecraftBlockIdSchema,
      channelBankOverlayBlockId: minecraftBlockIdSchema,
      boundaryBlockId: minecraftBlockIdSchema,
    }, ["surfaceRecipeRef", "surfacePrintEnabled", "autoConnectDefault", "surfaceAlgorithm",
      "surfaceBlockId"]),
    strictObject({
      ...surfaceRecipeCommonProperties,
      surfacePrintEnabled: { type: "boolean", enum: [true] },
      surfaceAlgorithm: { type: "string", enum: ["CONTOUR_BANDS"] },
      surfaceBlockId: minecraftBlockIdSchema,
      cropBlockId: minecraftBlockIdSchema,
      channelBankBlockId: minecraftBlockIdSchema,
      channelWaterBlockId: minecraftBlockIdSchema,
      channelBankOverlayBlockId: minecraftBlockIdSchema,
      boundaryBlockId: minecraftBlockIdSchema,
      fieldBeforeBlocks: positiveInteger("等高线沟渠前的田地区段宽度。"),
      channelWidthBlocks: positiveInteger("等高线沟渠区段宽度。"),
      fieldAfterBlocks: positiveInteger("等高线沟渠后的田地区段宽度。"),
    }, ["surfaceRecipeRef", "surfacePrintEnabled", "autoConnectDefault", "surfaceAlgorithm",
      "surfaceBlockId", "cropBlockId", "channelBankBlockId", "channelWaterBlockId",
      "channelBankOverlayBlockId", "fieldBeforeBlocks", "channelWidthBlocks", "fieldAfterBlocks"]),
  ],
};

const blueprintReferenceCatalogSchema = strictObject({
  schema: { type: "string", enum: ["city_blueprint_reference_catalog"] },
  structureRefs: {
    type: "array", minItems: 1,
    items: strictObject({
      structureRef: nonEmptyString("Blueprint 使用的结构白名单引用。"),
      templateCandidates: {
        type: "array", minItems: 1,
        items: strictObject({
          templateId: nonEmptyString("template catalog 中存在的模板 ID。"),
          variantId: nonEmptyString("该模板中存在的 variant ID。"),
        }, ["templateId", "variantId"]),
      },
      greenParcel: strictObject({
        pattern: { type: "string", enum: ["FREEFORM", "FIELD_GRID"] },
        density: { type: "string", enum: ["LOW", "MEDIUM", "HIGH"] },
        groundBlockId: minecraftBlockIdSchema,
        pathBlockId: minecraftBlockIdSchema,
      }, ["pattern", "density", "groundBlockId", "pathBlockId"]),
    }, ["structureRef", "templateCandidates"]),
  },
  fillPools: {
    type: "array", minItems: 1,
    items: strictObject({
      poolRef: nonEmptyString("稳定 fill pool 引用。"),
      structureRefs: {
        type: "array", uniqueItems: true,
        items: nonEmptyString("同目录 structureRef。"),
      },
    }, ["poolRef", "structureRefs"]),
  },
  algorithmProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      algorithmProfileRef: nonEmptyString("稳定算法 profile 引用。"),
      algorithm: { type: "string", enum: ["COMPACT", "GRID", "LINEAR", "COURTYARD", "ORGANIC_COMPACT", "CENTER_SYMMETRIC"] },
      centerAxisStreetEnabled: { type: "boolean",
        description: "仅 CENTER_SYMMETRIC 可用；是否生成中心主体两侧的礼仪性轴街。" },
    }, ["algorithmProfileRef", "algorithm"]),
  },
  compositionProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      compositionProfileRef: nonEmptyString("稳定 composition profile 引用。"),
      mode: { type: "string", enum: ["ROUND_ROBIN"] },
    }, ["compositionProfileRef", "mode"]),
  },
  styleProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      profileRef: nonEmptyString("稳定 style profile 引用。"),
      plantPalette: {
        type: "array", minItems: 1,
        items: strictObject({
          blockId: minecraftBlockIdSchema,
          weight: { type: "number", exclusiveMinimum: 0 },
        }, ["blockId", "weight"]),
      },
    }, ["profileRef"]),
  },
  roadProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      profileRef: nonEmptyString("稳定 road profile 引用。"),
      hierarchy: { type: "string", enum: ["SIMPLE", "HIERARCHICAL"] },
      density: { type: "string", enum: ["SPARSE", "BALANCED", "DENSE"] },
    }, ["profileRef", "hierarchy", "density"]),
  },
  surfaceDetailProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      profileRef: nonEmptyString("稳定 surface detail profile 引用。"),
      intensity: { type: "string", enum: ["LOW", "MEDIUM", "HIGH"] },
    }, ["profileRef", "intensity"]),
  },
  landUseRuleProfile: strictObject({
    schema: { type: "string", enum: ["city_land_use_rules"] },
    profileId: nonEmptyString("冻结 LandUse rule profile ID。"),
    rules: { type: "array", minItems: 1, items: landUseRuleSchema },
  }, ["schema", "profileId", "rules"]),
  foundationProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      foundationProfileRef: nonEmptyString("稳定的城市统一基座 profile 引用。"),
      landUseRuleRef: nonEmptyString("同目录 LandUse ruleRef。"),
      surfaceRecipeRef: nonEmptyString("同目录 surfaceRecipeRef。"),
      structureMarginBlocks: { type: "integer", minimum: 0 },
      closeRadiusBlocks: { type: "integer", minimum: 0 },
      maxJoinDistanceBlocks: { type: "integer", minimum: 0 },
    }, ["foundationProfileRef", "landUseRuleRef", "surfaceRecipeRef", "structureMarginBlocks",
      "closeRadiusBlocks", "maxJoinDistanceBlocks"]),
  },
  surfaceRecipes: { type: "array", minItems: 1, items: surfaceRecipeSchema },
  landscapeProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      landscapeProfileRef: nonEmptyString("稳定 landscape profile 引用。"),
      landscapeType: { type: "string", enum: ["FARMLAND", "COMMON_GREEN", "WOODLAND", "MEADOW", "POND"] },
      landUseRuleRef: nonEmptyString("同目录 LandUse ruleRef。"),
      surfaceRecipeRef: nonEmptyString("同目录 surfaceRecipeRef。"),
      baseAreaSmall: positiveInteger("SMALL 景观基准面积。"),
      baseAreaMedium: positiveInteger("MEDIUM 景观基准面积；服务端要求不小于 SMALL。"),
      baseAreaLarge: positiveInteger("LARGE 景观基准面积；服务端要求不小于 MEDIUM。"),
      membership: { type: "string", enum: ["URBAN", "LANDSCAPE"] },
      parcelStyle: strictObject({
        parcelCountMin: positiveInteger("AI 可提交的每实例 Parcel 最小精确数量。"),
        parcelCountMax: positiveInteger("AI 可提交的每实例 Parcel 最大精确数量。"),
        parcelAreaMinBlocks: positiveInteger("单个地块最小面积。"),
        parcelAreaMaxBlocks: positiveInteger("单个地块最大面积。"),
        minSharedBoundaryBlocks: positiveInteger("非根 Parcel 与父 Parcel 至少共享的边界格数。"),
      }, ["parcelCountMin", "parcelCountMax", "parcelAreaMinBlocks", "parcelAreaMaxBlocks",
        "minSharedBoundaryBlocks"]),
    }, ["landscapeProfileRef", "landscapeType", "landUseRuleRef", "surfaceRecipeRef",
      "baseAreaSmall", "baseAreaMedium", "baseAreaLarge", "membership", "parcelStyle"]),
  },
  landscapeFillProfiles: {
    type: "array", minItems: 1,
    items: strictObject({
      fillProfileRef: nonEmptyString("Blueprint 可引用的稳定景观填充方案。"),
      displayName: nonEmptyString("面向 AI 的方案名称。"),
      visualIntent: nonEmptyString("方案预期视觉效果。"),
      algorithm: { type: "string", enum: ["SINGLE_SOURCE_REGION_RELAY"],
        description: "唯一合法几何算法；禁止固定图形、全局距离环和 geometry fallback。" },
      relayOrigin: { type: "string", enum: ["PARENT_REGION_LOCAL_BOUNDARY"],
        description: "首区之后，每个区域只能从父区域的局部边界继续生长。" },
      compatibleLandscapeTypes: {
        type: "array", minItems: 1, uniqueItems: true,
        items: { type: "string", enum: ["FARMLAND", "COMMON_GREEN", "WOODLAND", "MEADOW", "POND"] },
      },
      primaryRoleRef: nonEmptyString("主题角色；服务端要求 materialRole=PRIMARY_CONTENT。"),
      roles: {
        type: "array", minItems: 1,
        items: strictObject({
          roleRef: nonEmptyString("AI 在 roleShares 中使用的语义角色。"),
          materialRole: { type: "string", enum: ["PRIMARY_CONTENT", "BANK", "WATER", "GROUND"] },
          allowedGrowthForms: { type: "array", minItems: 1, uniqueItems: true,
            items: { type: "string", enum: ["PATCH", "CORRIDOR"] },
            description: "仅控制单源 frontier 的团块/廊道偏置，不定义固定图形。" },
          defaultGrowthForm: { type: "string", enum: ["PATCH", "CORRIDOR"] },
          minShare: { type: "number", minimum: 0, maximum: 1 },
          maxShare: { type: "number", exclusiveMinimum: 0, maximum: 1 },
          defaultShare: { type: "number", minimum: 0, maximum: 1 },
        }, ["roleRef", "materialRole", "allowedGrowthForms", "defaultGrowthForm",
          "minShare", "maxShare", "defaultShare"]),
      },
      allowedContentRefs: {
        type: "array", uniqueItems: true,
        items: nonEmptyString("AI contentWeights 可用的语义内容白名单；不是 block ID。"),
      },
      examples: {
        type: "array", minItems: 1,
        items: strictObject({
          exampleId: nonEmptyString("Profile 内唯一示例 ID。"),
          description: nonEmptyString("面向 AI 的组合效果说明。"),
          roleShares: {
            type: "array", minItems: 1,
            description: "有序区域接力 stage；roleRef 可重复，每项占比是该 stage 的占比，总和必须为 1。",
            items: strictObject({
              roleRef: nonEmptyString("本 Profile 声明的 roleRef。"),
              growthForm: { type: "string", enum: ["PATCH", "CORRIDOR"],
                description: "从 role 的 allowedGrowthForms 中选择，只影响 frontier 偏置。" },
              targetShare: { type: "number", exclusiveMinimum: 0, exclusiveMaximum: 1 },
            }, ["roleRef", "growthForm", "targetShare"]),
          },
          contentWeights: {
            type: "array",
            items: strictObject({
              contentRef: nonEmptyString("本 Profile allowedContentRefs 中的语义内容。"),
              weight: { type: "number", exclusiveMinimum: 0 },
            }, ["contentRef", "weight"]),
          },
        }, ["exampleId", "description", "roleShares", "contentWeights"]),
      },
    }, ["fillProfileRef", "displayName", "visualIntent", "algorithm", "relayOrigin",
      "compatibleLandscapeTypes", "primaryRoleRef", "roles", "allowedContentRefs", "examples"]),
  },
}, ["schema", "structureRefs", "fillPools", "algorithmProfiles", "compositionProfiles",
  "styleProfiles", "roadProfiles", "surfaceDetailProfiles", "landUseRuleProfile", "foundationProfiles", "surfaceRecipes",
  "landscapeProfiles", "landscapeFillProfiles"]);

const artifactRefSchema = strictObject({
  path: nonEmptyString("prepare-context 冻结的相对 artifact 路径。"),
  schema: nonEmptyString("prepare-context 冻结的 artifact schema。"),
  contentHash: { type: "string", pattern: "^sha256:[0-9a-f]{64}$" },
}, ["path", "schema", "contentHash"]);

const cityBlueprintSchema = strictObject({
  schema: { type: "string", enum: ["city_blueprint"] },
  cityId: nonEmptyString("必须与冻结上下文一致。"),
  sourceD3Ref: artifactRefSchema,
  catalogSnapshotRef: artifactRefSchema,
  generationSeed: { type: "integer", minimum: -9007199254740991, maximum: 9007199254740991,
    description: "后续程序化编译使用的 JavaScript-safe 稳定整数种子。" },
  designIntent: strictObject({
    cityIdentity: nonEmptyString("城市身份。"),
    theme: nonEmptyString("统一主题。"),
    functionalRoles: { type: "array", minItems: 1, uniqueItems: true, items: nonEmptyString("功能角色。") },
  }, ["cityIdentity", "theme", "functionalRoles"]),
  styleProfile: strictObject({ profileRef: nonEmptyString("冻结 style profile 引用。") }, ["profileRef"]),
  groups: {
    type: "array", minItems: 1, items: strictObject({
      groupId: nonEmptyString("蓝图内唯一 ID。"),
      groupKind: { type: "string", enum: ["STRUCTURE"] },
      preferredPatchRefs: { type: "array", minItems: 1, uniqueItems: true,
        items: nonEmptyString("偏好的 D3 landformPatchId；多个 Group 可共享，不允许世界坐标。") },
      preferredPatchZone: { type: "string", enum: ["CENTER", "NORTH", "EAST", "SOUTH", "WEST"],
        description: "核心在 preferredPatchRefs 精确成员格并集内的起步方位；北=-Z、南=+Z、西=-X、东=+X。" },
      placementRelation: strictObject({
        kind: { type: "string", enum: ["BETWEEN_PATCHES", "ALONG_PATCH_BOUNDARY", "BETWEEN_GROUPS"] },
        patchRefs: { type: "array", uniqueItems: true,
          items: nonEmptyString("关系位置引用的 D3 landformPatchId。") },
        groupRefs: { type: "array", uniqueItems: true,
          items: nonEmptyString("关系位置引用的同一 Blueprint groupId。") },
      }, ["kind", "patchRefs", "groupRefs"]),
      role: nonEmptyString("Group 功能角色。"),
      priority: { type: "string", enum: ["CORE", "STANDARD", "PERIPHERAL"] },
      extentClass: { type: "string", enum: ["SMALL", "MEDIUM", "LARGE"],
        description: "功能区空间范围档位，不表示建筑数量。" },
      densityClass: { type: "string", enum: ["SPARSE", "BALANCED", "DENSE"],
        description: "功能区疏密档位；建筑数量由范围、疏密和模板占地推导。" },
      algorithmProfileRef: nonEmptyString("冻结算法 profile 引用。"),
      terrainPolicy: { type: "string", enum: ["CONFORM", "BALANCED", "ASSERTIVE"] },
      requiredStructureRefs: { type: "array", minItems: 1, uniqueItems: true, items: nonEmptyString("结构白名单引用。") },
      fillPoolRef: nonEmptyString("冻结 fill pool 引用。"),
      connectionPlan: strictObject({
        structurePoolRef: nonEmptyString("连接阵列使用的 fill pool；缺省时继承 fillPoolRef。"),
        algorithmProfileRef: nonEmptyString("连接阵列算法 profile；缺省时继承 Group algorithmProfileRef。"),
        densityClass: { type: "string", enum: ["SPARSE", "BALANCED", "DENSE"],
          description: "连接阵列疏密；缺省时继承 Group densityClass。" },
        parameters: strictObject({
          clusterShape: { type: "string", enum: ["ORGANIC_COMPACT", "GRID", "COURTYARD", "L_SHAPE", "U_SHAPE"] },
          sideMode: { type: "string", enum: ["LEFT", "RIGHT", "BOTH"] },
          stagger: { type: "boolean" },
          widthClass: { type: "string", enum: ["NARROW", "MEDIUM", "WIDE"] },
        }, []),
      }, []),
      compositionProfileRef: nonEmptyString("冻结 composition profile 引用，只控制结构组成顺序，不限制数量。"),
      attachedFeatures: { type: "array", maxItems: 0, description: "案子 04 前必须为空。" },
      targetAreaShare: { type: "number", exclusiveMinimum: 0, maximum: 1,
        description: "功能区占整座城市目标范围的比例；不提交具体面积。" },
      spaceComposition: strictObject({
        buildingShare: { type: "number", minimum: 0, maximum: 1 },
        landscapeShare: { type: "number", minimum: 0, maximum: 1 },
        openSpaceShare: { type: "number", minimum: 0, maximum: 1 },
      }, ["buildingShare", "landscapeShare", "openSpaceShare"]),
      expansionPolicy: strictObject({
        allowOutwardExpansion: { type: "boolean" },
        allowRelationConnection: { type: "boolean" },
        stopWhenTargetReached: { type: "boolean" },
      }, ["allowOutwardExpansion", "allowRelationConnection", "stopWhenTargetReached"]),
      buildingGreeneryPolicy: strictObject({
        coverage: { type: "string", enum: ["NONE", "SPARSE", "BALANCED", "LUSH"],
          description: "功能区内符合模板能力的建筑拥有附属绿化的目标覆盖档位。" },
        patternPreference: { type: "string", enum: ["TEMPLATE_DEFAULT", "FREEFORM", "FIELD_GRID", "MIXED"],
          description: "功能区附属绿化构图偏好；不提交逐栋坐标或 mask。" },
        densityPreference: { type: "string", enum: ["TEMPLATE_DEFAULT", "LOW", "MEDIUM", "HIGH"],
          description: "功能区附属绿化疏密偏好。" },
      }, ["coverage", "patternPreference", "densityPreference"]),
    }, ["groupId", "groupKind", "preferredPatchRefs", "preferredPatchZone", "role", "priority", "extentClass", "densityClass",
      "algorithmProfileRef", "terrainPolicy", "requiredStructureRefs", "fillPoolRef",
      "compositionProfileRef", "attachedFeatures", "targetAreaShare", "spaceComposition", "expansionPolicy",
      "buildingGreeneryPolicy"]),
  },
  arrayCompositions: {
    type: "array",
    items: strictObject({
      compositionId: nonEmptyString("Blueprint 内唯一的父阵列 ID。"),
      algorithmProfileRef: nonEmptyString("编排完整子 Group 的冻结算法 profile；不覆盖子 Group 自身算法。"),
      centerGroupId: nonEmptyString("父阵列中心的完整 Group。"),
      memberGroupIds: { type: "array", minItems: 1, uniqueItems: true,
        items: nonEmptyString("由父阵列安排槽位的完整子 Group；CENTER_SYMMETRIC 按相邻两项组成对称对。") },
    }, ["compositionId", "algorithmProfileRef", "centerGroupId", "memberGroupIds"]),
  },
  relations: {
    type: "array", items: strictObject({
      fromGroupId: nonEmptyString("关系起点。"),
      toGroupId: nonEmptyString("关系终点。"),
      relationKind: { type: "string", enum: ["HIERARCHY", "ADJACENCY", "CONNECTION", "BUFFER", "DISTANCE", "DIRECTION"] },
      strength: { type: "string", enum: ["HARD", "SOFT"] },
      distancePreference: { type: "string", enum: ["NONE", "NEAR", "FAR"] },
      directionPreference: { type: "string", enum: ["NONE", "NORTH", "EAST", "SOUTH", "WEST"] },
    }, ["fromGroupId", "toGroupId", "relationKind", "strength", "distancePreference", "directionPreference"]),
  },
  roadProfile: strictObject({ profileRef: nonEmptyString("冻结 road profile 引用。") }, ["profileRef"]),
  surfaceDetailProfile: strictObject({ profileRef: nonEmptyString("冻结 surface profile 引用。") }, ["profileRef"]),
  outdoorPlan: strictObject({
    mode: { type: "string", enum: ["GENERATE", "PRESERVE"] },
    envelopeProfile: { type: "string", enum: ["COMPACT", "BALANCED", "LOOSE"] },
    foundationProfileRef: nonEmptyString("统一城市基座使用的冻结 foundation profile 引用。"),
    spatialGrounds: {
      type: "array",
      items: strictObject({
        sourceGroupId: nonEmptyString("同一 Blueprint 中的 STRUCTURE groupId。"),
        sharedSpaceType: { type: "string", enum: ["CIVIC_SQUARE", "MARKET_STREET",
          "RESIDENTIAL_COURT", "FARMSTEAD", "GENERAL_URBAN"] },
        hierarchyLevel: { type: "string", enum: ["PRIMARY", "SECONDARY", "LOCAL"] },
        membership: { type: "string", enum: ["URBAN", "LANDSCAPE"] },
      }, ["sourceGroupId", "sharedSpaceType", "hierarchyLevel", "membership"]),
    },
    landscapes: {
      type: "array",
      items: {
        ...strictObject({
        landscapeId: nonEmptyString("Blueprint 内唯一景观 ID。"),
        landscapeProfileRef: nonEmptyString("冻结的景观算法、地表和内容 profile 引用。"),
        purpose: { type: "string", enum: ["FUNCTIONAL", "COMPOSITIONAL", "AMBIENT"] },
        originMode: { type: "string", enum: ["ATTACHED", "FREE_STANDING"] },
        owner: strictObject({
          groupId: nonEmptyString("主体所在 STRUCTURE groupId。"),
          requiredStructureRef: nonEmptyString("该 Group 内唯一 required structureRef。"),
        }, ["groupId", "requiredStructureRef"]),
        placementDomain: { type: "string",
          enum: ["URBAN_RESIDUAL", "FOUNDATION_EDGE", "BETWEEN_GROUPS", "ALONG_WATER"] },
        instanceCount: positiveInteger("精确实例数；ATTACHED 必须为 1。"),
        parcelCount: positiveInteger("每个实例的精确 Parcel 数。"),
        preferredPatchRefs: {
          type: "array", uniqueItems: true,
          items: nonEmptyString("独立景观偏好的 D3 patch ref；不得提交世界坐标。"),
        },
        terrainPolicy: { type: "string", enum: ["CONFORM", "BALANCED", "ASSERTIVE"] },
        required: { type: "boolean" },
        fillSelection: strictObject({
          variants: {
            type: "array", minItems: 1,
            items: strictObject({
              fillProfileRef: nonEmptyString("冻结 landscapeFillProfiles 中的方案引用。"),
              selectionWeight: { type: "number", exclusiveMinimum: 0 },
              roleShares: {
                type: "array", minItems: 1,
                description: "AI 提交的有序区域接力 stage；roleRef 可重复，顺序即接力顺序，各 stage targetShare 总和为 1。",
                items: strictObject({
                  roleRef: nonEmptyString("所选方案声明的角色。"),
                  growthForm: { type: "string", enum: ["PATCH", "CORRIDOR"],
                    description: "AI 选择的区域生长类型，仅作为 frontier 偏置。" },
                  targetShare: { type: "number", exclusiveMinimum: 0, exclusiveMaximum: 1 },
                }, ["roleRef", "growthForm", "targetShare"]),
              },
              contentWeights: {
                type: "array",
                items: strictObject({
                  contentRef: nonEmptyString("所选方案白名单内的语义内容；不是 block ID。"),
                  weight: { type: "number", exclusiveMinimum: 0 },
                }, ["contentRef", "weight"]),
              },
            }, ["fillProfileRef", "selectionWeight", "roleShares", "contentWeights"]),
          },
        }, ["variants"]),
        }, ["landscapeId", "landscapeProfileRef", "purpose", "originMode", "instanceCount", "parcelCount",
          "preferredPatchRefs", "terrainPolicy", "required", "fillSelection"]),
        oneOf: [
          {
            properties: {
              originMode: { const: "ATTACHED" },
              instanceCount: { const: 1 },
            },
            required: ["owner"],
            not: { required: ["placementDomain"] },
          },
          {
            properties: {
              originMode: { const: "FREE_STANDING" },
              required: { const: false },
            },
            required: ["placementDomain"],
            not: { required: ["owner"] },
          },
        ],
      },
    },
  }, ["mode", "envelopeProfile", "foundationProfileRef", "spatialGrounds", "landscapes"]),
}, ["schema", "cityId", "sourceD3Ref", "catalogSnapshotRef", "generationSeed", "designIntent",
  "styleProfile", "groups", "arrayCompositions", "relations", "roadProfile", "surfaceDetailProfile", "outdoorPlan"]);

export const realmTools: ToolDefinition[] = [
  {
    name: "realm_status",
    description: "读取 Geomantia 国度规划 W/T 调试接口状态、最近 run 和产物目录。",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "realm_w_refresh",
    description: "执行 W 粗扫，生成 WorldSurveyContext、WorldPatchMap 和带网格坐标预览图。",
    inputSchema: {
      type: "object",
      properties: {
        planningRadiusBlocks: { type: "number", description: "配置的 W 最大扫描半径，单位 block，默认 8192。" },
        radiusChunks: { type: "number", description: "兼容字段：粗扫半径，单位 chunk；未传 planningRadiusBlocks 时换算。" },
        cellStepBlocks: { type: "number", description: "粗 cell 步长，默认 128。" },
        microSampleStrideBlocks: { type: "number", description: "W cell 内 micro-sampling 步长，默认 32 block。" },
        localSlopeRadiusBlocks: { type: "number", description: "micro sample 周边局部坡度半径，默认 8 block。" },
        preferGeneratorNativeTerrain: { type: "boolean", description: "默认 true；检测到兼容生成器时优先使用原生二维地形快路径，false 强制使用 Minecraft prior。" },
        runTagAudit: { type: "boolean", description: "开发期调试：W 完成后抽样局部精扫并输出 tag_audit_report。" },
        tagAuditSampleCount: { type: "number", description: "Tag Audit 抽样点数量，默认 120。" },
        tagAuditSampleSeed: { type: "string", description: "Tag Audit 抽样 seed；同一 run 可换 seed 抽另一批点。" },
        tagAuditRadiusBlocks: { type: "number", description: "Tag Audit 局部精扫半径，默认 32 block。" },
        tagAuditStrideBlocks: { type: "number", description: "Tag Audit 局部精扫步长，默认 4 block。" },
        tagAuditSlopeRadiusBlocks: { type: "number", description: "Tag Audit 局部坡度半径，默认 4 block。" },
        sampleMode: { type: "string", enum: ["prior", "observedIfLoaded", "verifySurface"] },
        qualityMode: { type: "string", enum: ["smoke", "strict"], description: "验收质量模式；默认 strict。" },
        resumePolicy: { type: "string", enum: ["use_cache", "rescan", "use_cache_strict"] },
        centerBlockX: { type: "number" },
        centerBlockZ: { type: "number" },
        dimensionId: { type: "string" },
        playerName: { type: "string" },
        runId: { type: "string" },
        worldTheme: { type: "object" },
      },
    },
  },
  {
    name: "realm_t1_prepare",
    description: "基于 W 产物生成 RealmProfile 和大陆合法范围参考图；该图不是按文明差异生成的正式选址图，多个国度可以相同。T1 完成后必须先调用 patch_explorer_open(scopeType=realm_t2, realmId=...)，再 show/select 候选；直接提交 grid 坐标仅为兼容入口。客户端重启后可按 runId 从 sealed W 懒恢复，不重跑 W。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        realmProfiles: { type: "array" },
        realmCount: { type: "number" },
        targetContinentId: { type: "string" },
        allowAiDraftProfile: { type: "boolean" },
      },
      required: ["runId"],
    },
  },
  {
    name: "realm_t2_select_coordinate",
    description: "提交 realm_t2 Patch Explorer 的 patchSelectionRef，校验并生成国度扩张用 RealmSeed 与无坐标 CapitalCityIntent。此处不决定首都最终位置；正常 AI 主链必须先完成 patch_explorer_open/show/select。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        realmId: { type: "string" },
        gridX: { type: "number" },
        gridZ: { type: "number" },
        patchSelectionRef: { type: "string", description: "来自 realm_t2 Patch Explorer；提交后无需手填 gridX/gridZ。" },
        alternates: { type: "array" },
        reason: { type: "string" },
        selectedBy: { type: "string", enum: ["ai", "human", "debug"] },
        allowSnap: { type: "boolean" },
      },
      required: ["runId", "realmId"],
    },
  },
  {
    name: "realm_t3_expand",
    description: "运行 T3 多国度粗 cell 扩张，输出 RealmTerritoryMap 和国境预览图；服务重启后自动恢复完整 T1/T2 checkpoint。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        normalizationGroup: { type: "string" },
        allowUnclaimedLand: { type: "boolean" },
        qualityMode: { type: "string", enum: ["smoke", "strict"], description: "T3 质量模式；默认 strict。" },
        expansionModel: { type: "string", enum: ["quota_frontier", "action_budget"], description: "T3 扩张模型；strict 默认 action_budget，smoke 默认 quota_frontier。" },
      },
      required: ["runId"],
    },
  },
  {
    name: "realm_t4_build_registry",
    description: "兼容的固定验收入口：以 rule_fixture 模式在国度核心生成 T4 CitySeedRegistry。正式 AI 规划必须使用 realm_t4_patch_planning_create/select_capital/add_city/finalize。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string" },
        cityPlanningMode: { type: "string", enum: ["auto", "strict"], description: "T4 城市规划模式；默认 strict。" },
      },
      required: ["runId"],
    },
  },
  {
    name: "realm_t4_patch_planning_create",
    description: "为单个国度创建 AI 驱动的 T4 城市规划会话。会话只载入无坐标首都意图，citySeeds 初始为空；下一步必须先用 Patch Explorer 选首都。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        realmId: { type: "string" },
        planningSessionId: { type: "string" },
      },
      required: ["runId", "realmId"],
    },
  },
  {
    name: "realm_t4_patch_planning_select_capital",
    description: "消费 AI 已选的 realm_t4 patchSelectionRef，按 CapitalCityIntent 固定身份和规模建立该国唯一首都。服务端校验 owned territory、连续承载面积和重复选择。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        planningSessionId: { type: "string" },
        patchSelectionRef: { type: "string" },
        candidateRangeCells: { type: "integer", minimum: 1 },
        minimumAreaBlocks: { type: "integer", minimum: 0 },
        subregionId: { type: "string" },
        requiredConditions: { type: "array", items: { type: "string" } },
        coreFunctions: { type: "array", items: { type: "string" } },
        selectionReason: { type: "string" },
      },
      required: ["runId", "planningSessionId", "patchSelectionRef"],
    },
  },
  {
    name: "realm_t4_patch_planning_add_city",
    description: "在首都已选定后，把 AI 已选的 realm_t4 patchSelectionRef 转为非首都城市种子。role=capital 必须使用专用 select_capital 工具。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        planningSessionId: { type: "string" },
        patchSelectionRef: { type: "string" },
        citySeedId: { type: "string" },
        role: { type: "string" },
        theoreticalScale: { type: "string", enum: ["large_city", "city", "town", "village", "outpost"] },
        candidateRangeCells: { type: "integer", minimum: 1 },
        minimumAreaBlocks: { type: "integer", minimum: 0 },
        subregionId: { type: "string" },
        satelliteOf: { type: "string" },
        requiredConditions: { type: "array", items: { type: "string" } },
        coreFunctions: { type: "array", items: { type: "string" } },
        trigger: { type: "string" },
        selectionReason: { type: "string" },
      },
      required: ["runId", "planningSessionId", "patchSelectionRef", "citySeedId", "role"],
    },
  },
  {
    name: "realm_t4_patch_planning_finalize",
    description: "完成单国 T4 patch 规划，并按 realm 合并写回全局 CitySeedRegistry。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        planningSessionId: { type: "string" },
      },
      required: ["runId", "planningSessionId"],
    },
  },
  {
    name: "patch_explorer_open",
    description: "打开共享 Patch Explorer session。realm_t2/realm_t4 只用 sealed W Patch 提供范围，在 T 的 32 格尺度重新采样、分类并生成 landform Patch；city_d4 使用 D3 自身尺度且跨 GIS region 合并后的 landform Patch，并扣除 hard occupied。返回同一范围的原始地形总览、所有 Patch 总览和 T/D 共用固定色表。群系只作为附加地理事实，不生成候选。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        scopeType: { type: "string", enum: ["realm_t2", "realm_t4", "city_d4"] },
        scopeId: { type: "string", description: "realm_t2 为 realmId/continentId，realm_t4 为 realmId，city_d4 为 citySeedId。" },
        realmId: { type: "string" },
        continentId: { type: "string" },
        citySeedId: { type: "string" },
        sessionId: { type: "string" },
        preferGeneratorNativeTerrain: { type: "boolean", description: "默认 true；T Patch 重算与候选高程预览优先使用生成器原生地形，false 时强制走 Minecraft prior。" },
      },
      required: ["runId", "scopeType"],
    },
  },
  {
    name: "patch_explorer_show_candidates",
    description: "按 AI 主动选择的 landform 类型返回每类稳定面积分页和仅限当前页候选的稀疏几何关系，默认每类 Top 3。三个 scope 都在 open 的原始地形总览同一边界、同一比例上，把本页所有类型的 Top Patch 聚合高亮并标注候选 ID；批量比较不再为每个候选单独生成自适应取景图。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        sessionId: { type: "string" },
        interestTypes: { type: "array", minItems: 1, items: { type: "string" } },
        page: { type: "integer", minimum: 0 },
        pageToken: { type: "string" },
        pageSize: { type: "integer", minimum: 1, maximum: 12, default: 3 },
      },
      required: ["runId", "sessionId", "interestTypes"],
    },
  },
  {
    name: "patch_explorer_select_candidate",
    description: "选中当前页已展示候选，按 16 格、1024 格城市尺度生成同样的高程高亮确认图，并冻结稳定 patchSelectionRef。选择理由字段为 selectionReason，source、预览证据变化或候选未展示时拒绝。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        sessionId: { type: "string" },
        candidateId: { type: "string" },
        selectionReason: { type: "string" },
      },
      required: ["runId", "sessionId", "candidateId"],
    },
  },
  {
    name: "realm_run_acceptance",
    description: "运行 W -> T4 端到端验收闭环，默认自动选择候选坐标并输出 acceptance_report。",
    inputSchema: {
      type: "object",
      properties: {
        planningRadiusBlocks: { type: "number" },
        radiusChunks: { type: "number" },
        cellStepBlocks: { type: "number" },
        microSampleStrideBlocks: { type: "number" },
        localSlopeRadiusBlocks: { type: "number" },
        preferGeneratorNativeTerrain: { type: "boolean" },
        runTagAudit: { type: "boolean" },
        tagAuditSampleCount: { type: "number" },
        tagAuditSampleSeed: { type: "string" },
        tagAuditRadiusBlocks: { type: "number" },
        tagAuditStrideBlocks: { type: "number" },
        tagAuditSlopeRadiusBlocks: { type: "number" },
        sampleMode: { type: "string", enum: ["prior", "observedIfLoaded", "verifySurface"] },
        qualityMode: { type: "string", enum: ["smoke", "strict"] },
        expansionModel: { type: "string", enum: ["quota_frontier", "action_budget"] },
        cityPlanningMode: { type: "string", enum: ["auto", "strict"] },
        resumePolicy: { type: "string", enum: ["use_cache", "rescan", "use_cache_strict"] },
        centerBlockX: { type: "number" },
        centerBlockZ: { type: "number" },
        dimensionId: { type: "string" },
        playerName: { type: "string" },
        runId: { type: "string" },
        realmCount: { type: "number" },
        realmProfiles: { type: "array" },
        autoSelectCoordinates: { type: "boolean" },
      },
    },
  },
  {
    name: "realm_debug_command",
    description: "开发调试：通过 Minecraft server command dispatcher 执行一条命令，例如 tp/time/weather/gamemode。必须显式 confirmCommandExecution=true；默认拦截 stop/reload/op/ban 等高风险管理命令，除非 allowUnsafeCommand=true。",
    inputSchema: {
      type: "object",
      properties: {
        command: {
          type: "string",
          description: "要执行的 Minecraft 命令，可带或不带开头 /；必须是单行。",
        },
        confirmCommandExecution: {
          type: "boolean",
          description: "必须为 true；用于确认这是有副作用的调试命令执行。",
        },
        sourceMode: {
          type: "string",
          enum: ["auto", "player", "server"],
          description: "命令源；默认 auto。player 使用玩家上下文，server 使用服务端上下文。",
        },
        playerName: {
          type: "string",
          description: "玩家名；用于 sourceMode=player 或 auto 下选择玩家上下文，例如 Rinsing。",
        },
        dimensionId: {
          type: "string",
          description: "维度 ID；省略时使用玩家维度或 overworld。",
        },
        saveAfter: {
          type: "boolean",
          description: "执行后是否请求保存世界，默认 false。TP 通常不需要。",
        },
        allowUnsafeCommand: {
          type: "boolean",
          description: "允许执行 stop/reload/op/ban 等高风险管理命令，默认 false。",
        },
      },
      required: ["command", "confirmCommandExecution"],
    },
  },
  {
    name: "city_plan_d2",
    description: "City D2: 基于已有 W/T run 的 CitySeed 构建 CitySiteContext（城市局部上下文）。需提供 runId 和 citySeedId。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        cellStepBlocks: { type: "number", description: "可选覆盖；未传时从 run 的 world_survey_manifest.json 恢复 W/T 采样步长。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_plan_d3",
    description: "City D3: 以固定 16-block step 构建局部地貌审查包与群系图。默认优先 RTF 二维快速采样，未安装或不可用时整批回退 Minecraft prior。对 T4 AI 候选选出的首都，返回 siteReviewStatus=awaiting_review，必须调用 city_review_d3_site 后才能进入 D4。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        cellStepBlocks: { type: "number", enum: [16], description: "兼容字段；D3 固定为 16，省略即可。" },
        preferGeneratorNativeTerrain: { type: "boolean", description: "默认 true：优先 RTF 快速采样；不可用时整批回退 Minecraft prior。" },
        patchScanPaddingBlocks: { type: "number", description: "D3 patch 上下文额外扫描 padding，默认 128；用于让结构边界和当前城墙落在已扫描 patch 内。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_review_d3_site",
    description: "显式审查 T4 AI 选出首都的 D3 局部真实地形。接受当前点位后才可进入 D4；选择 reselect_required 时必须回到 T4 重选，不默认改变城市原型。",
    inputSchema: {
      type: "object",
      additionalProperties: false,
      properties: {
        runId: { type: "string" },
        citySeedId: { type: "string" },
        decision: { type: "string", enum: ["accept_selected_site", "reselect_required"] },
        decisionReason: { type: "string", minLength: 1 },
        reviewedBy: { type: "string", enum: ["ai", "human", "debug"] },
      },
      required: ["runId", "citySeedId", "decision", "decisionReason"],
    },
  },
  {
    name: "city_prepare_d4_blueprint_context",
    description: "程序准备并冻结 D4 单次城市决策的完整只读上下文。该工具不调用模型，也不计入 AI 城市设计调用次数。",
    inputSchema: {
      type: "object", additionalProperties: false,
      properties: {
        runId: nonEmptyString("已有 W/T run ID。"),
        citySeedId: nonEmptyString("目标城市。"),
        terrasenseProfileSource: { type: "object", description: "现有 TerraSense 结构画像源。" },
        templateCatalogSource: { type: "object", description: "现有固定 NBT template catalog 源。" },
        blueprintReferenceCatalog: {
          ...blueprintReferenceCatalogSchema,
          description: "严格 city_blueprint_reference_catalog 户外目录；AI 精确声明 Landscape 实例和 Parcel 数，required 主体与建筑联合预留，Parcel 使用父子边界接力；结构可选绿化地块，城市 style profile 可冻结植物 palette；禁止固定图形和 geometry fallback。",
        },
      },
      required: ["runId", "citySeedId", "terrasenseProfileSource", "templateCatalogSource", "blueprintReferenceCatalog"],
    },
  },
  {
    name: "city_submit_d4_blueprint",
    description: "正式 AI 边界：对同一 contextId 只接受一次包含结构与户外意图的完整 CityBlueprint 提交；AI 必须明确选择核心/填充建筑、阵列关系和景观占比；不会进入逐栋建筑候选、slot、阵列或户外 AI 循环。",
    inputSchema: {
      type: "object", additionalProperties: false,
      properties: {
        runId: nonEmptyString("上下文所属 run。"),
        citySeedId: nonEmptyString("上下文所属城市。"),
        contextId: nonEmptyString("prepare-context 返回的冻结 contextId。"),
        cityBlueprint: cityBlueprintSchema,
      },
      required: ["runId", "citySeedId", "contextId", "cityBlueprint"],
    },
  },
  {
    name: "city_compile_d4_blueprint",
    description: "程序化编译已接受的完整 CityBlueprint：保留 AI 指定核心/填充模板与阵列关系，按关系图、阵列和范围完成连接与 fill；普通非水体坑洼/起伏由台基消化，无法承载的单栋由 PCG 跳过，不升级为整城失败。输出标准 D4 anchor、compile trace 与 Group extent，不调用 AI、不接受 candidateId。",
    inputSchema: {
      type: "object", additionalProperties: false,
      properties: {
        runId: nonEmptyString("Blueprint 所属 run。"),
        citySeedId: nonEmptyString("Blueprint 所属城市。"),
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_plan_d5",
    description: "City D5: 读取最终 D4 StructureAnchorMap，按 collisionEnvelope + maskMarginBlocks 生成轻量 reservation mask 预案；忽略旧 safetyEnvelope 字段；road/build 仍为空占位，不生成真实道路或修改世界。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId（来自 city_seed_registry.json）。" },
        wallMarginBlocks: { type: "number", description: "墙带生成外扩距离，默认 24。" },
        wallCorridorHalfWidthBlocks: { type: "number", description: "墙带 corridor 半宽，默认 4。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_query_decoration_catalog",
    description: "查询当前 City Decoration 素材目录与 style profiles。AI 从所选 profile 的 semanticRefs 引用内容；只读，不返回原始 NBT 或任意 block operation。",
    inputSchema: strictObject({}, []),
  },
  {
    name: "city_upgrade_default_decoration_catalog",
    description: "显式升级未改动的 Geomantia 默认装饰目录，为水渠补下坡收尾内容。会备份旧 content index、停用旧 hash 的 active decoration plans，但不改 ledger 或已落地方块；完成后必须重新查询 catalog、重规划并重新激活。",
    inputSchema: strictObject({
      confirmConfigMutation: { type: "boolean", description: "必须为 true；否则不修改运行时 config。" },
    }, ["confirmConfigMutation"]),
  },
  {
    name: "city_probe_decoration_terrain",
    description: "只读检查已编译 City Decoration 的真实已加载区块地表。返回每个 program / palette slot 的加载覆盖、高度起伏、相邻槽位高差和连续带剖面；不会生成区块、不会写世界，也不会阻止后续 activate。",
    inputSchema: strictObject({
      runId: nonEmptyString("已有 W/T run ID。"),
      citySeedId: nonEmptyString("目标城市种子的 citySeedId。"),
      dimensionId: nonEmptyString("可选；省略时使用 run 记录的维度。"),
      playerName: nonEmptyString("可选；用于定位玩家当前维度。"),
    }, ["runId", "citySeedId"]),
  },
  {
    name: "city_query_structure_catalog",
    description: "按 TerraSense 已审核结构标签检索模板。支持 canonical termId，或来自 terrasenseProfileSource.vocabularySnapshotPath 的中文标签/别名；只读，不触发 D4-D7 或世界写入。",
    inputSchema: strictObject({
      terrasenseProfileSource: {
        type: "object",
        description: "schema=terrasense_structure_profile_source；sourceType=structure_profile_jsonl 或 debug_catalog。中文标签/别名检索需附 vocabularySnapshotPath。",
      },
      allOfTerms: {
        type: "array",
        description: "AND：候选必须同时拥有的 TerraSense term；可用 canonical termId 或词表标签/别名。",
        items: nonEmptyString("TerraSense canonical termId、词表 display label 或 alias。"),
      },
      anyOfTerms: {
        type: "array",
        description: "OR：候选至少拥有一个的 TerraSense term。",
        items: nonEmptyString("TerraSense canonical termId、词表 display label 或 alias。"),
      },
      excludeTerms: {
        type: "array",
        description: "排除：拥有任一 term 的候选不会返回。",
        items: nonEmptyString("TerraSense canonical termId、词表 display label 或 alias。"),
      },
      limit: { type: "integer", minimum: 1, maximum: 100, description: "最多返回数量，默认 20。" },
    }, ["terrasenseProfileSource"]),
  },
  {
    name: "city_query_template_metadata",
    description: "从当前 Minecraft StructureTemplateManager 只读校验指定 templateRef，返回运行时 NBT 的尺寸、内容 hash 与来源；不加载目标区块，不执行 D4-D7，也不放置方块。",
    inputSchema: strictObject({
      templateRefs: {
        type: "array",
        minItems: 1,
        maxItems: 32,
        description: "待校验的 ResourceLocation，例如 geomantia:d6d7_fixture/house。",
        items: nonEmptyString("有效的 template ResourceLocation。"),
      },
      dimensionId: nonEmptyString("可选；省略时取玩家当前维度。"),
      playerName: nonEmptyString("可选；用于定位当前维度。"),
    }, ["templateRefs"]),
  },
  {
    name: "city_plan_city_dressing",
    description: "City DecorationProgram 规划：选择 style profile 并提交分层语义 contentRef；程序在规划期解析为具体 prefab，再从 D3 patch 或 LandUse area 解析 mask 与局部坐标系。禁止已废弃的 dressingBrushPlan、targetBounds/memberBounds、世界 origin/x/z、内联 NBT 和 block operation。",
    inputSchema: strictObject({
      runId: nonEmptyString("已有 W/T run ID。"),
      citySeedId: nonEmptyString("目标城市种子的 citySeedId。"),
      decorationProgramPlan: decorationProgramPlanSchema,
    }, ["runId", "citySeedId", "decorationProgramPlan"]),
  },
  {
    name: "city_plan_decoration_anchor_candidates",
    description: "为喷泉、雕像、水井等单点关键装饰生成完整 footprint + 舒适边距约束下的候选锚点和预览。只读规划，不写正式 Decoration 编译产物、不加载区块；每个候选返回可直接回填当前 program.coordinateFrame 的 patch。",
    inputSchema: strictObject({
      runId: nonEmptyString("已有 W/T run ID。"),
      citySeedId: nonEmptyString("目标城市种子的 citySeedId。"),
      decorationProgramPlan: decorationProgramPlanSchema,
      programId: nonEmptyString("待选关键装饰的 programId；program 必须表达单点 prefab。"),
      candidateCount: {
        type: "integer",
        minimum: 1,
        maximum: 8,
        description: "最多返回候选数量，默认 5。",
      },
    }, ["runId", "citySeedId", "decorationProgramPlan", "programId"]),
  },
  {
    name: "city_plan_land_use",
    description: "City 建筑驱动 LandUse 规划：读取 D3 block terrain field 与 D6 locked footprint，把 D4 显式 group 或独立 anchor 解析为扩张主体，生成 block 级土地使用区域。只接受稳定 ruleRef 覆写，不接受裸面积、行动力或成本参数；不加载未生成 chunk，也不修改世界。",
    inputSchema: strictObject({
      runId: nonEmptyString("已有 W/T run ID。"),
      citySeedId: nonEmptyString("目标城市种子的 citySeedId。"),
      landUseIntentPlan: landUseIntentPlanSchema,
    }, ["runId", "citySeedId"]),
  },
  {
    name: "city_execute_d5",
    description: "City D5 Execute: 必须先有完整 D6 固定模板计划；用 exact NBT footprint、collision、mask 和 ownerChunks 激活 reservation mask registry 与 City 单-piece worldgen registry。必须显式传 confirmWorldMutation=true。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        confirmWorldMutation: { type: "boolean", description: "必须为 true；否则拒绝真实改世界。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId", "confirmWorldMutation"],
    },
  },
  {
    name: "city_plan_d6",
    description: "City D6: 从当前世界重新读取固定 NBT，校验 runtime hash、rawSize、transform、exact footprint、collision、mask 和 ownerChunks。不会读取 StructureStart bbox，不要求目标 chunk loaded，不修改世界。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_execute_d7",
    description: "City Execute D7: 只检查固定模板 worldgen ledger。不会 late paste；未生成模板返回等待，ledger 完整后基于 exact NBT footprint 生成道路/边界后处理。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        executeStructurePlacement: { type: "boolean", description: "true 时检查 worldgen ledger/状态；正式路径不 late paste。" },
        worldSeed: { type: "number", description: "可选；仅供仍需稳定 seed 的道路/后处理逻辑使用，不参与模板 identity 或几何选择。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_query_worldgen_observations",
    description: "按维度和 chunk 查询 City worldgen 写入后的 Minecraft 实际 BlockState。返回 post_features、post_retry_tick、chunk_save 回调观测及期望/实际不一致位置；只读，不加载或生成 chunk。",
    inputSchema: {
      type: "object",
      properties: {
        dimensionId: { type: "string", description: "维度 ID，例如 minecraft:overworld。" },
        chunkX: { type: "integer", description: "目标 chunk X。" },
        chunkZ: { type: "integer", description: "目标 chunk Z。" },
        phase: {
          type: "string",
          enum: ["post_features", "post_retry_tick", "chunk_save"],
          description: "可选观测阶段过滤。",
        },
        limit: { type: "integer", minimum: 1, maximum: 100, description: "返回最近记录数，默认 10。" },
        includeBlocks: { type: "boolean", description: "是否返回逐方块实际状态，默认 true。" },
      },
      required: ["dimensionId", "chunkX", "chunkZ"],
    },
  },
  {
    name: "city_plan_city_walls",
    description: "City 城墙规划：读取 D5 最终墙线、D7 placed ledger 和 City 自有道路的实际路面开门，并按当前地形策略生成墙体。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        roadScanMarginBlocks: { type: "number", description: "扫描 City 实际路面的墙带外扩距离，默认 8。" },
        naturalBoundaryMinDeltaBlocks: { type: "number", description: "高差天然屏障断墙阈值，默认 17。" },
        wallUnitLengthBlocks: { type: "number", description: "城墙 placement unit 长度，默认 8。" },
        nominalWallHeightBlocks: { type: "number", description: "名义墙高，默认 9。" },
        heightSegmentMaxDeltaBlocks: { type: "number", description: "分段统一墙顶高度的并段高差阈值，默认 7。" },
        heightSteppedTransitionMaxDeltaBlocks: { type: "number", description: "段间阶梯过渡最大高差，默认 16。" },
        waterRunMinBlocks: { type: "number", description: "连续水体边界判定最小长度，默认 32 blocks。" },
        waterFluidRatioMin: { type: "number", description: "单 unit fluid 覆盖率阈值，默认 0.8。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "city_execute_city_walls",
    description: "City 城墙执行：读取 city_wall_plan，用原版/Forge setBlock 放置石砖城墙和塔楼；可开启 debugScan 输出地形/mask/gap 报告；需要 confirmWorldMutation=true。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        confirmWorldMutation: { type: "boolean", description: "必须为 true；否则拒绝真实改世界。" },
        debugScan: { type: "boolean", description: "调试开关；true 时输出 step=1 地形扫描、mask 冲突和 gap 报告。" },
        debugScanStepBlocks: { type: "number", description: "debug scan 步长，默认 1。" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId", "confirmWorldMutation"],
    },
  },
  {
    name: "city_run_workflow",
    description: "City 正式 workflow：D3/site review 后等待或编译已接受的 CityBlueprint，把标准 D4 anchor 交给 D5/D6，并在 D6 locked footprint 后从同一 Blueprint 自动编译户外空间。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 W/T run ID。" },
        citySeedId: { type: "string", description: "目标城市种子的 citySeedId。" },
        cellStepBlocks: { type: "number", description: "D3 cell step，未传则使用默认。" },
        patchScanPaddingBlocks: { type: "number", description: "D3 patch 上下文额外扫描 padding，默认 128；workflow 首跑 D3 时用于覆盖结构和城墙 breathing room。" },
        skipExisting: { type: "boolean", description: "默认 true；已有 artifact 时跳过对应步骤，用于等待 worldgen 后快速续跑。" },
        confirmWorldMutation: { type: "boolean", description: "true 才执行 D5 激活 mask/registry；未传时 workflow 会停在 waiting_for_confirmation。" },
        planWalls: { type: "boolean", description: "D7 ledger 完整后是否调用 city_plan_city_walls。" },
        executeWalls: { type: "boolean", description: "planWalls 后是否执行 city_execute_city_walls。" },
        debugScan: { type: "boolean", description: "执行城墙时是否输出地形/mask/gap debug scan，默认 true。" },
        debugScanStepBlocks: { type: "number", description: "debug scan 步长，默认 1。" },
        wallMarginBlocks: { type: "number" },
        wallCorridorHalfWidthBlocks: { type: "number" },
        wallUnitLengthBlocks: { type: "number" },
        nominalWallHeightBlocks: { type: "number" },
        heightSegmentMaxDeltaBlocks: { type: "number" },
        heightSteppedTransitionMaxDeltaBlocks: { type: "number" },
        naturalBoundaryMinDeltaBlocks: { type: "number" },
        waterRunMinBlocks: { type: "number" },
        waterFluidRatioMin: { type: "number" },
        waterRunMinUnits: { type: "number" },
        waterRetreatMaxCells: { type: "number" },
        structureWallBreathingRoomBlocks: { type: "number" },
        heightDatumClampBlocks: { type: "number" },
        localMedianWindowUnits: { type: "number" },
        dimensionId: { type: "string", description: "维度 ID，省略时从 run manifest 恢复。" },
        playerName: { type: "string", description: "玩家名，用于定位维度。" },
      },
      required: ["runId", "citySeedId"],
    },
  },
  {
    name: "realm_tag_audit",
    description: "对已有 sealed W run 单独执行 Tag Audit 抽样局部精扫，不重跑 W/T 主链。",
    inputSchema: {
      type: "object",
      properties: {
        runId: { type: "string", description: "已有 sealed W/T run ID。" },
        tagAuditSampleCount: { type: "number", description: "Tag Audit 抽样点数量，默认 120。" },
        tagAuditSampleSeed: { type: "string", description: "Tag Audit 抽样 seed；同一 run 可换 seed 抽另一批点。" },
        tagAuditRadiusBlocks: { type: "number", description: "Tag Audit 局部精扫半径，默认 32 block。" },
        tagAuditStrideBlocks: { type: "number", description: "Tag Audit 局部精扫步长，默认 4 block。" },
        tagAuditSlopeRadiusBlocks: { type: "number", description: "Tag Audit 局部坡度半径，默认 4 block。" },
        dimensionId: { type: "string", description: "可选；省略时从 run 的 world_survey_manifest.json 恢复。" },
        playerName: { type: "string", description: "可选；用于没有 dimensionId 时选择玩家维度。" },
      },
      required: ["runId"],
    },
  },
];

function decorationVariant(type: string, params: Record<string, unknown>, required: string[] = []) {
  return strictObject({
    type: { type: "string", enum: [type] },
    params: strictObject(params, required),
  }, ["type", "params"]);
}

function strictObject(properties: Record<string, unknown>, required: string[]) {
  return {
    type: "object",
    properties,
    required,
    additionalProperties: false,
  };
}

function nonEmptyString(description: string) {
  return { type: "string", minLength: 1, description };
}

function integer(description: string) {
  return { type: "integer", description };
}

function positiveInteger(description: string) {
  return { type: "integer", minimum: 1, description };
}
