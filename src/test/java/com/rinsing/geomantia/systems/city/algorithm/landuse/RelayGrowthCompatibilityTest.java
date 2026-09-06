package com.rinsing.geomantia.systems.city.algorithm.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

class RelayGrowthCompatibilityTest {
    @Test void sixtyFiveThousandCellParcelFinishesWithoutChangingAreaOrSource() {
        var spans = new ArrayList<LandUseAreaPlan.ScanlineSpan>();
        for (int z = 4752; z < 4752 + 256; z++) spans.add(new LandUseAreaPlan.ScanlineSpan(z, -3472, -3217));
        var request = new RelayRegionGrowthClassifier.Request(spans, List.of(), new BlockPoint(-3472, 4752), 7,
                List.of(new RelayRegionGrowthClassifier.GrowthStage("a", "", "FIELD", .5, RelayRegionGrowthClassifier.GrowthForm.PATCH),
                        new RelayRegionGrowthClassifier.GrowthStage("b", "", "FIELD", .5, RelayRegionGrowthClassifier.GrowthForm.PATCH)));
        var result = assertTimeout(java.time.Duration.ofSeconds(15), () -> new RelayRegionGrowthClassifier().classify(request));
        assertEquals(65536, result.coveredBlockCount());
        assertEquals(request.source(), result.regions().get(0).start());
        assertEquals(List.of(32768, 32768), result.regions().stream().map(RelayRegionGrowthClassifier.RegionTrace::actualAreaBlocks).toList());
    }

    @Test void removalMatchesBruteForceForEveryFourByFourMask() {
        // Includes disconnected masks, holes, necks and isolated cells. The oracle
        // checks component counts, not the implementation's neighbor-search strategy.
        for (int bits = 1; bits < 65536; bits++) {
            Set<Long> cells = new HashSet<>();
            for (int bit = 0; bit < 16; bit++) if ((bits & (1 << bit)) != 0) cells.add(key(bit % 4, bit / 4));
            int before = components(cells);
            var indexed = new RelayRegionGrowthClassifier.RemainingCells(cells);
            for (long removed : List.copyOf(cells)) {
                Set<Long> after = new HashSet<>(cells);
                after.remove(removed);
                assertEquals(components(after) <= before,
                        RelayRegionGrowthClassifier.removalKeepsComponents(cells, removed),
                        "mask=" + bits + " removed=" + removed);
                assertEquals(components(after) <= before,
                        RelayRegionGrowthClassifier.removalKeepsComponents(indexed, removed),
                        "indexed mask=" + bits + " removed=" + removed);
                indexed.remove(removed);
                if (!after.isEmpty()) {
                    long next = after.iterator().next();
                    Set<Long> twice = new HashSet<>(after);
                    twice.remove(next);
                    assertEquals(components(twice) <= components(after),
                            RelayRegionGrowthClassifier.removalKeepsComponents(indexed, next));
                }
                indexed.add(removed);
            }
        }
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffff_ffffL); }
    private static int components(Set<Long> cells) {
        Set<Long> remaining = new HashSet<>(cells);
        int count = 0;
        while (!remaining.isEmpty()) {
            count++;
            var queue = new ArrayDeque<Long>();
            long seed = remaining.iterator().next();
            remaining.remove(seed);
            queue.add(seed);
            while (!queue.isEmpty()) {
                long point = queue.removeFirst();
                int x = (int) (point >> 32), z = (int) point;
                for (long next : new long[]{key(x - 1, z), key(x + 1, z), key(x, z - 1), key(x, z + 1)}) {
                    if (remaining.remove(next)) queue.add(next);
                }
            }
        }
        return count;
    }

    @Test void frozenOutputsIncludeEveryClaimAndBacktrackingOutcome() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        long started = System.nanoTime();
        for (int seed = 0; seed < 12; seed++) {
            var spans = new ArrayList<LandUseAreaPlan.ScanlineSpan>();
            for (int z = 0; z < 14; z++) spans.add(new LandUseAreaPlan.ScanlineSpan(z, -3472, -3453));
            var stages = List.of(
                    new RelayRegionGrowthClassifier.GrowthStage("a", "", "FIELD", .45, RelayRegionGrowthClassifier.GrowthForm.PATCH),
                    new RelayRegionGrowthClassifier.GrowthStage("b", "", "BANK", .08, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                    new RelayRegionGrowthClassifier.GrowthStage("c", "", "WATER", .06, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                    new RelayRegionGrowthClassifier.GrowthStage("d", "", "BANK", .08, RelayRegionGrowthClassifier.GrowthForm.CORRIDOR),
                    new RelayRegionGrowthClassifier.GrowthStage("e", "", "FIELD", .33, RelayRegionGrowthClassifier.GrowthForm.PATCH));
            String outcome;
            try {
                outcome = new RelayRegionGrowthClassifier().classify(new RelayRegionGrowthClassifier.Request(
                        spans, List.of(new LandUseAreaPlan.ScanlineSpan(5, -3465, -3462)),
                        new BlockPoint(-3472, 0), seed, stages)).toString();
            } catch (IllegalArgumentException failure) { outcome = failure.getMessage(); }
            digest.update(outcome.getBytes(StandardCharsets.UTF_8));
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        System.out.println("RELAY_COMPAT hash=" + hash + " seconds=" + (System.nanoTime() - started) / 1e9);
        assertEquals("a86dbe78691cdfba9232126ff20edacfc580869444903df58072bf66321dc188", hash);
    }
}
