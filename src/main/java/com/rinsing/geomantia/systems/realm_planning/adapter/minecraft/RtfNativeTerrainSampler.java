package com.rinsing.geomantia.systems.realm_planning.adapter.minecraft;

import java.util.Objects;

/** Public, chunk-free facade over the optional ReTerraForged two-dimensional heightmap bridge. */
public final class RtfNativeTerrainSampler {
    private RtfNativeTerrainSampler() {
    }

    public static Probe probe(Object randomState, Object registryAccess) {
        RtfTerrainPreviewReflectionBridge.Probe bridge =
                RtfTerrainPreviewReflectionBridge.probe(randomState, registryAccess);
        if (!bridge.available()) {
            return Probe.unavailable(bridge.unavailableReason());
        }
        return Probe.available(new Sampler(bridge.binding()));
    }

    public record Probe(Sampler sampler, String unavailableReason) {
        public Probe {
            unavailableReason = unavailableReason == null ? "" : unavailableReason.trim();
            if ((sampler == null) == unavailableReason.isEmpty()) {
                throw new IllegalArgumentException("Exactly one of sampler or unavailableReason is required.");
            }
        }

        public static Probe available(Sampler sampler) {
            return new Probe(Objects.requireNonNull(sampler, "sampler"), "");
        }

        public static Probe unavailable(String reason) {
            return new Probe(null, reason == null || reason.isBlank() ? "rtf_fast_path_unavailable" : reason);
        }

        public boolean available() {
            return sampler != null;
        }
    }

    public static final class Sampler {
        private final RtfTerrainPreviewReflectionBridge.Binding binding;

        private Sampler(RtfTerrainPreviewReflectionBridge.Binding binding) {
            this.binding = Objects.requireNonNull(binding, "binding");
        }

        public Sample sample(int blockX, int blockZ) {
            RtfTerrainPreviewReflectionBridge.RawSample raw = binding.sample(blockX, blockZ);
            return new Sample(raw.elevation(), raw.water(), raw.waterSurfaceElevation());
        }

        public String apiVariant() {
            return binding.apiVariant();
        }
    }

    public record Sample(int elevation, boolean water, int waterSurfaceElevation) {
    }
}
