---
name: verify
description: Run SchoolBridge's actual build gate (Spotless + SpotBugs + tests via Maven) and report each gate separately. Use before considering any change done.
allowed_tools: ["Bash", "Read", "Grep"]
---

# /verify

Run this project's real gate, in this exact order. Do not substitute a
generic build/lint/type-check flow — this is a Maven/Java 21 project with no
TypeScript, no ESLint, no coverage gate.

## Steps

1. `mvn spotless:apply` — auto-formats (google-java-format). If this fails,
   report the error and stop; it usually means a syntax error, not a style
   issue.
2. `mvn -B -ntp verify` — runs the full gate: compile, tests, SpotBugs
   (effort=Max, threshold=Medium), and Spotless check. This is a single
   command but three independent failure modes — read the output carefully
   to tell them apart:
   - Compile failure → fix the code, don't touch build config.
   - Test failure → read the specific assertion, don't just re-run. If it's
     "Tests run, Failures: 0" alongside a BUILD FAILURE, suspect a spurious
     Windows fork crash (`docs/COMMON_MISTAKES.md` mentions this pattern) —
     re-run once before investigating further.
   - SpotBugs failure → check `spotbugs-exclude.xml` for whether the bug
     pattern is already suppressed project-wide; if not, fix the actual
     issue (don't add a new suppression without a documented reason).
3. If module-specific: also check `docs/CHECKLISTS.md` → Definition of Done
   for anything beyond the Maven gate (i18n parity, OpenAPI docs updated).

## Output

```
VERIFY: [PASS/FAIL]

Spotless:  [OK/FAIL — reason]
Compile:   [OK/FAIL — reason]
Tests:     [X/Y passed — failing test names if any]
SpotBugs:  [OK/N findings — pattern + file:line]

Ready to close this gate: [YES/NO — what's blocking]
```

There is no coverage gate in this project — don't report a coverage
percentage as pass/fail criteria.

## Arguments

`$ARGUMENTS` may name a single test class to scope to
(`mvn -B -ntp test -Dtest=ClassName`) instead of the full `verify` — use
this for a fast inner-loop check, but always run the full `/verify` before
calling a module done.
