# C4-C5 Skill

## Purpose
Use this skill to run the semantic planning chain:

`C4 whitelist -> C4 generate -> C5 generate -> C5 review`

This skill is a whitelist-and-review skill, not a manual district tagging skill.
This skill ends after C5 review and handoff to `C6`.

This skill `MUST NOT` run `C6`.
This skill `MUST NOT` call any `C6+` interface.

## Allowed Tools
### C4
- `city_c4_whitelist_generate`
- `city_c4_whitelist_data`
- `city_c4_generate`
- `city_c4_data`

### C5
- `city_c5_generate`
- `city_c5_data`

## MUST-FOLLOW Workflow
1. `MUST` begin from an existing `city_id` with valid C3 outputs.
2. `MUST` understand the city role and current spatial structure before writing C4 whitelist.
3. `MUST` call `city_c4_whitelist_generate`.
4. `MUST` call `city_c4_generate`.
5. `MUST` call `city_c4_data` and review district function distribution.
6. `MUST` call `city_c5_generate`.
7. `MUST` review the returned C5 review payload, including preview and merge references.
8. `MUST` call `city_c5_data`.
9. `MUST` perform a C5 review and decide whether the result is acceptable.
10. If not acceptable, `MUST` retry the full chain from Step 3 exactly once.
11. After the second pass, `MUST` stop retrying and produce the final summary.
12. `MUST` end with `Next step = Enter C6`.

## C4 Thinking Chain
### Checkpoint 1: City Function Identity
Before writing whitelist, `PAUSE AND THINK DEEPLY` about:
- this city's overall role
- which primary functions are essential
- which secondary functions should be permitted but not dominant
- which functions should be excluded to avoid sameness

### Checkpoint 2: Layer Compatibility
Before submitting whitelist, `PAUSE AND THINK DEEPLY` about:
- whether allowed functions match current layer structure
- whether core/ring/buffer should allow different semantic mixes
- whether the whitelist is too broad and will collapse into generic output

Hard rules:
- `MUST` justify whitelist choices in terms of city role and spatial structure.
- `NEVER` use the same broad whitelist by default across all cities.
- `MUST NOT` submit C4 whitelist without explicit exclusions.

## C5 Review Chain
### Checkpoint 3: Preview Review
After `city_c5_generate`, `PAUSE AND THINK DEEPLY` about:
- whether groups are too uniform
- whether merge structure erased meaningful district contrast
- whether the city now looks too similar to previous cities
- whether the resulting group topology supports later C6/C7 planning

### Checkpoint 4: Retry Decision
Before retrying, `PAUSE AND THINK DEEPLY` about:
- whether the issue comes from C4 whitelist itself
- whether the city identity is under-specified
- whether retrying would meaningfully improve semantics

Hard rules:
- `MUST` review C5 after every generation.
- `MUST` use preview and merge files returned by MCP for review.
- `MUST NOT` skip review and continue directly to C6.
- `NEVER` retry more than once.
- `NEVER` retry only fragments of the chain; the allowed retry goes back to C4 whitelist.

## MUST NOT / NEVER Rules
- `MUST NOT` manually assign district functions one by one.
- `MUST NOT` skip `city_c4_data` review before entering C5.
- `MUST NOT` skip `city_c5_data` review before ending the skill.
- `MUST NOT` call any `C6+` API in this skill.
- `NEVER` assume C5 is automatically triggered by C4.
- `NEVER` continue retrying after the second result.
- `NEVER` accept a C5 result without checking whether group topology and merge behavior fit city identity.

## Required Output Format
The final C4-C5 output `MUST` contain:
- `city role summary`
- `C4 whitelist rationale`
- `C4 review summary`
- `C5 preview review`
- `merge policy summary`
- `retry status`
- `final accept/reject judgment`
- `next step = Enter C6`

Use this structure:

```text
C4-C5 Summary
- City role summary:
- C4 whitelist rationale:
- C4 review summary:
- C5 preview review:
- Merge policy summary:
- Retry status:
- Final accept/reject judgment:
- Next step: Enter C6
```

## Failure Handling
- If whitelist submission fails, stop and report the attempted whitelist and rationale.
- If C4 generation fails, stop and report that district semantics were not generated.
- If `city_c4_data` cannot be read, stop and report that C4 review is incomplete.
- If C5 generation fails, stop and report that module groups were not produced.
- If `city_c5_data` cannot be read, stop and report that C5 review is incomplete.
- If the second run is still unsatisfactory, stop and report the remaining structural problems instead of retrying again.

## Current-Implementation Reality
- Current C4 lets AI choose whitelist, not manual district tagging.
- Current C5 is not automatically triggered by C4.
- Current C4-C5 skill must explicitly call `city_c5_generate`.
- Current skill scope ends before `C6`.

## Handoff to Next Stage
After C4-C5 succeeds:
- state that C4-C5 is complete
- state that `C6` may now proceed
- `MUST NOT` continue into `C6` inside this skill unless another skill explicitly takes over
