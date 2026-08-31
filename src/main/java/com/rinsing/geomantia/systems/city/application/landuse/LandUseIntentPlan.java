package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record LandUseIntentPlan(
        String cityId,
        String seedSalt,
        List<GroupOverride> groupOverrides,
        List<SubjectOverride> subjectOverrides,
        List<SurfaceAlgorithmDefault> surfaceAlgorithmDefaults,
        List<SurfaceOverride> surfaceOverrides) {

    public static final String SCHEMA = "city_land_use_intent_plan";

    public LandUseIntentPlan {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        seedSalt = seedSalt == null ? "" : seedSalt;
        groupOverrides = List.copyOf(groupOverrides == null ? List.of() : groupOverrides);
        subjectOverrides = List.copyOf(subjectOverrides == null ? List.of() : subjectOverrides);
        surfaceAlgorithmDefaults = List.copyOf(
                surfaceAlgorithmDefaults == null ? List.of() : surfaceAlgorithmDefaults);
        surfaceOverrides = List.copyOf(surfaceOverrides == null ? List.of() : surfaceOverrides);
        Set<LandUseSurfaceSettings.SurfaceAlgorithm> algorithms = new HashSet<>();
        for (SurfaceAlgorithmDefault value : surfaceAlgorithmDefaults) {
            if (!algorithms.add(value.surfaceAlgorithm())) {
                throw new IllegalArgumentException("LAND_USE_SURFACE_ALGORITHM_DEFAULT_DUPLICATE:"
                        + value.surfaceAlgorithm().name().toLowerCase());
            }
        }
    }

    public LandUseIntentPlan(String cityId,
                             String seedSalt,
                             List<GroupOverride> groupOverrides,
                             List<SubjectOverride> subjectOverrides,
                             List<SurfaceOverride> surfaceOverrides) {
        this(cityId, seedSalt, groupOverrides, subjectOverrides, List.of(), surfaceOverrides);
    }

    public record SurfaceAlgorithmDefault(
            LandUseSurfaceSettings.SurfaceAlgorithm surfaceAlgorithm,
            String surfaceBlockId,
            String cropBlockId,
            String channelBankBlockId,
            String channelWaterBlockId,
            String channelBankOverlayBlockId) {
        public SurfaceAlgorithmDefault {
            if (surfaceAlgorithm == null) {
                throw new IllegalArgumentException("surfaceAlgorithm is required");
            }
            requireBlockId(surfaceBlockId, "surfaceBlockId");
            validateOptionalBlockId(cropBlockId, "cropBlockId");
            validateOptionalBlockId(channelBankBlockId, "channelBankBlockId");
            validateOptionalBlockId(channelWaterBlockId, "channelWaterBlockId");
            validateOptionalBlockId(channelBankOverlayBlockId, "channelBankOverlayBlockId");
            if (surfaceAlgorithm == LandUseSurfaceSettings.SurfaceAlgorithm.UNIFORM
                    && (cropBlockId != null || channelBankBlockId != null || channelWaterBlockId != null
                    || channelBankOverlayBlockId != null)) {
                throw new IllegalArgumentException("LAND_USE_UNIFORM_DEFAULT_FORBIDS_CONTOUR_MATERIALS");
            }
        }
    }

    public record SurfaceOverride(String targetGroupId,
                                  Boolean surfacePrintEnabled,
                                  Boolean autoConnect,
                                  LandUseSurfaceSettings.SurfaceAlgorithm surfaceAlgorithm,
                                  String surfaceBlockId,
                                  String cropBlockId,
                                  String channelBankBlockId,
                                  String channelWaterBlockId,
                                  String channelBankOverlayBlockId,
                                  BlockPoint algorithmAnchor) {
        public SurfaceOverride {
            if (targetGroupId == null || targetGroupId.isBlank()) {
                throw new IllegalArgumentException("targetGroupId is required");
            }
            validateOptionalBlockId(surfaceBlockId, "surfaceBlockId");
            validateOptionalBlockId(cropBlockId, "cropBlockId");
            validateOptionalBlockId(channelBankBlockId, "channelBankBlockId");
            validateOptionalBlockId(channelWaterBlockId, "channelWaterBlockId");
            validateOptionalBlockId(channelBankOverlayBlockId, "channelBankOverlayBlockId");
        }

        public SurfaceOverride(String targetGroupId,
                               Boolean surfacePrintEnabled,
                               Boolean autoConnect,
                               String surfaceBlockId,
                               String cropBlockId) {
            this(targetGroupId, surfacePrintEnabled, autoConnect, null, surfaceBlockId, cropBlockId,
                    null, null, null, null);
        }
    }

    public record GroupOverride(String groupId, List<String> memberAnchorIds, String ruleRef) {
        public GroupOverride {
            if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId is required");
            memberAnchorIds = List.copyOf(memberAnchorIds == null ? List.of() : memberAnchorIds);
            if (memberAnchorIds.isEmpty()) throw new IllegalArgumentException("memberAnchorIds must not be empty");
            ruleRef = ruleRef == null ? "" : ruleRef;
        }
    }

    public record SubjectOverride(TargetType targetType, String targetId, Mode mode, String ruleRef) {
        public SubjectOverride {
            if (targetType == null) throw new IllegalArgumentException("targetType is required");
            if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId is required");
            if (mode == null) throw new IllegalArgumentException("mode is required");
            ruleRef = ruleRef == null ? "" : ruleRef;
            if (mode == Mode.SET_RULE && ruleRef.isBlank()) {
                throw new IllegalArgumentException("set_rule requires ruleRef");
            }
            if (mode == Mode.EXCLUDE && !ruleRef.isBlank()) {
                throw new IllegalArgumentException("exclude forbids ruleRef");
            }
        }
    }

    public enum TargetType { GROUP, ANCHOR }
    public enum Mode { SET_RULE, EXCLUDE }

    private static void requireBlockId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        validateOptionalBlockId(value, field);
    }

    private static void validateOptionalBlockId(String value, String field) {
        if (value != null && !LandUseSurfaceSettings.isValidBlockId(value)) {
            throw new IllegalArgumentException("LAND_USE_SURFACE_BLOCK_ID_INVALID:" + field + ':' + value);
        }
    }
}
