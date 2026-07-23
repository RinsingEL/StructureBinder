package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.List;

public record CompiledDecorationProgramPlan(String schemaVersion, String cityId, String catalogHash,
                                            String styleProfileId, String styleProfileHash,
                                            List<HardObstacle> hardObstacles,
                                            List<CompiledDecorationProgram> programs) {
    public static final String SCHEMA = "city_decoration_compiled_program_plan.v0.4";

    public CompiledDecorationProgramPlan {
        if (!SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_SCHEMA_UNSUPPORTED: " + schemaVersion);
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_CITY_ID_REQUIRED");
        }
        if (catalogHash == null || catalogHash.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_CATALOG_HASH_REQUIRED");
        }
        if (styleProfileId == null || !styleProfileId.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_ID_INVALID");
        }
        if (styleProfileHash == null || styleProfileHash.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_STYLE_PROFILE_HASH_REQUIRED");
        }
        hardObstacles = List.copyOf(hardObstacles);
        programs = List.copyOf(programs);
        if (programs.isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAMS_REQUIRED");
        }
        long distinctIds = programs.stream().map(CompiledDecorationProgram::programId).distinct().count();
        if (distinctIds != programs.size()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_ID_DUPLICATE");
        }
    }

    public CompiledDecorationProgramPlan(String schemaVersion, String cityId, String catalogHash,
                                         List<CompiledDecorationProgram> programs) {
        this(schemaVersion, cityId, catalogHash, "direct_catalog", "direct_catalog", List.of(), programs);
    }

    public CompiledDecorationProgramPlan(String schemaVersion, String cityId, String catalogHash,
                                         List<HardObstacle> hardObstacles,
                                         List<CompiledDecorationProgram> programs) {
        this(schemaVersion, cityId, catalogHash, "direct_catalog", "direct_catalog", hardObstacles, programs);
    }

    public List<CompiledDecorationProgram> programsInExecutionOrder() {
        List<CompiledDecorationProgram> sorted = new ArrayList<>(programs);
        sorted.sort(CompiledDecorationProgram.EXECUTION_ORDER);
        return List.copyOf(sorted);
    }

    public record HardObstacle(String obstacleType, String sourceRef, BlockBounds blockBounds) {
        public HardObstacle {
            if (obstacleType == null || obstacleType.isBlank() || sourceRef == null || sourceRef.isBlank()
                    || blockBounds == null) {
                throw new IllegalArgumentException("CITY_DECORATION_HARD_OBSTACLE_INVALID");
            }
        }
    }
}
