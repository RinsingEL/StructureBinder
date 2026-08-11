package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Maintains the spatial grammar used by the incremental Blueprint compiler. */
final class CityBlueprintGroupLayoutPlanner {
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    Parameters parameters(String algorithm, CityBlueprint.DensityClass density) {
        int targetGap = switch (density) {
            case DENSE -> 4;
            case BALANCED -> 8;
            case SPARSE -> 16;
        };
        int maximumGap = switch (density) {
            case DENSE -> 12;
            case BALANCED -> 20;
            case SPARSE -> 32;
        };
        int handoffGap = switch (density) {
            case DENSE -> 16;
            case BALANCED -> 24;
            case SPARSE -> 40;
        };
        double claimMultiplier = 1.0;
        int jitter = Math.max(2, targetGap / 2);
        double outwardBias = 0.68;
        switch (algorithm) {
            case "GRID" -> {
                targetGap += 2;
                maximumGap += 4;
                jitter = 0;
                claimMultiplier = 1.08;
                outwardBias = 0.75;
            }
            case "LINEAR" -> {
                targetGap += 1;
                maximumGap += 2;
                jitter = Math.max(2, targetGap / 3);
                claimMultiplier = 1.04;
                outwardBias = 0.78;
            }
            case "COURTYARD" -> {
                targetGap += 4;
                maximumGap += 4;
                jitter = Math.max(1, targetGap / 5);
                claimMultiplier = 1.20;
                outwardBias = 0.50;
            }
            case "ORGANIC_COMPACT" -> {
                targetGap += 2;
                maximumGap += 3;
                jitter = Math.max(3, targetGap / 2);
                claimMultiplier = 1.06;
                outwardBias = 0.58;
            }
            case "CENTER_SYMMETRIC" -> {
                targetGap += 4;
                maximumGap += 4;
                jitter = 0;
                claimMultiplier = 1.20;
                outwardBias = 0.0;
            }
            default -> {
                // COMPACT uses the baseline parameters.
            }
        }
        return new Parameters(targetGap, maximumGap, handoffGap, jitter,
                claimMultiplier, outwardBias);
    }

    Frame frame(BlockPoint center, BlockPoint target, long seed, String groupId) {
        double dx = target == null ? 0.0 : target.x() - center.x();
        double dz = target == null ? 0.0 : target.z() - center.z();
        double length = Math.hypot(dx, dz);
        if (length < 0.0001) {
            double angle = stableUnit(seed, groupId, 0, "axis") * Math.PI * 2.0;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
            length = 1.0;
        }
        return new Frame(center, dx / length, dz / length);
    }

    Proposal propose(String algorithm,
                     CityBlueprint.DensityClass density,
                     long seed,
                     String groupId,
                     int slotIndex,
                     Frame frame,
                     BlockPoint seedPoint,
                     BlockPoint outwardTarget,
                     boolean outwardPending,
                     int footprintSpan) {
        Parameters parameters = parameters(algorithm, density);
        int spacing = Math.max(1, footprintSpan + parameters.targetEdgeGapBlocks());
        if (slotIndex == 0) {
            return new Proposal(slotIndex, algorithm, parameters, spacing, outwardPending,
                    outwardTarget, List.of(seedPoint));
        }

        boolean centerSymmetric = "CENTER_SYMMETRIC".equals(algorithm);
        Frame guidanceFrame = !centerSymmetric && outwardPending && outwardTarget != null
                ? frame.toward(outwardTarget) : frame;
        BlockPoint desired = centerSymmetric
                ? centerSymmetricPoint(frame, density, slotIndex, footprintSpan)
                : outwardPending && outwardTarget != null
                ? outwardPoint(algorithm, seedPoint, outwardTarget, slotIndex, spacing, seed, groupId)
                : switch (algorithm) {
                    case "GRID" -> gridPoint(frame, slotIndex, spacing, false);
                    case "LINEAR" -> linearPoint(frame, slotIndex, spacing, false);
                    case "COURTYARD" -> courtyardPoint(frame, slotIndex, spacing, false);
                    case "ORGANIC_COMPACT" -> spiralPoint(frame, slotIndex, spacing, seed, groupId,
                            parameters, false, true);
                    default -> spiralPoint(frame, slotIndex, spacing, seed, groupId,
                            parameters, false, false);
                };
        List<BlockPoint> guides = fallbackGuides(desired, guidanceFrame, parameters, spacing, algorithm);
        return new Proposal(slotIndex, algorithm, parameters, spacing, !centerSymmetric && outwardPending,
                outwardTarget, guides);
    }

    List<SymmetricPair> symmetricPairOptions(CityBlueprint.DensityClass density,
                                             Frame frame,
                                             int pairIndex,
                                             int centerFootprintSpan,
                                             int memberFootprintSpan) {
        return symmetricPairOptions(density, frame, pairIndex, centerFootprintSpan,
                memberFootprintSpan, frame.center().x() * 2, frame.center().z() * 2);
    }

    List<SymmetricPair> symmetricPairOptions(CityBlueprint.DensityClass density,
                                             Frame frame,
                                             int pairIndex,
                                             int centerFootprintSpan,
                                             int memberFootprintSpan,
                                             int anchorCenterTwiceX,
                                             int anchorCenterTwiceZ) {
        Parameters parameters = parameters("CENTER_SYMMETRIC", density);
        int firstRadius = Math.max(1, (centerFootprintSpan + memberFootprintSpan + 1) / 2
                + parameters.targetEdgeGapBlocks());
        int ring = pairIndex / 2;
        int radius = firstRadius + ring * (memberFootprintSpan + parameters.targetEdgeGapBlocks());
        int preferredAxis = ((pairIndex % 2) * 2 + (ring % 2)) % 4;
        double phase = Math.atan2(frame.axisZ(), frame.axisX());
        List<SymmetricPair> result = new ArrayList<>();
        for (int rotation = 0; rotation < 4; rotation++) {
            int axis = (preferredAxis + rotation) % 4;
            double angle = phase + axis * Math.PI / 4.0;
            double dx = Math.cos(angle) * radius;
            double dz = Math.sin(angle) * radius;
            BlockPoint approximateCenter = new BlockPoint(Math.floorDiv(anchorCenterTwiceX, 2),
                    Math.floorDiv(anchorCenterTwiceZ, 2));
            BlockPoint first = point(approximateCenter, dx, dz);
            BlockPoint opposite = new BlockPoint(anchorCenterTwiceX - first.x(),
                    anchorCenterTwiceZ - first.z());
            result.add(new SymmetricPair(pairIndex, ring, axis, radius,
                    anchorCenterTwiceX, anchorCenterTwiceZ, first, opposite, parameters));
        }
        return List.copyOf(result);
    }

    private static BlockPoint outwardPoint(String algorithm,
                                           BlockPoint frontier,
                                           BlockPoint target,
                                           int slotIndex,
                                           int spacing,
                                           long seed,
                                           String groupId) {
        double dx = target.x() - frontier.x();
        double dz = target.z() - frontier.z();
        double length = Math.max(0.0001, Math.hypot(dx, dz));
        double axisX = dx / length;
        double axisZ = dz / length;
        double lateral = switch (algorithm) {
            case "GRID" -> ((slotIndex % 3) - 1) * spacing * 0.55;
            case "LINEAR" -> ((slotIndex & 1) == 0 ? 1.0 : -1.0) * spacing * 0.28;
            case "COURTYARD" -> switch (slotIndex % 4) {
                case 0 -> -spacing * 0.70;
                case 1 -> -spacing * 0.24;
                case 2 -> spacing * 0.24;
                default -> spacing * 0.70;
            };
            case "ORGANIC_COMPACT" -> (stableUnit(seed, groupId, slotIndex, "outward_side") - 0.5)
                    * spacing * 1.10;
            default -> Math.sin(slotIndex * GOLDEN_ANGLE) * spacing * 0.38;
        };
        double advance = Math.min(spacing, length);
        return point(frontier, axisX * advance - axisZ * lateral,
                axisZ * advance + axisX * lateral);
    }

    int claimedArea(BlockBounds bounds, Parameters parameters) {
        int paddedWidth = bounds.widthBlocks() + parameters.targetEdgeGapBlocks();
        int paddedDepth = bounds.heightBlocks() + parameters.targetEdgeGapBlocks();
        return (int) Math.ceil(paddedWidth * (double) paddedDepth * parameters.claimAreaMultiplier());
    }

    private static BlockPoint spiralPoint(Frame frame,
                                          int slotIndex,
                                          int spacing,
                                          long seed,
                                          String groupId,
                                          Parameters parameters,
                                          boolean outwardPending,
                                          boolean organic) {
        double phase = stableUnit(seed, groupId, 0, "phase") * Math.PI * 2.0;
        double angleJitter = organic
                ? (stableUnit(seed, groupId, slotIndex, "angle") - 0.5) * 0.70 : 0.0;
        double angle = phase + slotIndex * GOLDEN_ANGLE + angleJitter;
        double radiusJitter = organic
                ? (stableUnit(seed, groupId, slotIndex, "radius") - 0.5)
                        * parameters.jitterBlocks() * 2.0 : 0.0;
        double radialGrowth = organic ? 0.62 : 0.52;
        double radius = Math.max(spacing,
                spacing * radialGrowth * Math.sqrt(slotIndex) + radiusJitter);
        double vx = Math.cos(angle);
        double vz = Math.sin(angle);
        if (outwardPending) {
            double bias = parameters.outwardBias();
            vx = vx * (1.0 - bias) + frame.axisX() * bias;
            vz = vz * (1.0 - bias) + frame.axisZ() * bias;
            double length = Math.max(0.0001, Math.hypot(vx, vz));
            vx /= length;
            vz /= length;
        }
        return point(frame.center(), vx * radius, vz * radius);
    }

    private static BlockPoint gridPoint(Frame frame, int slotIndex, int spacing, boolean outwardPending) {
        GridOffset offset = squareSpiral(slotIndex);
        int along = offset.row();
        if (outwardPending) along = Math.abs(along) + Math.max(0, (slotIndex - 1) / 8);
        double dx = frame.axisX() * along * spacing - frame.axisZ() * offset.column() * spacing;
        double dz = frame.axisZ() * along * spacing + frame.axisX() * offset.column() * spacing;
        return point(frame.center(), dx, dz);
    }

    private static BlockPoint linearPoint(Frame frame, int slotIndex, int spacing, boolean outwardPending) {
        int rank = (slotIndex + 1) / 2;
        int direction = outwardPending ? 1 : ((rank & 1) == 0 ? -1 : 1);
        double side = (slotIndex & 1) == 0 ? spacing * 0.42 : -spacing * 0.42;
        double along = direction * rank * spacing;
        double dx = frame.axisX() * along - frame.axisZ() * side;
        double dz = frame.axisZ() * along + frame.axisX() * side;
        return point(frame.center(), dx, dz);
    }

    private static BlockPoint courtyardPoint(Frame frame, int slotIndex, int spacing,
                                             boolean outwardPending) {
        int zeroBased = slotIndex - 1;
        int ring = zeroBased / 8 + 1;
        int position = zeroBased % 8;
        double phase = Math.atan2(frame.axisZ(), frame.axisX());
        if (!outwardPending) phase += Math.PI / 8.0;
        double angle = phase + position * Math.PI / 4.0;
        double radius = spacing * (1.35 + (ring - 1) * 1.15);
        return point(frame.center(), Math.cos(angle) * radius, Math.sin(angle) * radius);
    }

    private BlockPoint centerSymmetricPoint(Frame frame,
                                            CityBlueprint.DensityClass density,
                                            int slotIndex,
                                            int footprintSpan) {
        int pairIndex = Math.max(0, (slotIndex - 1) / 2);
        SymmetricPair pair = symmetricPairOptions(density, frame, pairIndex,
                footprintSpan, footprintSpan).get(0);
        return (slotIndex & 1) == 1 ? pair.first() : pair.opposite();
    }

    private static List<BlockPoint> fallbackGuides(BlockPoint desired,
                                                    Frame frame,
                                                    Parameters parameters,
                                                    int spacing,
                                                    String algorithm) {
        Set<BlockPoint> guides = new LinkedHashSet<>();
        guides.add(desired);
        if ("CENTER_SYMMETRIC".equals(algorithm)) return List.copyOf(guides);
        int offset = "GRID".equals(algorithm)
                ? spacing : Math.max(2, Math.min(spacing / 3, parameters.jitterBlocks()));
        guides.add(point(desired, -frame.axisZ() * offset, frame.axisX() * offset));
        guides.add(point(desired, frame.axisZ() * offset, -frame.axisX() * offset));
        guides.add(point(desired, frame.axisX() * offset, frame.axisZ() * offset));
        guides.add(point(desired, -frame.axisX() * offset, -frame.axisZ() * offset));
        return List.copyOf(guides);
    }

    private static GridOffset squareSpiral(int index) {
        if (index <= 0) return new GridOffset(0, 0);
        int x = 0;
        int z = 0;
        int dx = 1;
        int dz = 0;
        int segmentLength = 1;
        int segmentUsed = 0;
        int turns = 0;
        for (int i = 0; i < index; i++) {
            x += dx;
            z += dz;
            segmentUsed++;
            if (segmentUsed == segmentLength) {
                segmentUsed = 0;
                int nextDx = -dz;
                dz = dx;
                dx = nextDx;
                turns++;
                if ((turns & 1) == 0) segmentLength++;
            }
        }
        return new GridOffset(x, z);
    }

    private static BlockPoint point(BlockPoint origin, double dx, double dz) {
        return new BlockPoint(origin.x() + (int) Math.round(dx), origin.z() + (int) Math.round(dz));
    }

    private static double stableUnit(long seed, String groupId, int slotIndex, String salt) {
        long value = 0xcbf29ce484222325L ^ seed;
        String input = groupId + '\u0000' + slotIndex + '\u0000' + salt;
        for (int i = 0; i < input.length(); i++) {
            value ^= input.charAt(i);
            value *= 0x100000001b3L;
        }
        return (value >>> 11) * 0x1.0p-53;
    }

    record Parameters(int targetEdgeGapBlocks,
                      int maximumEdgeGapBlocks,
                      int landUseHandoffGapBlocks,
                      int jitterBlocks,
                      double claimAreaMultiplier,
                      double outwardBias) {
        JsonObject asJson() {
            JsonObject value = new JsonObject();
            value.addProperty("targetEdgeGapBlocks", targetEdgeGapBlocks);
            value.addProperty("maximumEdgeGapBlocks", maximumEdgeGapBlocks);
            value.addProperty("landUseHandoffGapBlocks", landUseHandoffGapBlocks);
            value.addProperty("jitterBlocks", jitterBlocks);
            value.addProperty("claimAreaMultiplier", claimAreaMultiplier);
            return value;
        }
    }

    record Frame(BlockPoint center, double axisX, double axisZ) {
        Frame recenter(BlockPoint nextCenter) {
            return new Frame(nextCenter, axisX, axisZ);
        }

        Frame toward(BlockPoint target) {
            double dx = target.x() - center.x();
            double dz = target.z() - center.z();
            double length = Math.hypot(dx, dz);
            return length < 0.0001 ? this : new Frame(center, dx / length, dz / length);
        }
    }

    record Proposal(int slotIndex,
                    String algorithm,
                    Parameters parameters,
                    int spacingBlocks,
                    boolean outwardGuided,
                    BlockPoint outwardTarget,
                    List<BlockPoint> guides) {
        Proposal {
            guides = List.copyOf(guides);
        }

        JsonArray guidesJson() {
            JsonArray values = new JsonArray();
            for (BlockPoint guide : guides) {
                JsonObject value = new JsonObject();
                value.addProperty("x", guide.x());
                value.addProperty("z", guide.z());
                values.add(value);
            }
            return values;
        }

        JsonObject traceJson() {
            JsonObject value = new JsonObject();
            value.addProperty("algorithm", algorithm);
            value.addProperty("slotIndex", slotIndex);
            value.addProperty("spacingBlocks", spacingBlocks);
            value.addProperty("outwardGuided", outwardGuided);
            value.add("densityParameters", parameters.asJson());
            if (outwardTarget != null) {
                JsonObject target = new JsonObject();
                target.addProperty("x", outwardTarget.x());
                target.addProperty("z", outwardTarget.z());
                value.add("outwardTarget", target);
            }
            return value;
        }
    }

    record SymmetricPair(int pairIndex,
                         int ringIndex,
                         int axisVariant,
                         int radiusBlocks,
                         int anchorCenterTwiceX,
                         int anchorCenterTwiceZ,
                         BlockPoint first,
                         BlockPoint opposite,
                         Parameters parameters) {
        JsonArray originsJson() {
            JsonArray values = new JsonArray();
            values.add(first.asJson());
            values.add(opposite.asJson());
            return values;
        }

        JsonObject traceJson(int firstSlotIndex) {
            JsonObject value = new JsonObject();
            value.addProperty("algorithm", "CENTER_SYMMETRIC");
            value.addProperty("slotIndex", firstSlotIndex);
            value.addProperty("spacingBlocks", radiusBlocks);
            value.addProperty("outwardGuided", false);
            value.add("densityParameters", parameters.asJson());
            value.addProperty("symmetryPairIndex", pairIndex);
            value.addProperty("symmetryRingIndex", ringIndex);
            value.addProperty("symmetryAxisVariant", axisVariant);
            value.addProperty("atomicPair", true);
            JsonObject center = new JsonObject();
            center.addProperty("x", anchorCenterTwiceX / 2.0);
            center.addProperty("z", anchorCenterTwiceZ / 2.0);
            center.addProperty("xTimesTwo", anchorCenterTwiceX);
            center.addProperty("zTimesTwo", anchorCenterTwiceZ);
            value.add("symmetryCenter", center);
            return value;
        }
    }

    private record GridOffset(int row, int column) {
    }
}
