from pathlib import Path
p=Path('src/main/java/com/rinsing/geomantia/systems/city/application/CityBlueprintCompilerService.java')
s=p.read_text(encoding='utf-8').replace('catalog.explicitCityRoles()?Set.copyOf(group.requiredStructureRefs()):Set.of()', 'catalog.explicitCityRoles() ? catalog.primaryStructures() : Set.of()').replace('if(catalog.explicitCityRoles()) result.addAll(state.group().requiredStructureRefs());', '// Required supports may repeat when explicitly selected in a fill pool.\n        // Only declared district cores are unique in the composed city.\n        if(catalog.explicitCityRoles()) result.addAll(catalog.primaryStructures());')
p.write_text(s,encoding='utf-8')
p=Path('src/test/java/com/rinsing/geomantia/systems/city/application/CityBlueprintCompilerServiceTest.java')
s=p.read_text(encoding='utf-8'); pos=s.index('    @Test void coreTemplatesInAnOldFillPool')
s=s[:pos]+'''    @Test void explicitDistrictRepeatsRequiredSupportFromSelectedPoolButNotCore() throws Exception {
        var fixture = acceptedFixture("required_support_repeat", "city:required_support_repeat", 12, 12, "SMALL",
                CityBlueprintCompilerServiceTest::configureCoarseCenteredGrid, bp -> {
                    var group = bp.getAsJsonArray("groups").get(0).getAsJsonObject();
                    group.addProperty("algorithmProfileRef", "algorithm:grid");
                    group.addProperty("structureCount", 6);
                    group.add("requiredStructureRefs", JsonParser.parseString(
                            "['geomantia:town_hall','geomantia:civic_support']"));
                    bp.add("districtDesigns", JsonParser.parseString(
                            "[{districtId:'admin',coreGroupId:'civic',coreStructureRef:'geomantia:town_hall',groupIds:['civic']}]"));
                });
        var result = new CityBlueprintCompilerService().compile(temporary, fixture.runId(), fixture.cityId());
        assertTrue(result.ok(), result.message());
        var anchors = result.structureAnchorPlan().getAsJsonArray("anchors").asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        assertEquals(6, anchors.size(), result.compileTrace().toString());
        assertEquals(1, anchors.stream().filter(a -> "geomantia:town_hall".equals(
                a.get("blueprintStructureRef").getAsString())).count());
        assertEquals(5, anchors.stream().filter(a -> "geomantia:civic_support".equals(
                a.get("blueprintStructureRef").getAsString())).count());
    }

'''+s[pos:];p.write_text(s,encoding='utf-8')
