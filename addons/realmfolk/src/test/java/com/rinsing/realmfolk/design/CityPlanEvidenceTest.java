package com.rinsing.realmfolk.design;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityPlanEvidenceTest {
    private CityPlanning.Submission input() {
        var in=new CityPlanning.Submission();var s=in.plan.overrides;
        s.populationMode="housing";s.residentsPerHouse=2;s.housingOccupancy=.85;
        s.housingTerms=List.of("家庭居住");s.jobs=Map.of("resident",new Design.Quota(0,256,1));
        var p=new Design.Profession();p.workplaceTerms=List.of();in.professions.put("resident",p);return in;
    }
    @Test void rejectsDefaultSynonymsWhenActualHousingUsesDifferentAuthorTerm() {
        var in=input();in.plan.overrides.housingTerms=List.of("住宅");
        assertTrue(assertThrows(IllegalArgumentException.class,()->CityPlanEvidence.validate(in,new Design(),Set.of("家庭居住"))).getMessage().contains("N1"));
        in.plan.overrides.housingTerms=List.of("家庭居住");
        assertDoesNotThrow(()->CityPlanEvidence.validate(in,new Design(),Set.of("家庭居住")));
    }
    @Test void rejectsMissingOccupationSiteBeforeWorldLoad() {
        var in=input();in.professions.get("resident").workplaceTerms=List.of("农田");
        assertTrue(assertThrows(IllegalArgumentException.class,()->CityPlanEvidence.validate(in,new Design(),Set.of("家庭居住","农业生产"))).getMessage().contains("N7"));
        in.professions.get("resident").workplaceTerms=List.of("农业生产");
        assertDoesNotThrow(()->CityPlanEvidence.validate(in,new Design(),Set.of("家庭居住","农业生产")));
    }
}
