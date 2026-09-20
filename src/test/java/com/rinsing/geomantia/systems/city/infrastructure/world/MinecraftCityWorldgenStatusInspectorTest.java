package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner.StructureTask;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftCityWorldgenStatusInspectorTest {
    private static StructureTask tree() {
        return new StructureTask("tree",new BlockPoint(4433,2137),"geomantia:roadside/small_oak","sha256:tree",
                new BlockBounds(4433,2137,4440,2146),"CLOCKWISE_90","NONE");
    }
    @Test void completedAnchorFragmentAndUnfinishedNeighborRemainWaiting() {
        var inspector=new MinecraftCityWorldgenStatusInspector((x,z)->z==133?"minecraft:initialize_light":null,
                (task,owner)->owner.z==133);
        var result=inspector.inspect(tree());
        assertFalse(result.failure());assertEquals("WAITING_FOR_WORLDGEN",result.reasonCode());
        assertTrue(result.message().contains("1/2"));
    }
    @Test void generatedNeighborWithoutProofIsStillARealFailure() {
        var inspector=new MinecraftCityWorldgenStatusInspector((x,z)->"minecraft:full",
                (task,owner)->owner.z==133);
        var result=inspector.inspect(tree());
        assertTrue(result.failure());assertEquals("STRUCTURE_CHUNK_ALREADY_GENERATED",result.reasonCode());
        assertTrue(result.message().contains("277,134"));
    }
    @Test void finishedFragmentsAfterLedgerSnapshotWaitForNextObservation() {
        var inspector=new MinecraftCityWorldgenStatusInspector((x,z)->"minecraft:full",(task,owner)->true);
        assertFalse(inspector.inspect(tree()).failure());
    }
    @Test void rechecksEvidenceIfWorldgenCompletesBetweenReads() {
        AtomicInteger checks=new AtomicInteger();
        var inspector=new MinecraftCityWorldgenStatusInspector((x,z)->"minecraft:full",
                (task,owner)->owner.z==134 || checks.incrementAndGet()>1);
        assertFalse(inspector.inspect(tree()).failure());
        assertEquals(2,checks.get());
    }
}
