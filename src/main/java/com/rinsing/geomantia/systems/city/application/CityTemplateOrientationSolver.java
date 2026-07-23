package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure rotation ranking for a template frontage and a public-facing target. */
public final class CityTemplateOrientationSolver {
    public List<RotationScore> rank(CityTemplateCatalog.Template template,
                                    CityTemplatePlacementGeometry.Mirror mirror,
                                    String frontageEntranceId,
                                    BlockPoint candidateCenter,
                                    FacingTarget target) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(mirror, "mirror");
        Objects.requireNonNull(candidateCenter, "candidateCenter");
        Objects.requireNonNull(target, "target");
        String entranceId = resolveEntranceId(template, frontageEntranceId);
        int targetX = target.direction() == null
                ? target.point().x() - candidateCenter.x() : dx(target.direction());
        int targetZ = target.direction() == null
                ? target.point().z() - candidateCenter.z() : dz(target.direction());

        List<RotationScore> scores = new ArrayList<>();
        for (CityTemplatePlacementGeometry.Rotation rotation : template.allowedRotations()) {
            CityTemplatePlacementGeometry geometry = template.geometry(rotation, mirror);
            CityTemplatePlacementGeometry.TransformedRoadEntrance entrance = geometry.roadEntrances().stream()
                    .filter(candidate -> candidate.entranceId().equals(entranceId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_UNAVAILABLE: " + entranceId));
            double alignmentScore = alignment(entrance.direction(), targetX, targetZ);
            scores.add(new RotationScore(rotation, entrance.entranceId(), entrance.direction(), alignmentScore));
        }
        scores.sort(Comparator.comparingDouble(RotationScore::alignmentScore).reversed());
        return List.copyOf(scores);
    }

    private String resolveEntranceId(CityTemplateCatalog.Template template, String requested) {
        if (requested != null && !requested.isBlank()) {
            String entranceId = requested.trim();
            boolean exists = template.roadEntrances().stream()
                    .anyMatch(entrance -> entrance.entranceId().equals(entranceId));
            if (!exists) {
                throw new IllegalArgumentException("D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_UNAVAILABLE: " + entranceId);
            }
            return entranceId;
        }
        List<CityTemplatePlacementGeometry.RoadEntrance> entrances = template.roadEntrances();
        if (entrances.isEmpty()) {
            throw new IllegalArgumentException("D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_REQUIRED: "
                    + template.templateId() + " has no road entrance to use as frontage.");
        }
        List<CityTemplatePlacementGeometry.RoadEntrance> namedFront = entrances.stream()
                .filter(entrance -> "front".equals(entrance.entranceId().toLowerCase(Locale.ROOT)))
                .toList();
        if (namedFront.size() == 1) {
            return namedFront.get(0).entranceId();
        }
        if (entrances.size() == 1) {
            return entrances.get(0).entranceId();
        }
        throw new IllegalArgumentException("D4_ARRAY_LAYOUT_FRONTAGE_ENTRANCE_AMBIGUOUS: "
                + template.templateId() + " requires frontageEntranceId.");
    }

    private static double alignment(CityTemplatePlacementGeometry.Direction direction, int targetX, int targetZ) {
        if (targetX == 0 && targetZ == 0) {
            return 0.0;
        }
        double length = Math.sqrt((double) targetX * targetX + (double) targetZ * targetZ);
        return (dx(direction) * targetX + dz(direction) * targetZ) / length;
    }

    private static int dx(CityTemplatePlacementGeometry.Direction direction) {
        return switch (direction) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    private static int dz(CityTemplatePlacementGeometry.Direction direction) {
        return switch (direction) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
    }

    public record FacingTarget(String targetRef,
                               BlockPoint point,
                               CityTemplatePlacementGeometry.Direction direction) {
        public FacingTarget {
            targetRef = targetRef == null ? "" : targetRef.trim();
            if ((point == null) == (direction == null)) {
                throw new IllegalArgumentException("FacingTarget requires exactly one point or direction.");
            }
        }

        public static FacingTarget point(String targetRef, BlockPoint point) {
            return new FacingTarget(targetRef, Objects.requireNonNull(point, "point"), null);
        }

        public static FacingTarget cardinal(String targetRef,
                                            CityTemplatePlacementGeometry.Direction direction) {
            return new FacingTarget(targetRef, null, Objects.requireNonNull(direction, "direction"));
        }
    }

    public record RotationScore(CityTemplatePlacementGeometry.Rotation rotation,
                                String entranceId,
                                CityTemplatePlacementGeometry.Direction frontageDirection,
                                double alignmentScore) {
    }
}
