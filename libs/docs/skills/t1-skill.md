# T1 Skill

## Purpose
Use this skill to select a target continent or region, run T1 Q1/Q2 candidate selection, and submit a final blueprint.

This skill ends at `t1_generate_blueprint`.

This skill `MUST NOT` establish territory.
This skill `MUST NOT` run T2.
This skill `MUST NOT` run T3.

## Allowed Tools
- `get_world_atlas`
- `get_t1_preview_maps`
- `scan_local_candidates`
- `query_region_pick`
- `t1_generate_blueprint`

## MUST-FOLLOW Workflow
1. `MUST` call `get_world_atlas` before deciding any target continent.
2. `MUST` choose a candidate `region_id` or `target_continent_id` from atlas data.
3. `MUST` call `get_t1_preview_maps(region_id)` before defining final geographic preferences.
4. `MUST` define exactly 2 or 3 explicit `interest_groups` from the civilization goal.
5. `MUST` call `scan_local_candidates(region_id=...)` with the chosen `interest_groups`.
6. `MUST` analyze candidate clusters, ASCII maps, and overlay preview before choosing a final cluster.
7. `MUST` call `query_region_pick` to lock the final cluster and final `point_mode`.
8. `MUST` call `t1_generate_blueprint` after Q2 succeeds.
9. `MUST` end with a T1 summary and a handoff to T2.

## PAUSE AND THINK DEEPLY Checkpoints
### Checkpoint 1: Preview Interpretation
After `get_t1_preview_maps`, `PAUSE AND THINK DEEPLY` about:
- terrain relief
- ridge/highland vs basin/plain
- coastline access
- likely expansion envelope
- whether the civilization theme prefers defensible, fertile, coastal, inland, or corridor-like terrain

`DO NOT CONTINUE UNTIL` you can explain why the chosen region is worth scanning.

### Checkpoint 2: Interest Group Design
Before `scan_local_candidates`, `PAUSE AND THINK DEEPLY` and define 2 or 3 explicit `interest_groups`.

Each group `MUST` express a clear geographic intention such as:
- highland defensible core
- low-slope fertile basin
- coastal trade access
- valley corridor

`NEVER` skip directly from atlas or preview to blueprint submission.

### Checkpoint 3: Cluster and Point Selection
Before `query_region_pick`, `PAUSE AND THINK DEEPLY` about:
- cluster size
- terrain description
- spatial position in the region
- how the cluster supports the civilization identity
- whether `center`, `north`, `south`, `east`, `west`, or `random_cardinal` best matches the intended capital posture

`DO NOT CONTINUE UNTIL` a final `query_region_pick` result exists.

### Checkpoint 4: Blueprint Finalization
Before `t1_generate_blueprint`, `PAUSE AND THINK DEEPLY` about:
- base power
- slope/water/forest penalties
- preferred biomes
- avoid biomes
- whether the final expansion policy matches the selected geography

`NEVER` submit `t1_generate_blueprint` without explicitly citing the chosen cluster and `point_mode` rationale.

## MUST NOT / NEVER Rules
- `MUST NOT` call `establish_territory` inside T1.
- `MUST NOT` call `t1_select_cluster` inside T1.
- `MUST NOT` call `t2_direction_candidates` or `t2_select_direction` inside T1.
- `MUST NOT` call `t3_run_continent` inside T1.
- `NEVER` default the target continent without citing atlas evidence.
- `NEVER` define vague `interest_groups`; each group must map to a clear terrain intention.
- `NEVER` skip Q1 or Q2.
- `NEVER` produce a blueprint without naming the selected cluster and final `point_mode`.

## Required Output Format
The final T1 output `MUST` contain:
- `selected region_id / continent_id`
- `interest_groups`
- `chosen cluster`
- `chosen point_mode`
- `blueprint summary`
- `risks / uncertainty`
- `next step = Enter T2`

Use this structure:

```text
T1 Summary
- Selected region/continent:
- Interest groups:
- Chosen cluster:
- Chosen point mode:
- Blueprint summary:
- Risks / uncertainty:
- Next step: Enter T2
```

## Failure Handling
- If atlas data is missing, stop and report that W4 data is required.
- If preview generation fails, stop and report that T1 preview data is unavailable.
- If `scan_local_candidates` returns no useful clusters, revise `interest_groups` once before giving up.
- If `query_region_pick` fails, stop and report that no final T1 point is locked.
- If blueprint submission fails, stop and report the exact failed inputs.

## Handoff to Next Stage
After T1 succeeds:
- state that T1 is complete
- state that T2 should now decide final territory direction and capital orientation
- `MUST NOT` continue into T2 in the same skill unless a different skill explicitly takes over
