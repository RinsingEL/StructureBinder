package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationProgramIntent;
import com.rinsing.geomantia.systems.city.application.dressing.DecorationProgramIntentPlan;

import java.util.ArrayList;
import java.util.List;

/** Resolves semantic AI palette references into concrete content catalog references before compilation. */
public final class CityDecorationStyleProfileResolver {
    public Resolution resolve(DecorationProgramIntentPlan intentPlan,
                              CityDecorationStyleProfileCatalog.StyleProfile styleProfile) {
        if (!intentPlan.styleProfileId().equals(styleProfile.styleProfileId())) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_ID_MISMATCH: plan="
                    + intentPlan.styleProfileId() + ", loaded=" + styleProfile.styleProfileId());
        }
        if (!intentPlan.styleProfileHash().equals(styleProfile.styleProfileHash())) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_HASH_MISMATCH: plan="
                    + intentPlan.styleProfileHash() + ", loaded=" + styleProfile.styleProfileHash());
        }
        List<DecorationProgramIntent> programs = new ArrayList<>();
        JsonArray resolutions = new JsonArray();
        for (DecorationProgramIntent program : intentPlan.programs()) {
            ResolvedPalette palette = resolvePalette(program, styleProfile);
            programs.add(new DecorationProgramIntent(program.programId(), program.targetArea(),
                    program.coordinateFrame(), program.shape(), program.pattern(), palette.palette(),
                    program.terrainPolicy(), program.conflictPolicy(), program.priority(), program.seed()));
            resolutions.add(palette.trace());
        }
        DecorationProgramIntentPlan resolved = new DecorationProgramIntentPlan(intentPlan.schema(),
                intentPlan.cityId(), intentPlan.catalogHash(), intentPlan.styleProfileId(),
                intentPlan.styleProfileHash(), programs);
        JsonObject trace = new JsonObject();
        trace.addProperty("schema", "city_decoration_style_resolution");
        trace.addProperty("styleProfileId", styleProfile.styleProfileId());
        trace.addProperty("styleProfileHash", styleProfile.styleProfileHash());
        trace.add("programs", resolutions);
        return new Resolution(resolved, trace);
    }

    private ResolvedPalette resolvePalette(DecorationProgramIntent program,
                                           CityDecorationStyleProfileCatalog.StyleProfile styleProfile) {
        List<CompiledDecorationProgram.PaletteSlot> slots = new ArrayList<>();
        JsonObject programTrace = new JsonObject();
        programTrace.addProperty("programId", program.programId());
        JsonArray slotTraces = new JsonArray();
        for (CompiledDecorationProgram.PaletteSlot slot : program.contentPalette().slots()) {
            List<CompiledDecorationProgram.ContentLayer> concreteLayers = new ArrayList<>();
            JsonObject slotTrace = new JsonObject();
            slotTrace.addProperty("paletteSlotId", slot.slotId());
            JsonArray layerTraces = new JsonArray();
            for (CompiledDecorationProgram.ContentLayer layer : slot.layers()) {
                List<CompiledDecorationProgram.ContentEntry> concreteEntries = new ArrayList<>();
                JsonObject layerTrace = new JsonObject();
                layerTrace.addProperty("layerId", layer.layerId());
                JsonArray entries = new JsonArray();
                for (CompiledDecorationProgram.ContentEntry semanticEntry : layer.entries()) {
                    CityDecorationStyleProfileCatalog.Mapping mapping =
                            styleProfile.requireMapping(semanticEntry.contentRef());
                    double variantWeightTotal = mapping.variants().stream()
                            .mapToDouble(CityDecorationStyleProfileCatalog.Variant::weight).sum();
                    JsonObject semanticTrace = new JsonObject();
                    semanticTrace.addProperty("semanticRef", semanticEntry.contentRef());
                    semanticTrace.addProperty("semanticWeight", semanticEntry.weight());
                    JsonArray variants = new JsonArray();
                    for (CityDecorationStyleProfileCatalog.Variant variant : mapping.variants()) {
                        double combinedWeight = semanticEntry.weight() * variant.weight() / variantWeightTotal;
                        concreteEntries.add(new CompiledDecorationProgram.ContentEntry(
                                variant.contentRef(), combinedWeight));
                        JsonObject variantTrace = new JsonObject();
                        variantTrace.addProperty("contentRef", variant.contentRef());
                        variantTrace.addProperty("resolvedWeight", combinedWeight);
                        variants.add(variantTrace);
                    }
                    semanticTrace.add("variants", variants);
                    entries.add(semanticTrace);
                }
                layerTrace.add("entries", entries);
                layerTraces.add(layerTrace);
                concreteLayers.add(new CompiledDecorationProgram.ContentLayer(layer.layerId(), layer.phase(),
                        concreteEntries, layer.required(), layer.dependsOnLayerId()));
            }
            slotTrace.add("layers", layerTraces);
            slotTraces.add(slotTrace);
            slots.add(new CompiledDecorationProgram.PaletteSlot(slot.slotId(), concreteLayers));
        }
        programTrace.add("paletteSlots", slotTraces);
        return new ResolvedPalette(new CompiledDecorationProgram.ContentPalette(slots), programTrace);
    }

    public record Resolution(DecorationProgramIntentPlan resolvedIntent, JsonObject trace) {
    }

    private record ResolvedPalette(CompiledDecorationProgram.ContentPalette palette, JsonObject trace) {
    }
}
