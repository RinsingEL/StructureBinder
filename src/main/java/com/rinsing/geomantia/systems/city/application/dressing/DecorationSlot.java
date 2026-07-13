package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.Comparator;

public record DecorationSlot(
        String slotId,
        String programId,
        String paletteSlotId,
        BlockPoint worldAnchor,
        CompiledDecorationProgram.LocalPoint localAnchor,
        int rotationQuarterTurns) {

    public static final Comparator<DecorationSlot> STABLE_ORDER = Comparator
            .comparing((DecorationSlot slot) -> slot.worldAnchor().x())
            .thenComparing(slot -> slot.worldAnchor().z())
            .thenComparing(DecorationSlot::slotId);

    public DecorationSlot {
        rotationQuarterTurns = Math.floorMod(rotationQuarterTurns, 4);
    }
}
