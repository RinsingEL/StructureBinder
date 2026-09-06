package com.rinsing.geomantia.systems.city.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Counts all committed phases together. Required content is never replaced to meet a fill quota. */
final class CityFillSelection {
    static String choose(List<String> pool, Map<String, Integer> counts, Set<String> blocked,
                         int maximum, int copies, int cursor) {
        String best = null;
        int bestCount = Integer.MAX_VALUE;
        for (int offset = 0; offset < pool.size(); offset++) {
            String ref = pool.get(Math.floorMod(cursor + offset, pool.size()));
            int count = counts.getOrDefault(ref, 0);
            if (blocked.contains(ref) || maximum > 0 && count + copies > maximum) continue;
            if (count < bestCount) { best = ref; bestCount = count; }
        }
        return best;
    }
}
