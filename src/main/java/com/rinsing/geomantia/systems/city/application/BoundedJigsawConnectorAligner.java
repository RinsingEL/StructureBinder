package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class BoundedJigsawConnectorAligner {
    private BoundedJigsawConnectorAligner() {
    }

    public static JsonObject alignToParentConnector(JsonObject parentConnector, JsonObject childPrototype) {
        JsonObject child = childPrototype == null ? new JsonObject() : childPrototype.deepCopy();
        JsonObject parent = parentConnector == null ? null : parentConnector.deepCopy();
        if (parent == null || parent.entrySet().isEmpty()) {
            return failed(child, "JIGSAW_CONNECTOR_ALIGNMENT_PENDING", "parent_connector_missing");
        }
        JsonObject matched = null;
        int matchedIndex = -1;
        JsonArray connectors = arrayValue(child, "connectorRefs", new JsonArray());
        for (int i = 0; i < connectors.size(); i++) {
            if (!connectors.get(i).isJsonObject()) {
                continue;
            }
            JsonObject connector = connectors.get(i).getAsJsonObject();
            if (canAttach(parent, connector)) {
                matched = connector;
                matchedIndex = i;
                break;
            }
        }
        if (matched == null) {
            return failed(child, "JIGSAW_CONNECTOR_ALIGNMENT_FAILED", "compatible_connector_missing");
        }

        JsonObject parentBlock = objectValue(parent, "worldBlock", null);
        JsonObject childBlock = objectValue(matched, "worldBlock", null);
        if (parentBlock == null || childBlock == null) {
            return failed(child, "JIGSAW_CONNECTOR_ALIGNMENT_FAILED", "connector_world_block_missing");
        }
        Offset attach = directionOffset(stringValue(parent, "front", ""));
        int targetX = intValue(parentBlock, "x", 0) + attach.x();
        int targetY = intValue(parentBlock, "y", 0) + attach.y();
        int targetZ = intValue(parentBlock, "z", 0) + attach.z();
        int dx = targetX - intValue(childBlock, "x", 0);
        int dy = targetY - intValue(childBlock, "y", 0);
        int dz = targetZ - intValue(childBlock, "z", 0);

        translateBlock(child, "anchorBlock", dx, dy, dz);
        translateBounds(child, "footprint", dx, dz);
        translateBounds(child, "templateFootprint", dx, dz);
        JsonArray movedConnectors = new JsonArray();
        for (int i = 0; i < connectors.size(); i++) {
            if (!connectors.get(i).isJsonObject()) {
                continue;
            }
            JsonObject moved = connectors.get(i).getAsJsonObject().deepCopy();
            translateBlock(moved, "worldBlock", dx, dy, dz);
            if (i == matchedIndex) {
                moved.addProperty("consumedByParent", true);
            }
            movedConnectors.add(moved);
        }
        child.add("connectorRefs", movedConnectors);
        child.addProperty("adapterScope", "child_pool_aligned");
        child.addProperty("prototypePlacementStatus", "connector_aligned");
        child.addProperty("alignmentStatus", "aligned");
        child.addProperty("attachTarget", stringValue(parent, "target", ""));
        child.addProperty("matchedConnectorId", stringValue(matched, "connectorId", ""));
        child.addProperty("parentConnectorId", stringValue(parent, "connectorId", ""));
        child.add("parentConnectorRef", parent);
        JsonObject delta = new JsonObject();
        delta.addProperty("x", dx);
        delta.addProperty("y", dy);
        delta.addProperty("z", dz);
        child.add("alignmentDelta", delta);
        if (child.has("footprint") && child.get("footprint").isJsonObject()) {
            child.addProperty("visibleAreaCost", area2d(child.getAsJsonObject("footprint")));
        }
        return child;
    }

    private static boolean canAttach(JsonObject parent, JsonObject child) {
        String parentTarget = stringValue(parent, "target", "");
        String childName = stringValue(child, "name", "");
        if (!parentTarget.isBlank() && !parentTarget.equals(childName)) {
            return false;
        }
        String parentFront = stringValue(parent, "front", "");
        String childFront = stringValue(child, "front", "");
        return parentFront.isBlank() || childFront.isBlank() || opposite(parentFront).equals(childFront);
    }

    private static JsonObject failed(JsonObject child, String reasonCode, String reason) {
        child.addProperty("alignmentStatus", "failed");
        child.addProperty("alignmentReasonCode", reasonCode);
        child.addProperty("alignmentReason", reason);
        return child;
    }

    private static void translateBlock(JsonObject obj, String key, int dx, int dy, int dz) {
        JsonObject block = objectValue(obj, key, null);
        if (block == null) {
            return;
        }
        block.addProperty("x", intValue(block, "x", 0) + dx);
        block.addProperty("y", intValue(block, "y", 0) + dy);
        block.addProperty("z", intValue(block, "z", 0) + dz);
        obj.add(key, block);
    }

    private static void translateBounds(JsonObject obj, String key, int dx, int dz) {
        JsonObject bounds = objectValue(obj, key, null);
        if (bounds == null) {
            return;
        }
        bounds.addProperty("minX", intValue(bounds, "minX", 0) + dx);
        bounds.addProperty("maxX", intValue(bounds, "maxX", 0) + dx);
        bounds.addProperty("minZ", intValue(bounds, "minZ", 0) + dz);
        bounds.addProperty("maxZ", intValue(bounds, "maxZ", 0) + dz);
        obj.add(key, bounds);
    }

    private static int area2d(JsonObject bounds) {
        return (intValue(bounds, "maxX", 0) - intValue(bounds, "minX", 0) + 1)
                * (intValue(bounds, "maxZ", 0) - intValue(bounds, "minZ", 0) + 1);
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            default -> "";
        };
    }

    private static Offset directionOffset(String direction) {
        return switch (direction) {
            case "north" -> new Offset(0, 0, -1);
            case "south" -> new Offset(0, 0, 1);
            case "east" -> new Offset(1, 0, 0);
            case "west" -> new Offset(-1, 0, 0);
            case "up" -> new Offset(0, 1, 0);
            case "down" -> new Offset(0, -1, 0);
            default -> new Offset(0, 0, 0);
        };
    }

    private static JsonArray arrayValue(JsonObject obj, String key, JsonArray defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : defaultValue;
    }

    private static JsonObject objectValue(JsonObject obj, String key, JsonObject defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject()
                ? obj.getAsJsonObject(key).deepCopy()
                : defaultValue;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    private record Offset(int x, int y, int z) {
    }
}
