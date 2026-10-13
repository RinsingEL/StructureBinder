from pathlib import Path
base=Path('src/main/java/com/rinsing/geomantia')
def edit(rel,fn):
 p=base/rel;s=p.read_text(encoding='utf-8');p.write_text(fn(s),encoding='utf-8')
def domain(s):
 # Preserve existing Java constructors and legacy saved blueprints.
 start=s.index('    public record Group('); end=s.index(') {',start)
 sig=s[start:end]; params=sig[sig.index('(')+1:]; names=[v.strip().split()[-1] for v in params.split(',')]
 old='        public Group('+params+') {\n            this('+','.join(names)+',null);\n        }\n'
 s=s[:end]+',\n            com.rinsing.geomantia.systems.city.domain.model.BlockPoint anchorBlock'+s[end:]
 pos=s.index(') {',start)+3;s=s[:pos]+'\n'+old+s[pos:]
 start=s.index('    public record ArrayComposition(');end=s.index(') {',start)
 s=s[:end]+',\n            com.rinsing.geomantia.systems.city.domain.model.BlockPoint anchorBlock'+s[end:]
 pos=s.index(') {',start)+3
 s=s[:pos]+'''\n        public ArrayComposition(String compositionId,String algorithmProfileRef,String centerGroupId,List<String> memberGroupIds,RectangleLayout layout) {
            this(compositionId,algorithmProfileRef,centerGroupId,memberGroupIds,layout,null);
        }
'''+s[pos:];return s
edit(Path('systems/city/domain/blueprint/CityBlueprint.java'),domain)
def codec(s):
 s=s.replace('item.add("preferredPatchRefs", strings(group.preferredPatchRefs()));','if (!group.preferredPatchRefs().isEmpty()) item.add("preferredPatchRefs", strings(group.preferredPatchRefs()));\n            if (group.anchorBlock() != null) item.add("anchorBlock", pointJson(group.anchorBlock()));')
 s=s.replace('item.addProperty("preferredPatchZone", group.preferredPatchZone().name());','if (!group.preferredPatchRefs().isEmpty()) item.addProperty("preferredPatchZone", group.preferredPatchZone().name());')
 s=s.replace('if (composition.layout() != null)', 'if (composition.anchorBlock() != null) item.add("anchorBlock", pointJson(composition.anchorBlock()));\n            if (composition.layout() != null)')
 s=s.replace('Set.of("groupId", "groupKind", "preferredPatchRefs", "preferredPatchZone",','Set.of("groupId", "groupKind", "anchorBlock", "preferredPatchRefs", "preferredPatchZone",')
 s=s.replace('Set.of("placementRelation", "connectionPlan", "fillPoolRef", "fillPools", "structureCount", "layout")','Set.of("anchorBlock", "preferredPatchRefs", "preferredPatchZone", "placementRelation", "connectionPlan", "fillPoolRef", "fillPools", "structureCount", "layout")')
 s=s.replace('nonEmptyStringList(item, "preferredPatchRefs", path + ".preferredPatchRefs"),','item.has("preferredPatchRefs") ? nonEmptyStringList(item, "preferredPatchRefs", path + ".preferredPatchRefs") : List.of(),')
 s=s.replace('enumValue(item, "preferredPatchZone", CityBlueprint.PreferredPatchZone.class, path),','item.has("preferredPatchZone") ? enumValue(item, "preferredPatchZone", CityBlueprint.PreferredPatchZone.class, path) : CityBlueprint.PreferredPatchZone.CENTER,')
 s=s.replace('"memberGroupIds", "layout");','"memberGroupIds", "layout", "anchorBlock");')
 s=s.replace('exactFields(item, fields, Set.of("layout"), path);','exactFields(item, fields, Set.of("layout", "anchorBlock"), path);')
 s=s.replace('item.has("layout") ? layout(requiredObject(item,"layout",path+".layout"),path+".layout") : null));','item.has("layout") ? layout(requiredObject(item,"layout",path+".layout"),path+".layout") : null,\n                    item.has("anchorBlock") ? point(item.getAsJsonObject("anchorBlock"),path+".anchorBlock") : null));')
 s=s.replace('if (FORBIDDEN_FIELDS.contains(entry.getKey()) && !landscapeSeedCoordinate)', '''boolean arrayCoordinate = (path.matches("\\\\$\\\\.(groups|arrayCompositions)\\\\[[0-9]+\\\\]") && entry.getKey().equals("anchorBlock"))
                    || (path.matches("\\\\$\\\\.(groups|arrayCompositions)\\\\[[0-9]+\\\\]\\\\.anchorBlock") && Set.of("x","z").contains(entry.getKey()));
            if (FORBIDDEN_FIELDS.contains(entry.getKey()) && !landscapeSeedCoordinate && !arrayCoordinate)''')
 pos=s.index('    private static void rejectForbidden')
 s=s[:pos]+'''    private static com.rinsing.geomantia.systems.city.domain.model.BlockPoint point(JsonObject value,String path) {
        exactFields(value,Set.of("x","z"),path);
        for (String key : List.of("x","z")) {
            double n=value.get(key).getAsDouble();
            if (!Double.isFinite(n) || n != Math.rint(n) || Math.abs(n)>30000000)
                throw new IllegalArgumentException("CITY_COORDINATE_INVALID: "+path+"."+key);
        }
        return new com.rinsing.geomantia.systems.city.domain.model.BlockPoint(value.get("x").getAsInt(),value.get("z").getAsInt());
    }
    private static JsonObject pointJson(com.rinsing.geomantia.systems.city.domain.model.BlockPoint point) {
        JsonObject value=new JsonObject();value.addProperty("x",point.x());value.addProperty("z",point.z());return value;
    }

'''+s[pos:];return s
edit(Path('systems/city/application/CityBlueprintCodec.java'),codec)
def compiler(s):
 s=s.replace('catalog.composition(group.compositionProfileRef());','if (preferredPatches.isEmpty()) preferredPatches.addAll(planningPatches);\n            catalog.composition(group.compositionProfileRef());')
 s=s.replace('CityBlueprint.PlacementRelation placement = group.placementRelation();','if (group.anchorBlock() != null) return group.anchorBlock();\n        CityBlueprint.PlacementRelation placement = group.placementRelation();')
 s=s.replace('BlockPoint chosen = patchPlacementOrigin(root, patches, patchStepBlocks, fullPlanningBounds);','''BlockPoint explicitOrigin = blueprint.arrayCompositions().stream()
                    .filter(c -> c.centerGroupId().equals(root.groupId()) && c.anchorBlock()!=null)
                    .map(CityBlueprint.ArrayComposition::anchorBlock).findFirst().orElse(root.anchorBlock());
            BlockPoint chosen = explicitOrigin != null ? explicitOrigin : patchPlacementOrigin(root, patches, patchStepBlocks, fullPlanningBounds);''')
 s=s.replace('if (root.placementRelation() != null && root.placementRelation().kind()', 'if (explicitOrigin == null && root.placementRelation() != null && root.placementRelation().kind()')
 s=s.replace('if ("COMPACT".equals(catalog.algorithm(root.algorithmProfileRef()))','if (explicitOrigin == null && "COMPACT".equals(catalog.algorithm(root.algorithmProfileRef()))')
 s=s.replace('if (relation.relationKind() != CityBlueprint.RelationKind.ADJACENCY) continue;', 'if (explicitOrigin != null || relation.relationKind() != CityBlueprint.RelationKind.ADJACENCY) continue;')
 return s
edit(Path('systems/city/application/CityBlueprintCompilerService.java'),compiler)
def session(s):
 s=s.replace('requireFields(group, Set.of("groupId", "role", "intent", "preferredPatchRefs"));','requireFields(group, group.has("preferredPatchRefs") ? Set.of("groupId", "role", "intent", "preferredPatchRefs") : Set.of("groupId", "role", "intent"));')
 s=s.replace('JsonArray patchRefs = requiredArray(group, "preferredPatchRefs");','JsonArray patchRefs = array(group, "preferredPatchRefs");').replace('if (patchRefs.isEmpty()) throw invalid(id + ": choose at least one preview patch.");','')
 return s
edit(Path('systems/city/application/CityDesignSession.java'),session)
def http(s):
 s=s.replace('JsonObject patchReviewEvidence = new CityD4PatchReviewService(debugRoot())\n                    .requireReviewed(runId, citySeedId);','JsonObject patchReviewEvidence = null;')
 start=s.index('        JsonObject openRequest = new JsonObject();',s.index('private JsonObject beginCityD4PatchReview'))
 end=s.index('        return response;',start)
 s=s[:start]+'''        addAgentNextAction(response, "waiting_for_agent", "D3_COORDINATE_DESIGN_READY",
                "city_prepare_d4_blueprint_context", "");
        cityDesignQueue.onAgentWorkflowState(runId, citySeedId,
                "waiting_for_agent", "D3_COORDINATE_DESIGN_READY", "");
'''+s[end:];return s
edit(Path('platform/http/RealmPlanningHttpController.java'),http)
edit(Path('systems/city/application/queue/CityDesignQueue.java'),lambda s:s.replace('case "PATCH_REVIEW_COMPLETED" ->','case "PATCH_REVIEW_COMPLETED", "D3_COORDINATE_DESIGN_READY" ->').replace('case WAITING_FOR_PATCH_REVIEW -> "patch_explorer_show_candidates";','case WAITING_FOR_PATCH_REVIEW -> "city_prepare_d4_blueprint_context";'))
