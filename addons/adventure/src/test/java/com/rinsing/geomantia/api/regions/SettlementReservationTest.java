package com.rinsing.geomantia.api.regions;

import org.junit.jupiter.api.Test;
import net.minecraft.nbt.CompoundTag;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class SettlementReservationTest {
    private SettlementReservationApi.Reservation value(String id,String kind) {return new SettlementReservationApi.Reservation(id,"minecraft:overworld",kind,new RegionBounds(-20,-20,20,20));}
    @Test void settlementsAndDangerSitesExcludeEachOtherInEitherOrderAndSurviveSave() {
        for(String first:new String[]{"village","adventure"}) {
            var store=new SettlementReservationApi.Store();var a=value("a",first);assertTrue(store.reserve(a));assertTrue(store.reserve(a));
            assertFalse(store.reserve(value("b",first.equals("village")?"adventure":"village")));
            var restored=SettlementReservationApi.Store.load(store.save(new CompoundTag()));
            assertFalse(restored.reserve(value("b","suburb")));assertFalse(restored.intersects("minecraft:the_nether",a.bounds(),""));
        }
    }
    @Test void simultaneousPlanningHasOnlyOneWinner() throws Exception {
        var store=new SettlementReservationApi.Store();var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=executor.submit(()->{gate.await();return store.reserve(value("village","village"));});
            var b=executor.submit(()->{gate.await();return store.reserve(value("dungeon","adventure"));});gate.countDown();
            assertNotEquals(a.get(),b.get());assertEquals(1,store.snapshot().size());
        }finally{executor.shutdownNow();}
    }
}
