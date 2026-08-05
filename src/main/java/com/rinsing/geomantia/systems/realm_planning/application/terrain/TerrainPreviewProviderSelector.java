package com.rinsing.geomantia.systems.realm_planning.application.terrain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TerrainPreviewProviderSelector {
    private final List<TerrainPreviewProvider> providers;

    public TerrainPreviewProviderSelector(List<TerrainPreviewProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("At least one terrain preview provider is required.");
        }
        Set<String> providerIds = new HashSet<>();
        List<TerrainPreviewProvider> copy = new ArrayList<>(providers.size());
        for (TerrainPreviewProvider provider : providers) {
            TerrainPreviewProvider nonNullProvider = Objects.requireNonNull(provider, "provider");
            String providerId = nonNullProvider.descriptor().providerId();
            if (!providerIds.add(providerId)) {
                throw new IllegalArgumentException("Duplicate terrain preview providerId: " + providerId);
            }
            copy.add(nonNullProvider);
        }
        this.providers = List.copyOf(copy);
    }

    public TerrainPreviewProviderSelection select() {
        return select(true);
    }

    public TerrainPreviewProviderSelection select(boolean preferGeneratorNative) {
        return preferGeneratorNative ? selectProviders(true, new ArrayList<>())
                : selectFallback("generator_native_disabled");
    }

    public TerrainPreviewProviderSelection selectFallback(String reason) {
        List<String> rejected = new ArrayList<>();
        rejected.add(reason == null || reason.isBlank() ? "generator_native_disabled" : reason.trim());
        return selectProviders(false, rejected);
    }

    private TerrainPreviewProviderSelection selectProviders(boolean includeGeneratorNative, List<String> rejected) {
        for (TerrainPreviewProvider provider : providers) {
            TerrainPreviewProviderDescriptor descriptor = provider.descriptor();
            if (!includeGeneratorNative && descriptor.sourceKind() == TerrainPreviewSourceKind.GENERATOR_NATIVE) {
                continue;
            }
            TerrainPreviewProviderAvailability availability;
            try {
                availability = Objects.requireNonNull(provider.availability(),
                        "Provider availability must not be null: " + descriptor.providerId());
            } catch (RuntimeException ex) {
                rejected.add(descriptor.providerId() + "=availability_check_failed:" + safeMessage(ex));
                continue;
            }
            if (!availability.available()) {
                rejected.add(descriptor.providerId() + "=" + availability.reason());
                continue;
            }
            return new TerrainPreviewProviderSelection(
                    provider,
                    descriptor.providerId(),
                    descriptor.sourceKind().contractName(),
                    descriptor.fastPath(),
                    String.join(";", rejected),
                    descriptor.sourceFingerprint(),
                    descriptor.samplingSemantics()
            );
        }
        throw new IllegalStateException("No terrain preview provider is available: " + String.join(";", rejected));
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message.trim();
    }
}
