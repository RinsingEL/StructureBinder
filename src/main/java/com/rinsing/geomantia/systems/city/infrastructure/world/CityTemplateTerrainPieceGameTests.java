package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraftforge.gametest.GameTestHolder;
import java.util.List;

@GameTestHolder("geomantia")
public final class CityTemplateTerrainPieceGameTests {
    @GameTest(template="empty")
    public static void sparseLoweredBuildingClearsOmittedAirWithoutCuttingItsBaseOrNeighbors(GameTestHelper helper) throws Exception {
        var level=helper.getLevel();var origin=helper.absolutePos(new BlockPos(2,2,2));
        var site=new BlockBounds(origin.getX(),origin.getZ(),origin.getX()+3,origin.getZ()+3);
        int ground=origin.getY()+1,top=origin.getY()+5;
        for(int z=site.minZ()-1;z<=site.maxZ()+1;z++)for(int x=site.minX()-1;x<=site.maxX()+1;x++)
            for(int y=origin.getY();y<=top;y++)level.setBlock(new BlockPos(x,y,z),Blocks.DIRT.defaultBlockState(),2);
        CityTemplateTerrainStructurePiece.clearBuildingSite(level,site,
                new BoundingBox(site.minX(),level.getMinBuildHeight(),site.minZ(),site.minX()+1,level.getMaxBuildHeight()-1,site.maxZ()),ground+1);
        if(!level.getBlockState(new BlockPos(site.minX()+2,ground+1,site.minZ())).is(Blocks.DIRT)){
            helper.fail("Site preparation wrote across its owner chunk");return;
        }
        CityTemplateTerrainStructurePiece.clearBuildingSite(level,site,
                new BoundingBox(site.minX()+2,level.getMinBuildHeight(),site.minZ(),site.maxX(),level.getMaxBuildHeight()-1,site.maxZ()),ground+1);
        // Neither an air palette entry nor explicit clearing blocks are present in this template.
        var sparse=new StructureTemplate();
        sparse.load(level.registryAccess().registryOrThrow(Registries.BLOCK).asLookup(),TagParser.parseTag("""
                {size:[4,5,4],palette:[{Name:"minecraft:stone_bricks"}],
                 blocks:[{pos:[1,1,1],state:0},{pos:[1,4,1],state:0}],entities:[]}
                """));
        if(!sparse.placeInWorld(level,origin,origin,new StructurePlaceSettings().setIgnoreEntities(true),level.getRandom(),2)){
            helper.fail("Sparse template was not placed");return;
        }
        for(int z=site.minZ();z<=site.maxZ();z++)for(int x=site.minX();x<=site.maxX();x++){
            if(x!=site.minX()+1||z!=site.minZ()+1)
                if(!level.getBlockState(new BlockPos(x,ground,z)).is(Blocks.DIRT)){helper.fail("Authored ground plane was cut away");return;}
            for(int y=ground+1;y<=top;y++){
                boolean roof=x==site.minX()+1&&z==site.minZ()+1&&y==origin.getY()+4;
                var state=level.getBlockState(new BlockPos(x,y,z));
                if(roof?!state.is(Blocks.STONE_BRICKS):!state.isAir()){helper.fail("Residual terrain or missing authored roof at "+x+","+y+","+z);return;}
            }
        }
        if(!level.getBlockState(new BlockPos(site.minX()-1,top,site.minZ())).is(Blocks.DIRT)){
            helper.fail("Neighbor outside the locked footprint was changed");return;
        }
        helper.succeed();
    }
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
