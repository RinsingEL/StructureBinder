package com.rinsing.geomantia.systems.city.application.landuse;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CityRoadGradeProfileTest {
    @Test void cutsHighSideBeforeDropAndFillsLowSideWithoutMovingEndpoints() {
        int[] terrain = new int[145];
        Arrays.fill(terrain, 0, 72, 80);
        Arrays.fill(terrain, 72, terrain.length, 56);
        var result = CityRoadGradeProfile.solve(terrain, 12, 12, 3, Map.of(0,80,144,56));
        assertTrue(result.feasible());
        int[] grade = result.heights();
        assertEquals(80, grade[0]); assertEquals(56, grade[144]);
        assertTrue(grade[71] < terrain[71]);
        assertTrue(grade[72] > terrain[72]);
        for (int i=0;i<grade.length;i++) {
            assertTrue(Math.abs(grade[i]-terrain[i]) <= 12);
            if (i>0) assertTrue(Math.abs(grade[i]-grade[i-1]) <= (i%3==0 ? 1 : 0));
        }
        assertArrayEquals(grade, CityRoadGradeProfile.solve(terrain,12,12,3,Map.of(0,80,144,56)).heights());
    }
    @Test void refusesToSacrificePinnedEntrance() {
        int[] terrain = new int[73];
        Arrays.fill(terrain,0,36,80); Arrays.fill(terrain,36,73,56);
        var result=CityRoadGradeProfile.solve(terrain,12,12,3,Map.of(35,80,36,56));
        assertFalse(result.feasible()); assertArrayEquals(terrain,result.heights());
    }
    @Test void preservesFlatNegativeHeightAndDoesNotLeakMutableArray() {
        int[] terrain={-12,-12,-12,-12};
        var result=CityRoadGradeProfile.solve(terrain,12,12,3,Map.of());
        assertTrue(result.feasible()); result.heights()[0]=99;
        assertEquals(-12,result.heights()[0]);
    }
}
