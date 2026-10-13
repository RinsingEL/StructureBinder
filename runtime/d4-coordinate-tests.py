from pathlib import Path
p=Path('src/test/java/com/rinsing/geomantia/systems/city/application/CityBlueprintCompilerServiceTest.java');s=p.read_text(encoding='utf-8');i=s.index('    @Test void explicitDistrictRepeatsRequiredSupport')
s=s[:i]+'''    @Test void coordinateArrayUsesExactWorldOriginWithoutPatchSelection() throws Exception {
        var fixture = acceptedFixture("coordinate_origin", "city:coordinate_origin", 12, 12, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, bp -> {
                    var group=bp.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.remove("preferredPatchRefs");group.remove("preferredPatchZone");
                    group.add("anchorBlock",JsonParser.parseString("{x:-111,z:-95}"));
                    group.addProperty("algorithmProfileRef","algorithm:grid");group.addProperty("structureCount",3);
                    group.add("layout",JsonParser.parseString("{columns:2,gapBlocks:8}"));
                });
        var result=new CityBlueprintCompilerService().compile(temporary,fixture.runId(),fixture.cityId());
        assertTrue(result.ok(),result.message());
        var anchors=result.structureAnchorPlan().getAsJsonArray("anchors");assertEquals(3,anchors.size());
        var box=anchors.get(0).getAsJsonObject().getAsJsonObject("plannedFootprint");
        assertEquals(-111,box.get("minX").getAsInt());assertEquals(-95,box.get("minZ").getAsInt());
    }

    @Test void coordinateCompositionTranslatesAllNestedArraysExactly() throws Exception {
        java.util.List<JsonObject> first = null;
        for (int shift : List.of(0,7)) {
            var fixture=acceptedFixture("coordinate_nested"+shift,"city:coordinate_nested"+shift,12,12,"SMALL",
                    CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid,bp->{
                        var groups=bp.getAsJsonArray("groups");var root=groups.get(0).getAsJsonObject();
                        root.remove("preferredPatchRefs");root.remove("preferredPatchZone");
                        root.addProperty("algorithmProfileRef","algorithm:grid");root.addProperty("structureCount",2);
                        root.add("layout",JsonParser.parseString("{columns:2,gapBlocks:8}"));
                        var child=root.deepCopy();child.addProperty("groupId","small");child.addProperty("priority","STANDARD");groups.add(child);
                        bp.add("arrayCompositions",JsonParser.parseString("[{compositionId:'campus',algorithmProfileRef:'algorithm:grid',centerGroupId:'civic',memberGroupIds:['small'],layout:{columns:2,gapBlocks:12},anchorBlock:{x:"+(-111+shift)+",z:-95}}]"));
                    });
            var result=new CityBlueprintCompilerService().compile(temporary,fixture.runId(),fixture.cityId());
            assertTrue(result.ok(),result.message());
            var anchors=result.structureAnchorPlan().getAsJsonArray("anchors").asList().stream().map(JsonElement::getAsJsonObject).toList();
            assertEquals(4,anchors.size());
            if(first==null) first=anchors;
            else for(int j=0;j<anchors.size();j++) {
                var a=first.get(j).getAsJsonObject("plannedFootprint");var b=anchors.get(j).getAsJsonObject("plannedFootprint");
                assertEquals(7,b.get("minX").getAsInt()-a.get("minX").getAsInt());
                assertEquals(a.get("minZ"),b.get("minZ"));
            }
        }
    }

'''+s[i:];p.write_text(s,encoding='utf-8')
