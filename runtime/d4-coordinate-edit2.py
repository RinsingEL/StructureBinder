from pathlib import Path
import re
base=Path('src/main/java/com/rinsing/geomantia')
def edit(rel,fn):
 p=base/rel;p.write_text(fn(p.read_text(encoding='utf-8')),encoding='utf-8')
edit('systems/city/application/CityBlueprintCompilerService.java',lambda s:s.replace('            if (group.anchorBlock() != null) return group.anchorBlock();\n        CityBlueprint.PlacementRelation placement = group.placementRelation();','            CityBlueprint.PlacementRelation placement = group.placementRelation();'))
def validate(s):
 at=s.index('        Set<String> groupIds = new HashSet<>();')
 s=s[:at]+'''        Set<String> coordinateChildren = new HashSet<>();
        java.util.Map<String,com.rinsing.geomantia.systems.city.domain.model.BlockPoint> compositionOrigins = new HashMap<>();
        for (var composition : blueprint.arrayCompositions()) {
            coordinateChildren.addAll(composition.memberGroupIds());
            if (composition.anchorBlock()!=null) compositionOrigins.put(composition.centerGroupId(),composition.anchorBlock());
        }
        for (var group : blueprint.groups()) {
            boolean nested = coordinateChildren.contains(group.groupId());
            var origin = compositionOrigins.getOrDefault(group.groupId(),group.anchorBlock());
            if (!group.preferredPatchRefs().isEmpty()) continue; // Read legacy saved designs.
            if (!nested && origin==null)
                add(issues,CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,"$.groups",
                        "Root array "+group.groupId()+" requires anchorBlock {x,z}; nested children inherit their parent placement.");
            if (nested && origin!=null)
                add(issues,CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,"$.groups",
                        "Nested array "+group.groupId()+" must omit anchorBlock; place only the root composition.");
            if (group.anchorBlock()!=null && compositionOrigins.containsKey(group.groupId()))
                add(issues,CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,"$.groups",
                        "Specify anchorBlock once, on the root group OR its composition.");
            if (group.placementRelation()!=null)
                add(issues,CityBlueprintReasonCode.CITY_BLUEPRINT_ARRAY_COMPOSITION_INVALID,"$.groups",
                        "Coordinate arrays use anchorBlock and nesting, not placementRelation.");
        }
'''+s[at:];return s
edit('systems/city/application/CityBlueprintValidator.java',validate)
def provider(s):
 s=s.replace('"groupId", string(), "role", string(), "intent", string(), "preferredPatchRefs", nonEmptyArray(string())','"groupId", string(), "role", string(), "intent", string()').replace('"groupId", "role", "intent", "preferredPatchRefs"','"groupId", "role", "intent"')
 s=s.replace('"preferredPatchRefs", nonEmptyArray(string()),\n                "preferredPatchZone", enumeration("CENTER", "NORTH", "EAST", "SOUTH", "WEST"),\n                "placementRelation", placementRelationSchema(), "role", string(),','"anchorBlock", described(object(properties("x", integer(), "z", integer()), "x", "z"), "World block origin of a root array. Nested arrays omit it and inherit parent geometry; no patch selection or automatic relocation."), "role", string(),')
 s=s.replace('"groupId", "groupKind", "preferredPatchRefs", "preferredPatchZone", "role",','"groupId", "groupKind", "role",')
 s=s.replace('"centerGroupId", string(), "memberGroupIds", nonEmptyArray(string()), "layout", rectangleLayoutSchema())','"centerGroupId", string(), "memberGroupIds", nonEmptyArray(string()), "layout", rectangleLayoutSchema(),\n                "anchorBlock", object(properties("x", integer(), "z", integer()), "x", "z"))')
 return s
edit('systems/provider/application/ProviderPlanningToolCatalog.java',provider)
p=Path('country_designer_mcp/src/realm/tools.ts');s=p.read_text(encoding='utf-8')
start=s.index('      preferredPatchRefs:',s.index('function') if 'function' in s else 0);end=s.index('      role:',start)
s=s[:start]+'''      anchorBlock: { ...objectSchema({ x: {type:"integer"}, z: {type:"integer"} }, ["x","z"]),
        description: "顶层阵列世界坐标原点。嵌套子阵列省略，由父组合排布；不选Patch、不自动迁移。" },
'''+s[end:]
s=s.replace('["groupId", "groupKind", "preferredPatchRefs", "preferredPatchZone", "role",','["groupId", "groupKind", "role",')
s=s.replace('centerGroupId: nonEmptyString("父阵列中心的完整 Group。"),','centerGroupId: nonEmptyString("父阵列中心的完整 Group。"),\n      anchorBlock: objectSchema({x:{type:"integer"},z:{type:"integer"}},["x","z"]),')
s=s.replace('intent: nonEmptyString("设计意图及组团关系。"), preferredPatchRefs: { type: "array", minItems: 1, uniqueItems: true, items: { type: "string" } }','intent: nonEmptyString("设计意图及组团关系；后续用明确坐标和嵌套定位。")').replace('["groupId", "role", "intent", "preferredPatchRefs"]','["groupId", "role", "intent"]')
s=s.replace('D3 后自动进入 waiting_for_patch_review，必须按 nextAction 调用 patch_explorer_show_candidates；完成 review 后才允许准备 D4 context。','D3 后直接准备 D4 context；读取地形图，使用明确坐标和嵌套阵列设计，不要求 Top Patch。')
s=s.replace('D3 或必要的 site review 完成后，服务端自动打开 city_d4 Patch Explorer，并返回 sessionId 与 nextAction=patch_explorer_show_candidates；Agent 必须查看所选地貌类型的 Top Patch 后才能准备 D4 context。','D3 或必要的 site review 完成后直接返回 nextAction=city_prepare_d4_blueprint_context。依据地形图和世界坐标设计，不再进行 Top Patch 筛选。')
p.write_text(s,encoding='utf-8')
edit('systems/city/application/queue/CityDesignQueue.java',lambda s:s.replace('|| "PATCH_REVIEW_COMPLETED".equals(reasonCode)','|| "PATCH_REVIEW_COMPLETED".equals(reasonCode) || "D3_COORDINATE_DESIGN_READY".equals(reasonCode)'))
def guide(s):
 s=re.sub(r'guide.addProperty\("placementBoundary", .*?\);', 'guide.addProperty("placementBoundary", "Place each root array or root composition with anchorBlock {x,z} in WORLD block coordinates. Nested children omit coordinates and inherit parent layout. No Patch selection or Top Patch review. Use the D3 terrain map for geographic judgment; coordinates are never snapped to patch centers or relocated for fit. Counts, nesting, dimensions and terrain/collision reports remain explicit. Out-of-bounds members are reported, not moved. CONNECTION requests roads between designed destinations.");',s)
 s=re.sub(r'guide.addProperty\("boundaryPlacement", .*?\);','guide.addProperty("boundaryPlacement", "Use explicit root anchorBlock coordinates. No Patch-boundary placement interface in new D4 designs. Terrain samples remain validation evidence.");',s)
 return s
edit('systems/city/application/CityDesignGuide.java',guide)
