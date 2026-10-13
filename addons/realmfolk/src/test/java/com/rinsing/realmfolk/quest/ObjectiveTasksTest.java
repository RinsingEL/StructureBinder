package com.rinsing.realmfolk.quest;

import com.google.gson.JsonParser;
import com.rinsing.realmfolk.design.DesignCodec;
import com.rinsing.realmfolk.model.Society;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectiveTasksTest {
    private ObjectiveTasks.Catalog catalog()throws Exception{return DesignCodec.decode(JsonParser.parseString(Files.readString(Path.of("examples/objective-packs/academy-ars/catalog.json"))),ObjectiveTasks.Catalog.class);}
    private ObjectiveTasks.Instances instances()throws Exception{return DesignCodec.decode(JsonParser.parseString(Files.readString(Path.of("examples/objective-packs/academy-ars/quests.json"))),ObjectiveTasks.Instances.class);}
    private Map<String,ObjectiveTasks.Frozen> compiled()throws Exception{return ObjectiveTasks.compile(catalog(),instances(),x->true,x->true,x->true);}
    @Test void authoredAliasSelectsRealAdapterAndAiCannotEscalate()throws Exception{
        var c=catalog();var i=instances();assertEquals(4,compiled().size());
        i.quests.get("academy:glyph_apprentice").targets.add("ars_nouveau:glyph_explosion");assertThrows(IllegalArgumentException.class,()->ObjectiveTasks.compile(c,i,x->true,x->true,x->true));
        var bad=instances();bad.quests.get("academy:glyph_apprentice").count=6;assertThrows(IllegalArgumentException.class,()->ObjectiveTasks.compile(c,bad,x->true,x->true,x->true));
    }
    @Test void knownSetCountsDistinctEligibleGlyphsAndCanExcludePreexistingLearning()throws Exception{
        var f=compiled().get("academy:glyph_apprentice");var p=ObjectiveTasks.accept(f,"mentor","realm",Set.of("ars_nouveau:glyph_light"));
        assertEquals(1,ObjectiveTasks.learned(p,Set.of("ars_nouveau:glyph_light","unrelated")));
        f.type.countExisting=false;p=ObjectiveTasks.accept(f,"mentor","realm",Set.of("ars_nouveau:glyph_light"));
        assertEquals(1,ObjectiveTasks.learned(p,Set.of("ars_nouveau:glyph_light","ars_nouveau:glyph_heal")));
    }
    @Test void noLearningIsNotCompletedEvenIfAnInventoryContainsGlyphItems()throws Exception{
        var p=ObjectiveTasks.accept(compiled().get("academy:glyph_apprentice"),"mentor","realm",Set.of());
        assertEquals(0,ObjectiveTasks.learned(p,Set.of()));
        assertThrows(IllegalArgumentException.class,()->ObjectiveTasks.complete(p,"mentor",0));
        assertEquals("ACTIVE",p.state);
    }
    @Test void wrongTargetsDuplicateDeathsAndDoneEventsDoNotCount()throws Exception{
        var p=ObjectiveTasks.accept(compiled().get("academy:patrol"),"mentor","realm",Set.of());
        assertFalse(ObjectiveTasks.event(p,"realmfolk:kill_entities","minecraft:cow","cow"));
        assertTrue(ObjectiveTasks.event(p,"realmfolk:kill_entities","minecraft:zombie","one"));
        assertFalse(ObjectiveTasks.event(p,"realmfolk:kill_entities","minecraft:zombie","one"));
        assertTrue(ObjectiveTasks.event(p,"realmfolk:kill_entities","minecraft:zombie","two"));
        ObjectiveTasks.complete(p,"mentor",p.count);
        assertFalse(ObjectiveTasks.event(p,"realmfolk:kill_entities","minecraft:zombie","three"));
        assertThrows(IllegalArgumentException.class,()->ObjectiveTasks.complete(p,"mentor",p.count));
    }
    @Test void originalIssuerAndFrozenDefinitionSurviveSaveAndCatalogChanges()throws Exception{
        var defs=compiled();var p=ObjectiveTasks.accept(defs.get("academy:copying"),"mentor","realm",Set.of());
        defs.get("academy:copying").quest.count=10;assertEquals(4,p.definition.quest.count);
        var j=new Society.Journal();j.objectiveTasks.put(p.definition.id,p);var restored=DesignCodec.copy(j,Society.Journal.class);
        assertThrows(IllegalArgumentException.class,()->ObjectiveTasks.complete(restored.objectiveTasks.get(p.definition.id),"other",4));
        ObjectiveTasks.complete(restored.objectiveTasks.get(p.definition.id),"mentor",4);assertEquals("DONE",restored.objectiveTasks.get(p.definition.id).state);
    }
    @Test void legacyJournalLoadsWithEmptyObjectiveLedger(){assertTrue(DesignCodec.JSON.fromJson("{\"balance\":32}",Society.Journal.class).objectiveTasks.isEmpty());}
    @Test void consumptionAndKillingAreSeparateSignals()throws Exception{
        var p=ObjectiveTasks.accept(compiled().get("academy:rations"),"mentor","realm",Set.of());
        assertFalse(ObjectiveTasks.event(p,"realmfolk:kill_entities","minecraft:bread","a"));
        assertTrue(ObjectiveTasks.event(p,"realmfolk:consume_items","minecraft:bread","b"));
    }
}
