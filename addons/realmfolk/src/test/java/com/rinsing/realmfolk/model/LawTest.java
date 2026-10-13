package com.rinsing.realmfolk.model;

import org.junit.jupiter.api.Test;
import static com.rinsing.realmfolk.model.Society.*;
import static org.junit.jupiter.api.Assertions.*;

class LawTest {
    @Test void realmPolicyChangesFineAndEscalationWithoutRepricingOldCases(){
        var policy=new com.rinsing.realmfolk.design.Design.LawPolicy();policy.damageFine=7;policy.repeatedOffenses=2;
        Journal j=new Journal();Offense old=Law.record(j,"a","house","witness",Crime.DAMAGE,0,1,policy);
        assertEquals(7,old.fine);assertFalse(Law.wanted(j,"a"));
        policy.damageFine=9;Law.record(j,"a","house","witness",Crime.DAMAGE,1,1,policy);
        assertEquals(16,Law.fine(j,"a"));assertEquals(7,old.fine);assertTrue(Law.dangerous(j,"a",policy));
        assertFalse(Law.dangerous(j,"a",new com.rinsing.realmfolk.design.Design.LawPolicy()));
    }
    @Test void ReportingOnePettyTheftDoesNotAuthorizeViolentPursuit(){
        Journal j=new Journal();Offense o=Law.record(j,"a","shop","n",Crime.THEFT,0,1);Law.report(j,o);
        assertTrue(Law.wanted(j,"a"));assertFalse(Law.dangerous(j,"a"));
        Law.record(j,"a","shop","n",Crime.DAMAGE,1,1);Law.record(j,"a","shop","n",Crime.DAMAGE,2,1);
        assertTrue(Law.dangerous(j,"a"));Law.settle(j,"a");assertFalse(Law.dangerous(j,"a"));
    }
    @Test void firstMinorOffenseCanBeRemediedBeforeNationalReputationChanges(){
        Journal j=new Journal();Offense o=Law.record(j,"a","shop","witness",Crime.THEFT,0,1);
        assertFalse(Law.wanted(j,"a"));assertEquals(0,j.standing("a"));assertEquals(-1,j.favor("witness"));
        o.stolenStack="bread";assertTrue(Law.returnGoods(o));assertEquals(0,Law.fine(j,"a"));assertEquals(0,j.standing("a"));
    }
    @Test void severeAssaultCanImmediatelyTriggerGuards(){
        Journal j=new Journal();Law.record(j,"a","house","victim",Crime.ASSAULT,0,1);
        assertTrue(Law.wanted(j,"a"));assertEquals(12,Law.fine(j,"a"));assertTrue(j.standing("a")<0);
    }
    @Test void repeatedUnresolvedDamageEscalates(){
        Journal j=new Journal();for(int i=0;i<3;i++)Law.record(j,"a","house","witness",Crime.DAMAGE,i,1);
        assertTrue(Law.wanted(j,"a"));assertEquals(6,Law.fine(j,"a"));
    }
    @Test void settlingDoesNotMagicallyRepairRelationships(){
        Journal j=new Journal();Law.record(j,"a","house","victim",Crime.ASSAULT,0,1);
        int standing=j.standing("a"),favor=j.favor("victim");Law.settle(j,"a");
        assertFalse(Law.wanted(j,"a"));assertEquals(standing,j.standing("a"));assertEquals(favor,j.favor("victim"));
    }
    @Test void ReportIsIdempotentAndCannotReopenSettledCase(){
        Journal j=new Journal();Offense o=Law.record(j,"a","p","n",Crime.DAMAGE,0,1);Law.report(j,o);int rep=j.standing("a");Law.report(j,o);assertEquals(rep,j.standing("a"));
        Law.settle(j,"a");Law.report(j,o);assertEquals(0,Law.fine(j,"a"));
    }
    @Test void KingdomsDoNotShareBounties(){Journal j=new Journal();Law.record(j,"a","p","n",Crime.ASSAULT,0,1);assertFalse(Law.wanted(j,"b"));Law.settle(j,"b");assertTrue(Law.wanted(j,"a"));}
    @Test void ReturningGoodsDoesNotEraseAssault(){Journal j=new Journal();Offense theft=Law.record(j,"a","p","n",Crime.THEFT,0,1);theft.stolenStack="x";Law.record(j,"a","p","n",Crime.ASSAULT,1,1);Law.returnGoods(theft);assertTrue(Law.wanted(j,"a"));assertEquals(12,Law.fine(j,"a"));}
    @Test void ClosedTheftCannotBeReturnedTwice(){Journal j=new Journal();Offense o=Law.record(j,"a","p","n",Crime.THEFT,0,1);o.stolenStack="x";assertTrue(Law.returnGoods(o));assertFalse(Law.returnGoods(o));}
}
