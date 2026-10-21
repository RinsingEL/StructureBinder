package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityNaturalLayoutPlannerTest {
    static LandUseTerrainField field(boolean water) {
        List<LandUseTerrainField.Cell> cells=new ArrayList<>();
        for(int z=-12;z<=12;z++)for(int x=-12;x<=12;x++) {
            boolean wet=water&&x>3+Math.floorDiv(z,4);
            cells.add(new LandUseTerrainField.Cell(x,z,x*16,z*16,16,wet?60:64,0,0,0,wet,wet?4:0,Math.abs(x-3)*16,"plains",wet?"WATER":"PLAIN","p",true));
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(-192,-192,207,207),16,cells);
    }
    @Test void linesFollowObservedWaterAndDoNotTreatUnknownBoundaryAsShore() {
        assertTrue(CityTerrainLines.extract(field(false)).isEmpty());
        var lines=CityTerrainLines.extract(field(true));assertFalse(lines.isEmpty());
        assertEquals(lines,CityTerrainLines.extract(field(true)));
        assertTrue(lines.stream().allMatch(l->l.kind().equals("SHORE")));
        assertTrue(lines.stream().flatMap(l->l.samples().stream()).map(s->s.point().x()).distinct().count()>2);
    }
    @Test void contourAndCliffEdgesComeFromObservedHeightChanges() {
        var source=field(false);
        for(boolean cliff:List.of(false,true)) {
            var cells=source.cells().stream().map(c->new LandUseTerrainField.Cell(c.cellX(),c.cellZ(),c.blockMinX(),c.blockMinZ(),c.cellStepBlocks(),cliff?(c.cellX()<0?88:64):64+c.cellX()*2,2,2,0,false,0,100,c.biomeId(),c.landformType(),c.landformPatchId(),true)).toList();
            var terrain=new LandUseTerrainField(source.schema(),source.cityId(),source.planningBounds(),source.cellStepBlocks(),cells);
            var lines=CityTerrainLines.extract(terrain);assertFalse(lines.isEmpty());
            assertTrue(lines.stream().allMatch(l->cliff?l.kind().equals("CLIFF"):l.kind().startsWith("CONTOUR_")));
            assertTrue(lines.stream().flatMap(l->l.samples().stream()).allMatch(p->p.normalX()==(cliff?1:-1)));
        }
    }

    @Test void terrainLineUsesLandwardRowsAndRejectsMissingOrShortLines() {
        var field=field(true);var line=CityTerrainLines.extract(field).stream().max(Comparator.comparingInt(l->l.samples().size())).orElseThrow();
        var layout=new CityBlueprint.NaturalLayout("TERRAIN_LINE",line.id(),2,32,8,192);
        var slots=CityNaturalLayoutPlanner.line(layout,field,List.of(12,12,12,12,12,12));
        assertEquals(6,slots.size());assertEquals(slots,CityNaturalLayoutPlanner.line(layout,field,List.of(12,12,12,12,12,12)));
        assertTrue(slots.stream().allMatch(s->field.cellAt(s.origin().x(),s.origin().z()).map(c->!c.water()).orElse(false)));
        assertThrows(IllegalArgumentException.class,()->CityNaturalLayoutPlanner.line(new CityBlueprint.NaturalLayout("TERRAIN_LINE","missing",1,32,8,192),field,List.of(12)));
        assertThrows(IllegalArgumentException.class,()->CityNaturalLayoutPlanner.line(new CityBlueprint.NaturalLayout("TERRAIN_LINE",line.id(),1,512,8,192),field,List.of(12,12,12)));
    }
    @Test void scatterKeepsCompleteUnitsApartAndIsDeterministic() {
        var layout=new CityBlueprint.NaturalLayout("SCATTER","",1,48,8,160);
        var sizes=Collections.nCopies(5,new CityRectangleLayoutPlanner.Size(20,24));
        var result=CityNaturalLayoutPlanner.scatter(layout,field(false),new BlockPoint(0,0),sizes,25);
        assertEquals(5,result.size());assertEquals(result,CityNaturalLayoutPlanner.scatter(layout,field(false),new BlockPoint(0,0),sizes,25));
        assertNotEquals(result,CityNaturalLayoutPlanner.scatter(layout,field(false),new BlockPoint(0,0),sizes,26));
        for(int i=0;i<result.size();i++)for(int j=0;j<i;j++)assertTrue(Math.hypot(result.get(i).x()-result.get(j).x(),result.get(i).z()-result.get(j).z())>=48);
        assertThrows(IllegalArgumentException.class,()->CityNaturalLayoutPlanner.scatter(new CityBlueprint.NaturalLayout("SCATTER","",1,512,8,16),field(false),new BlockPoint(0,0),sizes,25));
    }
    @Test void ruralMosaicFormsLargeDisjointPatchesAndRetainsHoles() {
        Set<BlockPoint> domain=new HashSet<>();for(int z=-96;z<96;z++)for(int x=-96;x<96;x++)if(x*x+z*z>100)domain.add(new BlockPoint(x,z));
        var result=CityRuralMosaic.partition(domain,5,field(false),36);
        assertEquals(5,result.size());assertEquals(result,CityRuralMosaic.partition(domain,5,field(false),36));
        Set<BlockPoint> all=new HashSet<>();for(var parcel:result.values()){assertTrue(parcel.size()>1000);for(var p:parcel){assertTrue(domain.contains(p));assertTrue(all.add(p));}}
        assertTrue(all.size()>domain.size()*.9);assertTrue(all.size()<domain.size());
    }
}
