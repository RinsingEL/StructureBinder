# C1 Skill

## Purpose
Use this skill to configure a city from an existing territory context.

This skill is a terrain-informed configuration skill, not a territory-selection skill.
This skill ends at `city_c1_generate` plus C1 result verification.

This skill `MUST NOT` run `city_c2_generate`.
This skill `MUST NOT` modify T1/T2 outcomes.
This skill `MUST NOT` continue into district planning.

## Allowed Tools
### Terrain and Context
- `territory_summary`
- `territory_t4_window`
- `get_world_atlas`
- `get_t1_preview_maps`

### City Creation and Readback
- `city_c1_generate`
- `city_stage1_data`
- `city_stage2_data`

## MUST-FOLLOW Workflow
1. `MUST` begin from an existing territory context.
2. `MUST` call `territory_summary`.
3. `MUST` call `territory_t4_window` to inspect the local territory terrain window.
4. `SHOULD` call `get_world_atlas` and `get_t1_preview_maps(region_id)` to understand region-scale terrain.
5. `MUST` explain the intended city role before designing parameters.
6. `MUST` confirm or review `center_x` and `center_z`.
7. `MUST` design all C1 parameters:
   - `target_chunk_count`
   - `bias`
   - `density`
   - `ecology`
   - `allow_water_city`
   - `layers`
8. `MUST` design every layer independently, including its role and area intention.
9. `MUST` call `city_c1_generate`.
10. `MUST` call `city_stage1_data`.
11. `SHOULD` call `city_stage2_data` to confirm downstream compatibility.
12. `MUST` end with a C1 summary and a handoff to `C2`.

## Anti-Homogenization Core Rules
- `MUST` treat every city as a distinct urban form, not a template reuse.
- `NEVER` reuse the same layer relationship, area emphasis, and size profile by default.
- `MUST` justify why this city's total size differs from or resembles previous cities.
- `MUST` justify every layer's area intention independently.
- `NEVER` let all cities collapse into the same `core + generic urban + generic buffer` pattern without terrain-specific reasoning.

## PAUSE AND THINK DEEPLY Checkpoints
### Checkpoint 1: Preview Interpretation
After reading `territory_summary`, `territory_t4_window`, and optional region-scale preview, `PAUSE AND THINK DEEPLY` about:
- terrain posture
- coast / ridge / basin / corridor / plateau / floodplain signals
- whether the city should feel compact, stretched, ringed, layered, defensive, open, or terraced
- whether the city should be small-but-dense, large-and-layered, or wide-and-buffered

`DO NOT CONTINUE UNTIL` you can explain how terrain should influence city form.

### Checkpoint 2: City Role
Before parameter design, `PAUSE AND THINK DEEPLY` about:
- why this city exists
- what role it plays inside the territory
- whether it is ceremonial, economic, defensive, logistical, or agricultural
- how that role should affect size, density, and layering

`NEVER` treat all cities as generic capitals.

### Checkpoint 3: Layer Area Design
Before writing `layers`, `PAUSE AND THINK DEEPLY` about every layer individually.

For each layer, the skill `MUST` state:
- layer type
- intended role
- relative area size
- why it should be smaller or larger than the previous layer
- how it relates to terrain and city identity

The skill `MUST` reason explicitly about:
- which layer should dominate area
- which layer should stay compact
- whether a ring or wall layer should be thin, thick, or absent
- whether the buffer should be light, broad, defensive, or ecological

`DO NOT CONTINUE UNTIL` every layer has an explicit area rationale.

### Checkpoint 4: Anti-Homogenization Review
Before submission, `PAUSE AND THINK DEEPLY` and compare this city against the generic pattern the project tends to fall into.

The skill `MUST` ask:
- What makes this city structurally different from other recent cities?
- Is the core too predictably dominant?
- Is the outer buffer too mechanically uniform?
- Are layer weights and total scale too similar to previous outputs?
- Does the terrain justify a more unusual ratio or hierarchy?

`NEVER` submit if the city still reads as a near-copy of prior layer patterns without explicit justification.

## MUST NOT / NEVER Rules
- `MUST NOT` run `city_c2_generate` inside C1.
- `MUST NOT` skip terrain preview or context reading.
- `MUST NOT` leave `bias`, `density`, `ecology`, or `layers` unexplained.
- `MUST NOT` treat the provided center as automatically correct without review.
- `NEVER` use the same layer count or weight profile only because it worked before.
- `NEVER` default every city to a near-identical scale.
- `NEVER` assign layer weights without explaining the intended area relationship.
- `NEVER` continue into district planning or later city stages in this skill.

## Required Output Format
The final C1 output `MUST` contain:
- `territory context`
- `preview interpretation`
- `selected center_x / center_z`
- `target_chunk_count`
- `bias`
- `density`
- `ecology`
- `allow_water_city`
- `layer-by-layer area rationale`
- `city_id`
- `risks / uncertainty`
- `next step = Enter C2`

Use this structure:

```text
C1 Summary
- Territory context:
- Preview interpretation:
- Selected center:
- Target chunk count:
- Bias:
- Density:
- Ecology:
- Allow water city:
- Layer-by-layer area rationale:
- City ID:
- Risks / uncertainty:
- Next step: Enter C2
```

`Layer-by-layer area rationale` `MUST` be written layer by layer and `MUST NOT` be compressed into one sentence.

## Failure Handling
- If territory context is missing, stop and report that C1 requires an existing territory.
- If preview or context data is too weak, stop and report that city form cannot be justified.
- If the selected center appears too wet or too contradictory to terrain, revise the center once before failing.
- If the layer design remains too generic after anti-homogenization review, revise once before submission.
- If `city_c1_generate` fails, stop and report the exact submitted parameters.
- If `city_stage1_data` cannot be read back, stop and report that C1 verification is incomplete.

## Current-Implementation Reality
- Current C1 does not have its own candidate-scan stage.
- Current C1 is a terrain-informed configuration skill, not a territory-selection skill.
- Current C1 should use preview and context reading to improve parameter design, not pretend there is already a formal city siting Q1/Q2.

## Handoff to Next Stage
After C1 succeeds:
- state that C1 is complete
- state that `C2` should now generate terrain and land-claim outputs
- `MUST NOT` continue into `C2` inside this skill unless another skill explicitly takes over
