package com.rinsing.geomantia.systems.city.application.landuse;

import java.util.Arrays;
import java.util.Map;

/** Whole-segment vertical alignment. Integer heights; no world/chunk access. */
public final class CityRoadGradeProfile {
    private CityRoadGradeProfile() {}

    public record Result(boolean feasible, int[] heights, String reason) {
        public Result { heights = heights.clone(); }
        @Override public int[] heights() { return heights.clone(); }
    }

    /** Finds a grade within cut/fill envelopes and fixed junction/entrance elevations. */
    public static Result solve(int[] terrain, int maxCut, int maxFill, int blocksPerRise,
                               Map<Integer, Integer> fixed) {
        if (terrain.length == 0 || maxCut < 0 || maxFill < 0 || blocksPerRise < 1)
            throw new IllegalArgumentException("Invalid road grade input");
        int n = terrain.length;
        int[] lower = new int[n], upper = new int[n];
        for (int i = 0; i < n; i++) {
            lower[i] = terrain[i] - maxCut;
            upper[i] = terrain[i] + maxFill;
        }
        for (var entry : fixed.entrySet()) {
            int i = entry.getKey(), y = entry.getValue();
            if (i < 0 || i >= n) throw new IllegalArgumentException("Road pin out of range");
            if (y < lower[i] || y > upper[i]) return new Result(false, terrain, "PIN_OUTSIDE_EARTHWORK_LIMIT");
            lower[i] = upper[i] = y;
        }
        // Rises are permitted only at globally segment-relative stations. Two passes compute
        // the feasible envelope; maxCut is a real constraint, not a post-placement fallback.
        for (int i = 1; i < n; i++) {
            int step = i % blocksPerRise == 0 ? 1 : 0;
            lower[i] = Math.max(lower[i], lower[i-1] - step);
            upper[i] = Math.min(upper[i], upper[i-1] + step);
        }
        for (int i = n-2; i >= 0; i--) {
            int step = (i+1) % blocksPerRise == 0 ? 1 : 0;
            lower[i] = Math.max(lower[i], lower[i+1] - step);
            upper[i] = Math.min(upper[i], upper[i+1] + step);
        }
        for (int i = 0; i < n; i++) if (lower[i] > upper[i])
            return new Result(false, terrain, "INSUFFICIENT_GRADE_LENGTH");
        int[] result = new int[n];
        // The upper envelope propagates the low side back into the high side. Stay as close
        // to existing ground as that envelope allows, then maintain the selected grade.
        result[0] = Math.max(lower[0], Math.min(upper[0], terrain[0]));
        for (int i = 1; i < n; i++) {
            int step = i % blocksPerRise == 0 ? 1 : 0;
            int lo = Math.max(lower[i], result[i-1] - step);
            int hi = Math.min(upper[i], result[i-1] + step);
            result[i] = Math.max(lo, Math.min(hi, terrain[i]));
        }
        return new Result(true, result, Arrays.equals(result, terrain) ? "UNCHANGED" : "CONTINUOUS_GRADE");
    }
}
