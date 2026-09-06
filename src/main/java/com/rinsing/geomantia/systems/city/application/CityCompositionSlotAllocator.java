package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic bounded backtracking: an earlier member must not strand a later member. */
final class CityCompositionSlotAllocator {
    record Result(List<Integer> choices, int visited, boolean searchLimitReached) {
        boolean allocated() { return choices != null; }
    }

    static Result allocate(List<List<BlockBounds>> options, List<BlockBounds> reserved, int limit) {
        Search search = new Search(options, reserved, limit);
        boolean found = search.visit(0);
        return new Result(found ? Arrays.stream(search.choices).boxed().toList() : null,
                search.visited, search.limited);
    }

    private static final class Search {
        final List<List<BlockBounds>> options;
        final List<BlockBounds> occupied;
        final int[] choices;
        final int limit;
        int visited;
        boolean limited;

        Search(List<List<BlockBounds>> options, List<BlockBounds> reserved, int limit) {
            this.options = options;
            this.occupied = new ArrayList<>(reserved);
            this.choices = new int[options.size()];
            Arrays.fill(choices, -1);
            this.limit = limit;
        }

        boolean visit(int assigned) {
            if (assigned == options.size()) return true;
            int selected = -1;
            List<Integer> available = null;
            // Most constrained first, original member/candidate order as a deterministic tie-break.
            for (int group = 0; group < options.size(); group++) {
                if (choices[group] >= 0) continue;
                List<Integer> legal = new ArrayList<>();
                for (int candidate = 0; candidate < options.get(group).size(); candidate++) {
                    if (++visited > limit) { limited = true; return false; }
                    BlockBounds bounds = options.get(group).get(candidate);
                    if (occupied.stream().noneMatch(bounds::overlaps)) legal.add(candidate);
                }
                if (legal.isEmpty()) return false;
                if (available == null || legal.size() < available.size()) {
                    selected = group;
                    available = legal;
                }
            }
            for (int candidate : available) {
                choices[selected] = candidate;
                occupied.add(options.get(selected).get(candidate));
                if (visit(assigned + 1)) return true;
                occupied.remove(occupied.size() - 1);
                choices[selected] = -1;
                if (limited) return false;
            }
            return false;
        }
    }
}
