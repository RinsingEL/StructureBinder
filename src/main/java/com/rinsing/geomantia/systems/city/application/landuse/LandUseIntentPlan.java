package com.rinsing.geomantia.systems.city.application.landuse;

import java.util.List;

public record LandUseIntentPlan(
        String cityId,
        String seedSalt,
        List<GroupOverride> groupOverrides,
        List<SubjectOverride> subjectOverrides) {

    public static final String SCHEMA = "city_land_use_intent_plan.v0.1";

    public LandUseIntentPlan {
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        seedSalt = seedSalt == null ? "" : seedSalt;
        groupOverrides = List.copyOf(groupOverrides == null ? List.of() : groupOverrides);
        subjectOverrides = List.copyOf(subjectOverrides == null ? List.of() : subjectOverrides);
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
}
