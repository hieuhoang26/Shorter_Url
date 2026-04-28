---
name: dev
description: Use when implementing a feature that has an existing plan file. Reads the plan and executes each step systematically. Triggers on phrases like "implement the plan", "start dev", "code this up", "/dev", or after a Plan phase completes.
---

# Dev Skill

You are a senior Java developer. Your job is to implement code **strictly following the plan file** produced by the Plan phase. No scope creep. No reinventing. Faithful execution.

## Workflow

### Step 0 — Load Relevant Skills

Before writing any code, scan the plan to determine what types of output will be produced, then read the relevant SKILL.md files.

**Skill locations:**
- `/mnt/skills/public/<skill-name>/SKILL.md` — public skills (docx, pdf, pptx, xlsx, frontend-design, file-reading, pdf-reading, product-self-knowledge)
- `/mnt/skills/user/<skill-name>/SKILL.md` — user-defined skills (mermaid-diagram, etc.)

**Trigger mapping — read the SKILL.md if the plan involves:**

| Plan involves... | Read this skill |
|---|---|
| Word document generation / `.docx` output | `/mnt/skills/public/docx/SKILL.md` |
| PDF generation or reading | `/mnt/skills/public/pdf/SKILL.md` |
| PowerPoint / slide deck | `/mnt/skills/public/pptx/SKILL.md` |
| Excel / spreadsheet | `/mnt/skills/public/xlsx/SKILL.md` |
| Web UI / React / HTML components | `/mnt/skills/public/frontend-design/SKILL.md` |
| Reading uploaded files | `/mnt/skills/public/file-reading/SKILL.md` |
| Diagrams / flowcharts / Mermaid | `/mnt/skills/user/mermaid-diagram/SKILL.md` |
| Anthropic API / Claude product details | `/mnt/skills/public/product-self-knowledge/SKILL.md` |

Read ALL skills that apply. If unsure, read it — it costs little and prevents mistakes.

After reading, confirm: `> Skills loaded: [list of SKILL.md files read]`

---

### Step 1 — Load the Plan
- Look for the most recent or most relevant plan in `docs/plans/`.
- If multiple plans exist, list them and ask the user which one to implement.
- Read the plan fully before writing a single line of code.
- Confirm: "Implementing plan: `PLAN-<date>-<slug>.md`. Starting now."

### Step 2 — Pre-flight Check
Before coding, verify:
- [ ] All dependencies/libraries mentioned in the plan are already in `pom.xml` / `build.gradle`. Add if missing.
- [ ] The target packages/directories exist. Create if needed.
- [ ] No conflicts with existing code that would require re-planning.

If a blocker is found → STOP and notify the user. Do not proceed with workarounds that change the plan's scope.

### Step 3 — Implement Step by Step

Work through each `- [ ] Step N` from the plan **in order**. For each step:

1. **Announce** the step: `> Implementing Step N: <description>`
2. **Write the code** following these Java standards:
    - Follow existing naming conventions found in the codebase
    - Use constructor injection (never field injection with `@Autowired`)
    - Prefer `Optional` over null returns
    - Add `@Override` on all overridden methods
    - Keep methods short and single-purpose
    - Handle exceptions explicitly — no swallowed exceptions
3. **Mark complete**: Update the plan file checkbox from `[ ]` to `[x]`

### Step 4 — Testing

After all implementation steps are done:
- Write unit tests for every new service/component
- Write integration tests for every new endpoint
- Test class naming: `<ClassName>Test` for unit, `<ClassName>IT` for integration
- Use `@ExtendWith(MockitoExtension.class)` for unit tests
- Cover: happy path, edge cases, error cases

Run tests if possible:
```bash
./mvnw test -pl <module> -Dtest=<TestClassName>
```

### Step 5 — Self-Review Checklist

Before declaring done, check:
- [ ] All plan steps are marked `[x]`
- [ ] No TODO or placeholder code left
- [ ] All new public methods have Javadoc
- [ ] No unused imports
- [ ] Consistent code style with the rest of the codebase
- [ ] No hardcoded values that should be in config/constants
- [ ] Error cases handled (not just happy path)
- [ ] Tests pass

### Step 6 — Update the Plan File

Change the plan's status from `DRAFT` to `DONE` and add:
```markdown
**Completed**: YYYY-MM-DD  
**Summary of changes**: Brief list of files created/modified
```

### Step 7 — Summary Report

Output a completion summary:
```
✅ Dev complete for: <Plan Title>

Files created:
  - src/main/java/com/example/service/FooService.java
  - src/main/java/com/example/controller/FooController.java

Files modified:
  - src/main/resources/application.yml

Tests written:
  - src/test/java/com/example/service/FooServiceTest.java

Next steps (if any):
  - Pending: [anything flagged as out of scope or deferred]

```

## Rules
- NEVER add features not in the plan. If something seems needed, note it as a follow-up.
- NEVER skip steps — even if they seem trivial.
- NEVER skip Step 0 — skills must be loaded before any code is written.
- NEVER modify the plan's scope. If the plan is wrong, stop and run `/plan` again.
- ALWAYS keep the plan file updated as steps complete.
- If blocked on a step, explain clearly and ask for guidance rather than guessing.

## Java Code Quality Standards

### Structure
```
src/
  main/java/com/example/
    controller/   ← REST endpoints only, no business logic
    service/      ← Business logic, transactions
    repository/   ← Data access only
    model/        ← Entities and domain objects
    dto/          ← Request/Response DTOs
    exception/    ← Custom exceptions
    config/       ← Spring configuration classes
  test/java/com/example/
    controller/   ← Integration tests
    service/      ← Unit tests
```

### Patterns to follow
- Services return domain objects or DTOs, never entities directly to controllers
- Use `@Transactional` at service layer, not repository or controller
- Custom exceptions extend `RuntimeException` with meaningful messages
- Controllers only validate input and delegate to services