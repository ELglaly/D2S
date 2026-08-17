---
description: Review known pitfalls from docs/COMMON_MISTAKES.md before starting work, or capture a new one after fixing a bug.
argument-hint: [add: "<lesson description>"] or leave blank to review
---

$ARGUMENTS

## If no argument (review mode):

Read `docs/COMMON_MISTAKES.md` and summarize the entries most relevant to the current task:

1. List all entry titles
2. Highlight any that touch the area you're about to work in
3. Remind about the most frequently relevant ones:
   - Tenant `findById` bypasses the isolation filter — every `TenantEntity` repository needs an
     explicit `@Query` override (#1)
   - `@Transactional(readOnly=true)` on service read methods that traverse lazy fields
   - `HashMap`, never `Map.of(...)`, for outbox/audit payloads — nullable fields NPE (#3)
   - Slash-style action paths only, never `:verb` (#6)
   - Enum values come from the real `public enum` source file — never guess or reuse a stale doc
     snapshot
   - New FKs to `users(id)`/`schools(id)` need `ON DELETE CASCADE` (#8)

Also check whether the auto-memory system (`feedback_*.md` files) has anything project-specific and
more recent than the doc.

## If argument starts with "add:" (add mode):

A new pitfall was discovered. Two places to record it, not one:

1. **Save a memory** (`feedback_*.md` via the auto-memory system) — this is what carries the lesson
   into future sessions automatically. Structure: rule, **Why** (the actual incident), **How to
   apply** (when this check kicks in).
2. **If it's a concrete, generalizable technical trap** (not a one-off judgment call), also append a
   numbered entry to `docs/COMMON_MISTAKES.md` following the existing format:
   ```markdown
   ## N. <Short symptom-oriented title>

   <Symptom, then the mechanism that causes it.>

   **Fix:** <the concrete fix>.
   → `feedback_<slug>.md`
   ```
3. If it's high-impact enough to be a standing rule (not just a gotcha to remember mid-task), also
   add it to the "Critical Rules" section in `.claude/CLAUDE.md`
4. If the pattern is mechanically detectable in a diff (a regex over the changed file), consider
   adding a check to `tools/hooks/check-known-gotchas.ps1` so it surfaces automatically on the next
   `Edit`/`Write` instead of relying on someone remembering to look it up
