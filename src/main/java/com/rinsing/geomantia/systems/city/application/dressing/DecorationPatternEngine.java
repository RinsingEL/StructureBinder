package com.rinsing.geomantia.systems.city.application.dressing;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class DecorationPatternEngine {
    private final DecorationShapeEvaluator shapeEvaluator;
    private final List<DecorationPatternGenerator> generators;

    public DecorationPatternEngine() {
        this(new DecorationShapeEvaluator(), List.of(new UniformFillPatternGenerator(),
                new CrossSectionRepeatPatternGenerator(), new ParallelRowsPatternGenerator(),
                new EdgeRepeatPatternGenerator(), new GridRepeatPatternGenerator(),
                new DeterministicScatterPatternGenerator()));
    }

    DecorationPatternEngine(DecorationShapeEvaluator shapeEvaluator, List<DecorationPatternGenerator> generators) {
        this.shapeEvaluator = shapeEvaluator;
        this.generators = List.copyOf(generators);
    }

    public List<DecorationSlot> generate(CompiledDecorationProgram program, BlockBounds queryBounds) {
        DecorationPatternGenerator generator = generators.stream()
                .filter(candidate -> candidate.supports(program.pattern()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "CITY_DECORATION_PATTERN_UNSUPPORTED: " + program.pattern().type()));
        List<DecorationSlot> slots = new ArrayList<>(generator.generate(program, queryBounds, shapeEvaluator));
        slots.sort(DecorationSlot.STABLE_ORDER);
        return List.copyOf(slots);
    }

    public List<DecorationSlot> generate(Collection<CompiledDecorationProgram> programs, BlockBounds queryBounds) {
        List<CompiledDecorationProgram> sortedPrograms = new ArrayList<>(programs);
        sortedPrograms.sort(CompiledDecorationProgram.EXECUTION_ORDER);
        List<DecorationSlot> slots = new ArrayList<>();
        for (CompiledDecorationProgram program : sortedPrograms) {
            slots.addAll(generate(program, queryBounds));
        }
        return List.copyOf(slots);
    }
}
