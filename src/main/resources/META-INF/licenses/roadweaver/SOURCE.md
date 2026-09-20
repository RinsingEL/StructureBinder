# RoadWeaver source attribution

Upstream: https://github.com/shiroha-233/RoadWeaver
Local source version: 2.2.2-1.20.1, commit b48bfab (full revision recorded in development record).

`com.rinsing.geomantia.thirdparty.roadweaver.BoundedRoadPathfinder` adapts the priority-queue
A* search, terrain elevation cost, and parent-chain reconstruction from
`common/src/main/java/net/shiroha233/roadweaver/pathfinding/impl/BasicAStarPathfinder.java`.
Geomantia replaces Minecraft/config/cache dependencies with a read-only terrain interface,
requires exact endpoints, adds bounded full-width obstacle checks and deterministic ordering,
and uses cardinal movement for its existing surface-print executor.

No RoadWeaver lifecycle hooks, automatic structure discovery, task queue, assets, native
libraries, or world-writing code are included. LICENSE and upstream NOTICE accompany this file.
