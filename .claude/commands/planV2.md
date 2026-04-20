---
name: plan
description: Use when given a requirement, feature request, or task to implement. Creates a structured plan file before any coding begins. Triggers on phrases like "plan this", "create a plan for", "I need to implement", "new feature", "new requirement".
---

# Plan Command

Delegate all planning work to the specialized planner subagent.

## Workflow

1. Receive the requirement from the user.
2. Spawn the `planner` subagent with full context:
    - The requirement content
    - Any relevant files or context the user has provided
3. The `planner` subagent runs independently and creates the plan file.
4. Once the subagent completes, notify the user:
   > "Plan written to `.claude/docs/plans/PLAN-<date>-<slug>.md`. Ready to start Dev phase? Run `/dev` to implement."

## Rules
- NEVER do planning yourself — always delegate to the `planner` subagent.
- NEVER write implementation code during this phase.