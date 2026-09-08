package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import java.util.*;

/** Stable weighted order: failed pools fall through without rerolling the successful city. */
final class CityWeightedPoolSelection {
    static List<CityBlueprint.WeightedPool> fill(CityBlueprint.Group group) {
        return group.fillPools().isEmpty() ? List.of(new CityBlueprint.WeightedPool(group.fillPoolRef(), 1)) : group.fillPools();
    }
    static List<CityBlueprint.WeightedPool> connection(CityBlueprint.Group group) {
        var plan = group.connectionPlan();
        if (plan == null || plan.structurePoolRef() == null) return fill(group);
        return plan.structurePools().isEmpty() ? List.of(new CityBlueprint.WeightedPool(plan.structurePoolRef(), 1)) : plan.structurePools();
    }
    static List<String> order(List<CityBlueprint.WeightedPool> pools, long seed, String identity, int ordinal) {
        record Draw(String ref, double score) { }
        return pools.stream().map(pool -> {
            long hash = seed ^ 0xcbf29ce484222325L;
            for (char c : (identity + "|" + ordinal + "|" + pool.poolRef()).toCharArray()) hash = (hash ^ c) * 0x100000001b3L;
            double unit = Math.max(Double.MIN_NORMAL, new SplittableRandom(hash).nextDouble());
            return new Draw(pool.poolRef(), Math.log(-Math.log(unit)) - Math.log(pool.weight()));
        }).sorted(Comparator.comparingDouble(Draw::score).thenComparing(Draw::ref)).map(Draw::ref).toList();
    }
}
