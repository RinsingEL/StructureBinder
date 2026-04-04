# T2 Skill

## Purpose
Use this skill to convert an existing T1 result into a finalized territory or capital decision.

This skill starts from confirmed T1 output and ends after T2 export.

This skill `MUST NOT` run T3.

## Allowed Tools
- `t1_candidates_for_continent`
- `t1_select_cluster`
- `t2_direction_candidates`
- `t2_select_direction`
- `establish_territory`
- `run_workflow_stage(stage_id="T2")`
- `territory_summary`
- `get_territory_status`

## MUST-FOLLOW Workflow
1. `MUST` begin from an existing T1 result, blueprint, or territory context.
2. `MUST` call `t1_candidates_for_continent`.
3. If cluster is not fixed, `MUST` call `t1_select_cluster`.
4. `MUST` call `t2_direction_candidates`.
5. `MUST` compare all available directions before making a final direction decision.
6. `MUST` call `t2_select_direction`.
7. `MUST` confirm territory creation parameters before establishing territory.
8. `MUST` call `establish_territory`.
9. `MUST` call `run_workflow_stage(stage_id="T2")` or the equivalent T2 export path.
10. `MUST` end with a T2 summary and a handoff to optional T3.

## PAUSE AND THINK DEEPLY Checkpoints
### Checkpoint 1: Continent Candidate State
After `t1_candidates_for_continent`, `PAUSE AND THINK DEEPLY` about:
- whether the target territory is blocked
- whether the current cluster state is already fixed
- whether another cluster selection is required before T2 can proceed

`DO NOT CONTINUE UNTIL` the chosen continent and target cluster state are clear.

### Checkpoint 2: Direction Candidate Analysis
After `t2_direction_candidates`, `PAUSE AND THINK DEEPLY` about:
- `center`
- `north`
- `south`
- `east`
- `west`

For each available direction, compare:
- terrain posture
- defensibility
- access routes
- likely expansion meaning
- whether it matches the intended capital role

### Checkpoint 3: Final Direction Decision
Before `t2_select_direction`, `PAUSE AND THINK DEEPLY` and state:
- why the chosen direction is the best capital orientation
- why rejected directions are worse

`NEVER` choose direction by defaulting to `center` without explicit rationale.

`DO NOT CONTINUE UNTIL` `t2_select_direction` succeeds.

### Checkpoint 4: Territory Finalization
Before `establish_territory`, `PAUSE AND THINK DEEPLY` about:
- `power`
- `mountain_cost`
- `water_cost`
- `color`
- whether the territory parameters match the selected direction and geography

`MUST` confirm these values before territory creation.

## MUST NOT / NEVER Rules
- `MUST NOT` run `t3_run_continent` inside T2.
- `MUST NOT` skip `t2_direction_candidates`.
- `MUST NOT` create territory before direction is fixed.
- `MUST NOT` assume the cluster is already fixed without checking continent candidate state.
- `NEVER` default to `center` without explicit justification.
- `NEVER` skip territory parameter confirmation.
- `NEVER` continue to T3 as part of this v1 T2 skill.

## Required Output Format
The final T2 output `MUST` contain:
- `selected cluster`
- `selected direction`
- `territory parameter summary`
- `T2 export result`
- `risks / uncertainty`
- `next step = Optionally enter T3`

Use this structure:

```text
T2 Summary
- Selected cluster:
- Selected direction:
- Territory parameter summary:
- T2 export result:
- Risks / uncertainty:
- Next step: Optionally enter T3
```

## Failure Handling
- If `t1_candidates_for_continent` shows the target is blocked, stop and report that T1 selection must be revised.
- If cluster is missing and `t1_select_cluster` fails, stop and report that no valid T1 cluster is fixed.
- If `t2_direction_candidates` returns no useful direction, stop and report that T2 cannot resolve capital orientation.
- If `t2_select_direction` fails, stop and report that direction remains unresolved.
- If `establish_territory` fails, stop and report the exact territory parameters used.
- If T2 export fails, stop and report that T2 outputs are incomplete.

## Handoff to Next Stage
After T2 succeeds:
- state that T2 is complete
- state that T3 is now optional and separate
- `MUST NOT` automatically run T3 inside this skill
