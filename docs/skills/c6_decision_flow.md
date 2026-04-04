# C6 Decision Flow

## Purpose
This document reserves the decision-flow skeleton for future `MCP + Skill` execution of C6 rectangle planning.

## Intended Flow
1. Read C5/C6 context and candidate build areas through MCP.
2. Inspect rectangle decision input and validation constraints.
3. Select rectangle decisions with explicit rationale.
4. Submit rectangle choices through MCP.
5. Read back validation result and summarize whether C7 can continue.

## Rules
- Skill owns the decision policy and pause/resume behavior.
- MCP owns candidate retrieval, submission, and result/status retrieval.
- Decision steps should remain reproducible from MCP-visible artifacts.
