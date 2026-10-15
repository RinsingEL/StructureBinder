package com.rinsing.realmfolk.design;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.api.planning.*;
import com.rinsing.geomantia.systems.provider.application.PlanningExtensionRegistry;
import com.rinsing.realmfolk.server.CityPlanningExtension;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanningApiCompatibilityTest {
    @Test void realHostAcceptsAddonToolsWithoutOptionalHarnessOrMap(){
        var definitions=new CityPlanningExtension(null).tools();
        PlanningExtension addon=new PlanningExtension(){
            public String id(){return "realmfolk:city_society";}
            public String version(){return "test";}
            public String title(){return "居民与城市商贸";}
            public List<PlanningTool> tools(){return definitions;}
            public boolean isComplete(PlanningExtensionContext c){return false;}
            public JsonObject prepare(PlanningExtensionContext c){return new JsonObject();}
            public JsonObject execute(PlanningExtensionContext c,String tool,JsonObject args){return new JsonObject();}
        };
        assertEquals(10,definitions.size());
        assertTrue(definitions.stream().anyMatch(t->t.name().equals("realmfolk_quick_plan")));
        assertDoesNotThrow(()->new PlanningExtensionRegistry(List.of(addon),Runnable::run));
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.rinsing.geomantia.harness.addons.HarnessMod"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.rinsing.geomantia.map.addons.AdventureMapMod"));
    }
}
