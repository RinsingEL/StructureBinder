package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationProgramIntent;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationProgramIntentPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityDecorationStyleProfileResolverTest {
    @Test
    void resolvesSemanticPaletteEntriesToWeightedConcreteContent() {
        DecorationProgramIntentPlan intent = new DecorationProgramIntentPlan(
                DecorationProgramIntentPlan.SCHEMA, "city_test", "catalog_hash", "forest_village", "style_hash",
                List.of(semanticProgram()));
        CityDecorationStyleProfileCatalog.StyleProfile profile = new CityDecorationStyleProfileCatalog.StyleProfile(
                "forest_village", "style_hash", Map.of("market_stall",
                new CityDecorationStyleProfileCatalog.Mapping("market_stall", List.of(
                        new CityDecorationStyleProfileCatalog.Variant("city:prefab/oak_stall", 1.0),
                        new CityDecorationStyleProfileCatalog.Variant("city:prefab/spruce_stall", 3.0)))));

        CityDecorationStyleProfileResolver.Resolution resolution = new CityDecorationStyleProfileResolver()
                .resolve(intent, profile);
        List<CompiledDecorationProgram.ContentEntry> entries = resolution.resolvedIntent().programs().get(0)
                .contentPalette().slots().get(0).entries();

        assertEquals(List.of("city:prefab/oak_stall", "city:prefab/spruce_stall"),
                entries.stream().map(CompiledDecorationProgram.ContentEntry::contentRef).toList());
        assertEquals(0.5, entries.get(0).weight());
        assertEquals(1.5, entries.get(1).weight());
        assertEquals("market_stall", resolution.trace().getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonArray("paletteSlots").get(0).getAsJsonObject().getAsJsonArray("layers").get(0)
                .getAsJsonObject().getAsJsonArray("entries").get(0)
                .getAsJsonObject().get("semanticRef").getAsString());
    }

    @Test
    void rejectsAPlanProducedForAChangedStyleProfile() {
        DecorationProgramIntentPlan intent = new DecorationProgramIntentPlan(
                DecorationProgramIntentPlan.SCHEMA, "city_test", "catalog_hash", "forest_village", "stale_hash",
                List.of(semanticProgram()));
        CityDecorationStyleProfileCatalog.StyleProfile profile = new CityDecorationStyleProfileCatalog.StyleProfile(
                "forest_village", "current_hash", Map.of("market_stall",
                new CityDecorationStyleProfileCatalog.Mapping("market_stall", List.of(
                        new CityDecorationStyleProfileCatalog.Variant("city:prefab/oak_stall", 1.0)))));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new CityDecorationStyleProfileResolver().resolve(intent, profile));
        assertEquals("CITY_DECORATION_STYLE_PROFILE_HASH_MISMATCH: plan=stale_hash, loaded=current_hash",
                failure.getMessage());
    }

    @Test
    void resolvesBundledAgricultureCommercialAndCivicSemantics(@TempDir Path temp) throws Exception {
        Path root = CityDecorationDefaultCatalogBootstrap.ensureInstalled(
                temp.resolve("config/geomantia/city_decoration"));
        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader(state -> {
        }).load(root);
        CityDecorationStyleProfileCatalog.StyleProfile profile =
                new CityDecorationStyleProfileCatalogLoader().load(root, catalog)
                        .requireProfile("medieval_coastal");
        DecorationProgramIntentPlan intent = new DecorationProgramIntentPlan(
                DecorationProgramIntentPlan.SCHEMA, "city_test", catalog.catalogHash(),
                profile.styleProfileId(), profile.styleProfileHash(),
                List.of(semanticProgram("agriculture_field_detail"),
                        semanticProgram("commercial_goods"), semanticProgram("civic_plaza")));

        CityDecorationStyleProfileResolver.Resolution resolution =
                new CityDecorationStyleProfileResolver().resolve(intent, profile);

        assertEquals(Set.of("geomantia:decoration/scarecrow_02", "geomantia:decoration/haystack_01",
                        "geomantia:decoration/farm_tool_rack_01"),
                resolvedContentRefs(resolution, 0));
        assertEquals(Set.of("geomantia:decoration/crate_cluster_01",
                        "geomantia:decoration/barrel_cluster_01"),
                resolvedContentRefs(resolution, 1));
        assertEquals(Set.of("geomantia:decoration/banner_post_01",
                        "geomantia:decoration/lantern_post_01",
                        "geomantia:decoration/street_bench_01"),
                resolvedContentRefs(resolution, 2));
    }

    private static DecorationProgramIntent semanticProgram() {
        return semanticProgram("market_stall");
    }

    private static DecorationProgramIntent semanticProgram(String semanticRef) {
        return new DecorationProgramIntent(semanticRef + "_points",
                new DecorationProgramIntent.TargetArea("patch", "market_patch", 0),
                new DecorationProgramIntent.CoordinateFrameIntent("target_centroid", "patch_long_axis", 0, 0, 0),
                new CompiledDecorationProgram.TargetMaskShape(),
                new CompiledDecorationProgram.GridRepeatPattern("stall", 4, 4, 0, 0),
                new CompiledDecorationProgram.ContentPalette(List.of(
                        new CompiledDecorationProgram.PaletteSlot("stall", CompiledDecorationProgram.Phase.MAJOR,
                                List.of(new CompiledDecorationProgram.ContentEntry(semanticRef, 2.0)), true))),
                new CompiledDecorationProgram.TerrainPolicy(2, false,
                        CompiledDecorationProgram.InvalidTerrainAction.CLIP),
                new CompiledDecorationProgram.ConflictPolicy(CompiledDecorationProgram.ConflictAction.SKIP, 1),
                10, 42L);
    }

    private static Set<String> resolvedContentRefs(CityDecorationStyleProfileResolver.Resolution resolution,
                                                   int programIndex) {
        return resolution.resolvedIntent().programs().get(programIndex).contentPalette().slots().get(0)
                .entries().stream().map(CompiledDecorationProgram.ContentEntry::contentRef)
                .collect(java.util.stream.Collectors.toSet());
    }
}
