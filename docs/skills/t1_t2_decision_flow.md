# T1/T2 Skill Overview

## Purpose
This file is the navigation layer for the T1/T2 skill rollout.

`T1 != T2 != T3`

`C1 is a separate city skill and is not part of T1/T2.`
`C4-C5 is a separate city semantic planning skill.`

- `T1` selects a target continent/region, runs Q1/Q2, and submits a blueprint.
- `T2` fixes the final cluster/direction, establishes the territory, and exports T2 outputs.
- `C1` configures city structure from existing territory context and prepares the city for C2.
- `C4-C5` defines whitelist-driven district semantics and module-group review before C6.
- `T3` is a separate program stage and is NOT part of this v1 skill rollout.

## Skill Files
- `c1-skill.md`
- `c4-c5-skill.md`
- `t1-skill.md`
- `t2-skill.md`

## Hard Boundaries
- `C1 MUST NOT run C2.`
- `C4-C5 MUST NOT run C6.`
- `T1 MUST NOT establish territory.`
- `T2 MUST NOT run T3.`
- `T3 is NEVER part of this v1 skill rollout.`

## Handoff
- Finish `C1` before entering `C2`.
- Finish `C4-C5` before entering `C6`.
- Finish `T1` before entering `T2`.
- Finish `T2` before optionally entering `T3`.
