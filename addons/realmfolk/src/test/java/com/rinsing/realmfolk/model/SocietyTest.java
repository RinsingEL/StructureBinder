package com.rinsing.realmfolk.model;

import org.junit.jupiter.api.Test;
import static com.rinsing.realmfolk.model.Society.*;
import static org.junit.jupiter.api.Assertions.*;

class SocietyTest {
    @Test void SmallerHouseOverridesPublicTownAreaAndChecksHeightAndDimension(){
        Society s=new Society();s.towns.put("t",new Town("t","r","Town",new Point(0,64,0)));
        Property square=new Property("square","t","Square",new Bounds(-20,60,-20,20,90,20),new Point(0,64,0));
        Property house=new Property("house","t","House",new Bounds(0,64,0,5,70,5),new Point(1,65,1));s.properties.put(square.id,square);s.properties.put(house.id,house);
        assertEquals("house",s.propertyAt("minecraft:overworld",new Point(1,65,1)).orElseThrow().id);
        assertEquals("square",s.propertyAt("minecraft:overworld",new Point(1,80,1)).orElseThrow().id);
        assertTrue(s.propertyAt("minecraft:the_nether",new Point(1,65,1)).isEmpty());
    }
    @Test void PlayersOwnOnlyTheirOwnDeed(){Property p=new Property("p","t","P",new Bounds(0,0,0,1,1,1),new Point(0,0,0));p.owner="player:alice";assertTrue(p.owns("alice"));assertFalse(p.owns("bob"));}
    @Test void InvalidBoundsAreRejected(){assertThrows(IllegalArgumentException.class,()->new Bounds(10,0,0,0,1,1));}
}
