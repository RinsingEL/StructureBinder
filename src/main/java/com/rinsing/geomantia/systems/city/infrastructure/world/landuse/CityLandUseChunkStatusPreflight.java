package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Proves that every LandUse owner with compiled writes is still before FEATURES. */
public final class CityLandUseChunkStatusPreflight {

    public PreflightResult inspect(LandUseAreaPlan plan,
                                   CityLandUseSurfacePrintPlan surfacePrintPlan,
                                   ChunkStatusProbe probe) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(surfacePrintPlan, "surfacePrintPlan");
        Objects.requireNonNull(probe, "probe");
        CityLandUseChunkCompiler compiler = new CityLandUseChunkCompiler();
        CityLandUseChunkCompiler.PreparedSurfacePlan prepared = compiler.prepare(plan, surfacePrintPlan);
        List<OwnerChunk> owners = ownerChunks(plan, compiler, prepared);
        List<ChunkEvidence> evidence = new ArrayList<>(owners.size());
        int blocked = 0;
        int unknown = 0;
        for (OwnerChunk owner : owners) {
            ChunkEvidence item;
            try {
                item = Objects.requireNonNull(probe.inspect(owner.chunkX(), owner.chunkZ()),
                        "CITY_LAND_USE_CHUNK_STATUS_EVIDENCE_REQUIRED");
            } catch (RuntimeException failure) {
                item = ChunkEvidence.unknown(owner.chunkX(), owner.chunkZ(), EvidenceSource.UNKNOWN,
                        "CITY_LAND_USE_CHUNK_STATUS_READ_FAILED: " + failure.getClass().getSimpleName());
            }
            if (item.chunkX() != owner.chunkX() || item.chunkZ() != owner.chunkZ()) {
                throw new IllegalArgumentException("CITY_LAND_USE_CHUNK_STATUS_OWNER_MISMATCH");
            }
            evidence.add(item);
            if (item.state() == EvidenceState.FEATURES_OR_LATER) blocked++;
            if (item.state() == EvidenceState.UNKNOWN) unknown++;
        }
        String reason = blocked > 0 ? "CITY_LAND_USE_CHUNK_ALREADY_AT_FEATURES"
                : unknown > 0 ? "CITY_LAND_USE_CHUNK_STATUS_UNKNOWN"
                : "CITY_LAND_USE_CHUNKS_ELIGIBLE";
        return new PreflightResult(blocked == 0 && unknown == 0, reason, owners.size(), blocked, unknown,
                List.copyOf(evidence));
    }

    public static List<OwnerChunk> ownerChunks(LandUseAreaPlan plan,
                                                CityLandUseSurfacePrintPlan surfacePrintPlan) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(surfacePrintPlan, "surfacePrintPlan");
        CityLandUseChunkCompiler compiler = new CityLandUseChunkCompiler();
        return ownerChunks(plan, compiler, compiler.prepare(plan, surfacePrintPlan));
    }

    private static List<OwnerChunk> ownerChunks(
            LandUseAreaPlan plan,
            CityLandUseChunkCompiler compiler,
            CityLandUseChunkCompiler.PreparedSurfacePlan prepared) {
        Set<OwnerChunk> owners = new LinkedHashSet<>();
        for (LandUseAreaPlan.Area area : plan.areas()) {
            for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                int minChunkX = Math.floorDiv(span.minX(), 16);
                int maxChunkX = Math.floorDiv(span.maxX(), 16);
                int chunkZ = Math.floorDiv(span.z(), 16);
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    owners.add(new OwnerChunk(chunkX, chunkZ));
                }
            }
            for (LandUseAreaPlan.BoundaryLoop loop : area.boundaryLoops()) {
                for (BlockPoint point : loop.points()) {
                    owners.add(new OwnerChunk(Math.floorDiv(point.x(), 16), Math.floorDiv(point.z(), 16)));
                }
            }
        }
        return owners.stream()
                .filter(owner -> compiler.compilePrepared(prepared, owner.chunkX(), owner.chunkZ())
                        .hasRelevantCells())
                .sorted(OwnerChunk.STABLE_ORDER)
                .toList();
    }

    public interface ChunkStatusProbe {
        ChunkEvidence inspect(int chunkX, int chunkZ);
    }

    public enum EvidenceSource {
        LOADED,
        DISK,
        UNKNOWN
    }

    public enum EvidenceState {
        NOT_PRESENT,
        BEFORE_FEATURES,
        FEATURES_OR_LATER,
        UNKNOWN
    }

    public record ChunkEvidence(int chunkX,
                                int chunkZ,
                                EvidenceSource source,
                                EvidenceState state,
                                String statusName,
                                String reasonCode) {
        public ChunkEvidence {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(state, "state");
            statusName = statusName == null ? "" : statusName;
            Objects.requireNonNull(reasonCode, "reasonCode");
        }

        public static ChunkEvidence notPresent(int x, int z) {
            return new ChunkEvidence(x, z, EvidenceSource.DISK, EvidenceState.NOT_PRESENT, "",
                    "CITY_LAND_USE_CHUNK_NOT_PRESENT_ON_DISK");
        }

        public static ChunkEvidence beforeFeatures(int x, int z, EvidenceSource source, String statusName) {
            return new ChunkEvidence(x, z, source, EvidenceState.BEFORE_FEATURES, statusName,
                    "CITY_LAND_USE_CHUNK_BEFORE_FEATURES");
        }

        public static ChunkEvidence featuresOrLater(int x, int z, EvidenceSource source, String statusName) {
            return new ChunkEvidence(x, z, source, EvidenceState.FEATURES_OR_LATER, statusName,
                    "CITY_LAND_USE_CHUNK_ALREADY_AT_FEATURES");
        }

        public static ChunkEvidence status(int x, int z, EvidenceSource source, ChunkStatus status) {
            Objects.requireNonNull(status, "status");
            boolean generated = status.isOrAfter(ChunkStatus.FEATURES);
            return generated ? featuresOrLater(x, z, source, status.toString())
                    : beforeFeatures(x, z, source, status.toString());
        }

        public static ChunkEvidence unknown(int x, int z, EvidenceSource source, String reason) {
            return new ChunkEvidence(x, z, source, EvidenceState.UNKNOWN, "", reason);
        }
    }

    public record PreflightResult(boolean eligible,
                                  String reasonCode,
                                  int ownerChunkCount,
                                  int featuresOrLaterCount,
                                  int unknownCount,
                                  List<ChunkEvidence> chunks) {
        public PreflightResult {
            Objects.requireNonNull(reasonCode, "reasonCode");
            chunks = List.copyOf(chunks);
        }
    }

    public record OwnerChunk(int chunkX, int chunkZ) {
        public static final Comparator<OwnerChunk> STABLE_ORDER =
                Comparator.comparingInt(OwnerChunk::chunkZ).thenComparingInt(OwnerChunk::chunkX);
    }

    /** Reads loaded state first, then the region file NBT directly; it never requests a chunk ticket. */
    public static final class MinecraftChunkStatusProbe implements ChunkStatusProbe {
        private final ServerLevel level;

        public MinecraftChunkStatusProbe(ServerLevel level) {
            this.level = level;
        }

        @Override
        public ChunkEvidence inspect(int chunkX, int chunkZ) {
            if (level == null) {
                return ChunkEvidence.unknown(chunkX, chunkZ, EvidenceSource.UNKNOWN,
                        "CITY_LAND_USE_SERVER_LEVEL_REQUIRED");
            }
            ChunkAccess loaded = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, false);
            if (loaded != null) {
                return ChunkEvidence.status(chunkX, chunkZ, EvidenceSource.LOADED, loaded.getStatus());
            }
            try {
                Optional<CompoundTag> stored = level.getChunkSource().chunkMap.read(new ChunkPos(chunkX, chunkZ)).join();
                if (stored.isEmpty()) return ChunkEvidence.notPresent(chunkX, chunkZ);
                String rawStatus = stored.get().getString("Status");
                ChunkStatus status = rawStatus.isBlank() ? null : ChunkStatus.byName(rawStatus);
                if (status == null) {
                    return ChunkEvidence.unknown(chunkX, chunkZ, EvidenceSource.DISK,
                            "CITY_LAND_USE_DISK_CHUNK_STATUS_INVALID");
                }
                return ChunkEvidence.status(chunkX, chunkZ, EvidenceSource.DISK, status);
            } catch (RuntimeException failure) {
                return ChunkEvidence.unknown(chunkX, chunkZ, EvidenceSource.DISK,
                        "CITY_LAND_USE_DISK_CHUNK_STATUS_READ_FAILED");
            }
        }
    }
}
