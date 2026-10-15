package com.rinsing.realmfolk.design;

import com.rinsing.realmfolk.life.*;
import com.rinsing.realmfolk.model.Society;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.rinsing.realmfolk.model.Society.*;
import static org.junit.jupiter.api.Assertions.*;

class LivingFacilitiesTest {
    private Society world(){
        Society s=new Society();s.design.active=DesignCodec.defaults();s.design.revision=1;
        var settings=s.design.active.realms.get("default").population;settings.populationMode="housing";settings.housingOccupancy=1.;settings.jobs=Map.of("resident",new Design.Quota(0,256,1));
        s.towns.put("t",new Town("t","r","Town",new Point(0,65,0)));
        var p=new Property("h","t","Home",new Bounds(0,64,0,20,75,20),new Point(2,65,2));p.terms=List.of("住宅");
        p.slots=List.of(p.home,p.home.offset(3,0,0),p.home.offset(6,0,0));FacilityFixtures.physical(p,0);s.properties.put(p.id,p);return s;
    }
    private PopulationPlanner.Plan plan(Society s){return PopulationPlanner.plan(s.design.active,s,s.towns.get("t"),1,1);}
    @Test void guestsPatientsAndUnreachableBedsAreNotHousing(){var s=world();var sites=s.properties.get("h").livingSites;sites.get(0).use="guest";sites.get(1).use="care";sites.get(2).available=false;assertEquals(0,plan(s).summary().housingSlots());assertTrue(plan(s).citizens().isEmpty());}
    @Test void standableSpaceAloneCreatesNoResidents(){var s=world();s.properties.get("h").livingSites=null;assertEquals(0,plan(s).citizens().size());}
    @Test void stableSiteIdentitySurvivesReordering(){var s=world();var before=plan(s).citizens().stream().map(c->c.id).collect(java.util.stream.Collectors.toSet());Collections.reverse(s.properties.get("h").livingSites);assertEquals(before,plan(s).citizens().stream().map(c->c.id).collect(java.util.stream.Collectors.toSet()));}
    @Test void bedLossReassignsExistingPersonWithoutDeletingRelations(){var s=world();var c=plan(s).citizens().get(0);s.citizens.put(c.id,c);s.journal("player").like(c.id,42);String old=c.bedSite;var lost=FacilityAssignments.site(s.properties.get("h"),old);lost.available=false;lost.present=false;assertTrue(FacilityAssignments.repair(s));assertSame(c,s.citizens.get(c.id));assertNotEquals(old,c.bedSite);assertEquals(42,s.journal("player").favor(c.id));}
    @Test void blockedPathsPreserveExistingBedsAndJobsButPreventNewClaims(){
        var s=world();var people=plan(s).citizens();people.forEach(c->s.citizens.put(c.id,c));
        var before=people.stream().map(c->c.bedSite).toList();
        var home=s.properties.get("h");home.livingSites.forEach(site->{site.present=true;site.available=false;});
        var worker=people.get(0);worker.workProperty=home.id;worker.profession=new Design.Profession();worker.profession.workplaceTerms=List.of("工坊生产");
        var job=new LivingSite("work:blocked","work",new Point(18,65,2),new Point(17,65,2),"minecraft:smithing_table");job.present=true;home.livingSites.add(job);worker.workSite=job.id;worker.work=job.approach;
        FacilityAssignments.repair(s);
        assertEquals(before,people.stream().map(c->c.bedSite).toList());assertEquals(job.id,worker.workSite);
        assertTrue(FacilityAssignments.beds(home).isEmpty());assertTrue(FacilityAssignments.jobs(home).isEmpty());
        home.livingSites.forEach(site->site.available=true);FacilityAssignments.repair(s);
        assertEquals(before,people.stream().map(c->c.bedSite).toList());assertEquals(job.id,worker.workSite);
    }
    @Test void oldSaveWithoutFacilityFieldsCanEnterRainAndPopulationPlanning(){
        var s=world();var old=DesignCodec.JSON.fromJson("{\"id\":\"old\",\"name\":\"old\",\"town\":\"t\",\"property\":\"h\",\"role\":\"resident\",\"home\":{\"x\":2,\"y\":65,\"z\":2},\"work\":{\"x\":2,\"y\":65,\"z\":2}}",Citizen.class);
        assertEquals("",old.bedSite);assertEquals("",old.workSite);assertFalse(old.visitor);
        s.citizens.put(old.id,old);old.workSite=null; // Explicit null also occurs in external save editors.
        assertNull(ShelterPlanner.choose(s,old,old.home,p->false,Set.of()));
        assertDoesNotThrow(()->plan(s));
    }
    @Test void duplicateClaimsAreRepairedAndValidClaimsWin(){var s=world();var people=plan(s).citizens();people.forEach(c->s.citizens.put(c.id,c));people.get(1).bedSite=people.get(0).bedSite;FacilityAssignments.repair(s);assertEquals(3,s.citizens.values().stream().map(c->c.bedSite).filter(id->!id.isBlank()).distinct().count());}
    @Test void routineClockDoesNotInvalidatePublicationButFacilityLossDoes(){var s=world();plan(s).citizens().forEach(c->s.citizens.put(c.id,c));var tx=new DesignTransactions(s,1,i->true);var draft=tx.submit(1,new Design.Patch(),List.of("t"),"future_only");String token=tx.preview(draft.id).previewToken();s.citizens.values().forEach(c->c.lastRoutineTick=12345);assertEquals(token,tx.preview(draft.id).previewToken());s.properties.get("h").livingSites.get(0).available=false;assertNotEquals(token,tx.preview(draft.id).previewToken());}
    @Test void scheduleWrapsMidnightAndStaggersResidents(){var job=new Design.Profession();job.restStart=23000;job.restEnd=3000;assertEquals(DailySchedule.Phase.SLEEP,DailySchedule.phase("one",job,0));assertEquals(DailySchedule.Phase.WORK,DailySchedule.phase("one",job,8000));var phases=new HashSet<DailySchedule.Phase>();for(int i=0;i<100;i++)phases.add(DailySchedule.phase("resident-"+i,job,5000));assertTrue(phases.size()>1);}
    @Test void visitorsDoNotConsumePermanentHousingCapacity(){var s=world();var visitor=new Citizen("v","旅人","t","h","resident",new Point(99,65,0),new Point(99,65,0));visitor.visitor=true;s.citizens.put(visitor.id,visitor);assertEquals(3,plan(s).citizens().size());}
    @Test void arrivalUsesActualFeetPositionRatherThanFlooredBlockPosition(){
        assertTrue(LivingSite.withinUseDistance(49.85768593777051,-57,6.013003383701995,new Point(49,-57,4)));
        assertFalse(LivingSite.withinUseDistance(49.5,-57,7,new Point(49,-57,4)));
    }
    @Test void visitorPlacesMustBeExplicitPublicActivityUses(){
        var s=world();s.properties.get("h").publicPoints=List.of(new Point(1,62,1));
        var square=new Property("square","t","广场",new Bounds(30,64,0,40,70,10),new Point(32,65,2));square.terms=List.of("广场");square.publicPoints=List.of(square.home);s.properties.put(square.id,square);
        assertEquals(List.of(square),VisitorPlaces.select(s,s.towns.get("t")));
        square.owner="player:someone";assertTrue(VisitorPlaces.select(s,s.towns.get("t")).isEmpty());
    }
    @Test void visitorsUseFreeIndoorWorkOrMeetingSitesRatherThanOutdoorHomes(){
        var s=world();var visitor=new Citizen("v","旅人","t","square","resident",new Point(99,65,0),new Point(99,65,0));visitor.visitor=true;
        var shop=new Property("shop","t","商店",new Bounds(30,64,0,40,70,10),new Point(32,65,2));FacilityFixtures.physical(shop,2);s.properties.put(shop.id,shop);
        var occupied=shop.livingSites.get(0).approach;
        assertEquals(shop.livingSites.get(1).approach,ShelterPlanner.choose(s,visitor,visitor.home,p->p.x()<40,Set.of(occupied)));
        shop.owner="player:owner";assertNull(ShelterPlanner.choose(s,visitor,visitor.home,p->p.x()<40,Set.of()));
    }
}
