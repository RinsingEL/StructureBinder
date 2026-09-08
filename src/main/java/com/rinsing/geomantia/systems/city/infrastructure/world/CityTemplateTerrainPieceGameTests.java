package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraftforge.gametest.GameTestHolder;
import java.util.List;

@GameTestHolder("geomantia")
public final class CityTemplateTerrainPieceGameTests {
    @GameTest(template="empty")
    public static void sameChunkBuildingsSurviveMergeAndNbtReload(GameTestHelper helper) {
        var first = piece("first",0);
        var second = piece("second",8);
        var merged = MinecraftCityWorldgenStructurePlacer.appendTemplatePiece(List.of(first),second);
        merged = MinecraftCityWorldgenStructurePlacer.appendTemplatePiece(merged,second);
        if (merged.size()!=2) { helper.fail("Same-chunk buildings were lost or duplicated"); return; }
        var context = StructurePieceSerializationContext.fromLevel(helper.getLevel());
        var restoredFirst = new CityTemplateTerrainStructurePiece(context,merged.get(0).createTag(context));
        var restoredSecond = new CityTemplateTerrainStructurePiece(context,merged.get(1).createTag(context));
        if (!restoredFirst.matchesPlannedAnchor("first",0,0) || !restoredSecond.matchesPlannedAnchor("second",8,0)
                || restoredFirst.footprint().overlaps(restoredSecond.footprint())) {
            helper.fail("Distinct template identities/footprints did not survive NBT reload"); return;
        }
        helper.succeed();
    }
    private static CityTemplateTerrainStructurePiece piece(String id,int x) {
        return new CityTemplateTerrainStructurePiece(ResourceLocation.tryParse("geomantia:d6d7_fixture/house"),
                "fixture_hash",id,new BlockPoint(x,0),64,CityTemplatePlacementGeometry.Rotation.NONE,
                CityTemplatePlacementGeometry.Mirror.NONE,new CityTemplatePlacementGeometry.Size(4,4,4));
    }
}
