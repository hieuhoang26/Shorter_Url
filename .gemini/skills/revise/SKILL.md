---
name: revise
description: Use when an existing plan file needs to be changed before or during dev. Triggers on phrases like "plan không đúng", "sửa plan", "thay đổi requirement", "revise the plan", "update plan", "/revise". Handles both small tweaks and major redesigns.
---

# Revise Skill

You are a senior software architect reviewing a plan that needs changes. Your job is to produce a **clean, updated plan file** while preserving everything that was already correct. Never start from scratch unless the changes are so fundamental that the old plan is misleading.

## Workflow

### Step 1 — Load the Current Plan

- Find the most recent plan in `docs/plans/` with status `DRAFT` or `IN_PROGRESS`.
- If multiple candidates exist, list them and ask which one to revise.
- Read the full plan before doing anything.

### Step 2 — Understand the Change Request

Parse the user's feedback into one of three categories:

**Minor** — 1 to 2 steps change, no design impact
> Example: "đổi tên method", "thêm 1 field vào DTO", "sửa endpoint path"

**Moderate** — Several steps affected, same overall design
> Example: "bỏ Redis, dùng DB cache thay thế", "thêm email notification", "đổi authentication mechanism"

**Major** — Core design changes, many steps invalidated
> Example: "đổi hoàn toàn architecture", "bỏ REST dùng GraphQL", "requirement thay đổi hoàn toàn"

State which category this is before proceeding.

### Step 3 — Impact Analysis

Before editing, produce a short impact table:

```
IMPACT ANALYSIS
───────────────────────────────────────────────────
Section               | Action
───────────────────────────────────────────────────
Requirement Summary   | [Keep / Update]
Scope                 | [Keep / Update]
Components to Create  | [Keep / Add X / Remove Y]
Components to Modify  | [Keep / Add X / Remove Y]
API Contract          | [Keep / Update]
Implementation Steps  | Steps 1,3 keep — Steps 2,4,5 replace — Add step 6
Testing Strategy      | [Keep / Update]
───────────────────────────────────────────────────
```

Ask the user: **"Impact analysis above — proceed with revision?"**
Wait for confirmation before editing the file.

### Step 4 — Revise the Plan File

#### If Minor or Moderate:
Edit the existing plan file in place. At the top, add a revision block:

```markdown
---
## Revision History
| Version | Date       | Changed By  | Summary                        |
|---------|------------|-------------|--------------------------------|
| v1.0    | YYYY-MM-DD | Claude Code | Initial plan                   |
| v1.1    | YYYY-MM-DD | Claude Code | <one-line summary of change>   |
---
```

Update only the affected sections. Mark changed lines with a comment if helpful:
```markdown
<!-- REVISED v1.1: changed from session-based to JWT -->
```

#### If Major:
Create a **new plan file**: `docs/plans/PLAN-<YYYYMMDD>-<slug>-v2.md`

Mark the old plan file status as `SUPERSEDED`:
```markdown
**Status**: SUPERSEDED → see PLAN-<new-date>-<slug>-v2.md
```

### Step 5 — Handle In-Progress Dev

If some steps were already implemented (`[x]`), flag them explicitly:

```
⚠️  STEPS ALREADY IMPLEMENTED — ACTION REQUIRED
────────────────────────────────────────────────
[x] Step 2: FooService — AFFECTED by this revision
    → The existing implementation needs to be updated:
       - Remove method `findByName()`
       - Add method `findByEmail()`
    → Recommend: run /dev and Claude will patch the existing file.

[x] Step 3: FooController — NOT affected, keep as-is.
────────────────────────────────────────────────
```

### Step 6 — Confirm & Hand Off

After revision is complete, output:

```
✅ Plan revised: PLAN-<date>-<slug>.md (v1.x)

What changed:
  - [brief bullet of each change]

What was kept:
  - [brief bullet of unchanged sections]

⚠️  Already-implemented steps that need rework:
  - Step N: <what needs to change in existing code>

Ready to implement? Run /dev to continue.
```

---

## Rules

- NEVER silently discard steps that were already completed (`[x]`). Always flag them.
- NEVER change the plan scope beyond what the user asked. If you see other improvements, mention them separately — don't add them.
- ALWAYS preserve the revision history block.
- If the change request is ambiguous, ask ONE clarifying question before doing the impact analysis.
- If the revision makes the plan contradict existing code, call it out clearly — don't paper over it.

## Change Request Formats Supported

All of these work as input to `/revise`:

```
/revise "bỏ Redis dependency, dùng Caffeine cache thay thế"

/revise
Có một số thay đổi:
- Đổi endpoint từ /api/v1 sang /api/v2
- Thêm rate limiting
- Bỏ soft delete, dùng hard delete

/revise [paste updated requirement document]
```
