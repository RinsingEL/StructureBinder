package com.rinsing.geomantia.systems.city.domain.landuse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Catalog-resolved semantic fill program carried from Blueprint compilation into SurfacePrint. */
public record LandscapeFillProgram(
        String fillProfileRef,
        String primaryRoleRef,
        List<RoleDefinition> roles,
        List<ContentWeight> contentWeights,
        long stableSeed) {

    private static final double SHARE_EPSILON = 1.0e-6;

    public LandscapeFillProgram {
        requireText(fillProfileRef, "LANDSCAPE_FILL_PROFILE_REF_REQUIRED");
        requireText(primaryRoleRef, "LANDSCAPE_FILL_PRIMARY_ROLE_REQUIRED");
        roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
        contentWeights = List.copyOf(Objects.requireNonNull(contentWeights, "contentWeights"));
        if (roles.isEmpty()) throw new IllegalArgumentException("LANDSCAPE_FILL_ROLES_REQUIRED");

        Map<String, RoleDefinition> rolesByRef = new HashMap<>();
        double shareSum = 0.0;
        for (RoleDefinition role : roles) {
            RoleDefinition previous = rolesByRef.putIfAbsent(role.roleRef(), role);
            if (previous != null && previous.materialRole() != role.materialRole()) {
                throw new IllegalArgumentException("LANDSCAPE_FILL_ROLE_CONFLICT:" + role.roleRef());
            }
            shareSum += role.targetShare();
        }
        if (Math.abs(shareSum - 1.0) > SHARE_EPSILON) {
            throw new IllegalArgumentException("LANDSCAPE_FILL_ROLE_SHARES_MUST_SUM_TO_ONE:" + shareSum);
        }
        RoleDefinition primary = rolesByRef.get(primaryRoleRef);
        if (primary == null || primary.materialRole() != MaterialRole.PRIMARY_CONTENT) {
            throw new IllegalArgumentException("LANDSCAPE_FILL_PRIMARY_ROLE_INVALID:" + primaryRoleRef);
        }

        Set<String> contentRefs = new HashSet<>();
        for (ContentWeight content : contentWeights) {
            if (!contentRefs.add(content.contentRef())) {
                throw new IllegalArgumentException("LANDSCAPE_FILL_CONTENT_DUPLICATE:" + content.contentRef());
            }
        }
    }

    public RoleDefinition role(String roleRef) {
        return roles.stream().filter(role -> role.roleRef().equals(roleRef)).findFirst().orElseThrow(() ->
                new IllegalArgumentException("LANDSCAPE_FILL_ROLE_UNKNOWN:" + roleRef));
    }

    public record RoleDefinition(String roleRef,
                                 MaterialRole materialRole,
                                 GrowthForm growthForm,
                                 double targetShare) {
        public RoleDefinition {
            requireText(roleRef, "LANDSCAPE_FILL_ROLE_REF_REQUIRED");
            Objects.requireNonNull(materialRole, "materialRole");
            Objects.requireNonNull(growthForm, "growthForm");
            if (!Double.isFinite(targetShare) || targetShare <= 0.0 || targetShare > 1.0) {
                throw new IllegalArgumentException("LANDSCAPE_FILL_ROLE_SHARE_INVALID:" + roleRef);
            }
        }
    }

    public record ContentWeight(String contentRef, double weight) {
        public ContentWeight {
            requireText(contentRef, "LANDSCAPE_FILL_CONTENT_REF_REQUIRED");
            if (!Double.isFinite(weight) || weight <= 0.0) {
                throw new IllegalArgumentException("LANDSCAPE_FILL_CONTENT_WEIGHT_INVALID:" + contentRef);
            }
        }
    }

    public enum MaterialRole {
        PRIMARY_CONTENT,
        BANK,
        WATER,
        GROUND
    }

    public enum GrowthForm {
        PATCH,
        CORRIDOR
    }

    private static void requireText(String value, String reason) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(reason);
    }
}
