package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Fixed tracks sized by their members, including any allowed quarter turn. */
final class CityGridFootprintLayout {
    record Track(int index, int offset, int span) {}
    private final List<Track> rows;
    private final List<Track> columns;
    private final int gap;

    CityGridFootprintLayout(List<Integer> spans, int gap) {
        this.gap = gap;
        Map<Integer, Integer> rowSizes = new TreeMap<>(), columnSizes = new TreeMap<>();
        for (int slot = 0; slot < spans.size(); slot++) {
            var cell = CityBlueprintGroupLayoutPlanner.squareSpiral(slot);
            rowSizes.merge(cell.row(), spans.get(slot), Math::max);
            columnSizes.merge(cell.column(), spans.get(slot), Math::max);
        }
        rows = tracks(rowSizes, gap);
        columns = tracks(columnSizes, gap);
    }

    private static List<Track> tracks(Map<Integer, Integer> sizes, int gap) {
        List<Track> result = new ArrayList<>();
        int offset = -sizes.entrySet().stream().filter(e -> e.getKey() < 0)
                .mapToInt(e -> e.getValue() + gap).sum();
        for (var entry : sizes.entrySet()) {
            result.add(new Track(entry.getKey(), offset, entry.getValue()));
            offset += entry.getValue() + gap;
        }
        return List.copyOf(result);
    }

    private static Track track(List<Track> tracks, int index) {
        return tracks.get(index - tracks.get(0).index());
    }

    BlockPoint origin(BlockPoint center, int slot) {
        var cell = CityBlueprintGroupLayoutPlanner.squareSpiral(slot);
        return new BlockPoint(center.x() + track(rows, cell.row()).offset(),
                center.z() + track(columns, cell.column()).offset());
    }

    BlockPoint frontage(BlockPoint center, int slot) {
        var cell = CityBlueprintGroupLayoutPlanner.squareSpiral(slot);
        BlockPoint origin = origin(center, slot);
        Track row = track(rows, cell.row());
        return new BlockPoint(origin.x() + (cell.row() <= 0 ? row.span() + gap / 2 : -gap / 2),
                origin.z());
    }

    JsonObject asJson(BlockPoint origin) {
        JsonObject value = new JsonObject();
        value.add("origin", origin.asJson());
        value.addProperty("gapBlocks", gap);
        value.add("rows", json(rows));
        value.add("columns", json(columns));
        return value;
    }

    private static JsonArray json(List<Track> tracks) {
        JsonArray array = new JsonArray();
        for (Track track : tracks) {
            JsonObject value = new JsonObject();
            value.addProperty("index", track.index());
            value.addProperty("offset", track.offset());
            value.addProperty("span", track.span());
            array.add(value);
        }
        return array;
    }
}
