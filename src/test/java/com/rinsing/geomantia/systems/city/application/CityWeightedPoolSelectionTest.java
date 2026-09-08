package com.rinsing.geomantia.systems.city.application;
import org.junit.jupiter.api.Test;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CityWeightedPoolSelectionTest {
    @Test void stableWeightedRollPrefersHighWeightWithoutDroppingFallbackPools() {
        var pools=List.of(new CityBlueprint.WeightedPool("a",3),new CityBlueprint.WeightedPool("b",1));
        int selectedA=0;
        for(int i=0;i<2000;i++) {
            var order=CityWeightedPoolSelection.order(pools,42,"district:unit",i);
            assertEquals(order,CityWeightedPoolSelection.order(pools,42,"district:unit",i));
            assertEquals(Set.of("a","b"),new HashSet<>(order));
            if(order.get(0).equals("a")) selectedA++;
        }
        assertTrue(selectedA>1400 && selectedA<1600,"weighted sample="+selectedA);
    }
}
