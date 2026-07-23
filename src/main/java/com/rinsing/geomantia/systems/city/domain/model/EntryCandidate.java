package com.rinsing.geomantia.systems.city.domain.model;

public record EntryCandidate(String id, BlockPoint block, String direction, String description) {
    public EntryCandidate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (block == null) {
            throw new IllegalArgumentException("block is required");
        }
    }
}
