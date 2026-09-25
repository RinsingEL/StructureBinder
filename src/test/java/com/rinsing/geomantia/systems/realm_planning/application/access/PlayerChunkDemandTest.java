package com.rinsing.geomantia.systems.realm_planning.application.access;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerChunkDemandTest {
    @Test void deniedDemandDoesNotAcquireSlotAndUnlockRestoresStationaryDemand() {
        var demand=new PlayerChunkDemand();
        var denied=demand.update(1,5,true,p->false);
        assertFalse(denied.before()); assertFalse(denied.after());
        var restored=demand.reconcile(p->true);
        assertEquals(1,restored.size()); assertTrue(restored.get(0).after());
        assertTrue(demand.reconcile(p->true).isEmpty());
        var revoked=demand.reconcile(p->false);
        assertTrue(revoked.get(0).before()); assertFalse(revoked.get(0).after());
    }
    @Test void leavingViewRemovesSuppressedIntentAndSharedDemandStaysUntilVanillaReleases() {
        var demand=new PlayerChunkDemand();
        demand.update(1,2,true,p->false);
        demand.update(1,35,false,p->true);
        assertTrue(demand.reconcile(p->true).isEmpty());
        assertTrue(demand.update(2,1,true,p->true).after());
        var shared=demand.update(2,7,true,p->true);
        assertTrue(shared.before() && shared.after());
        var removed=demand.update(2,35,false,p->true);
        assertTrue(removed.before()); assertFalse(removed.after());
    }
}
