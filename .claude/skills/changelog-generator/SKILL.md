---
name: changelog-generator
description: Generate CHANGELOG.md entries from git commits for SchoolBridge releases. Groups changes by type and formats for human readability.
metadata:
  version: "2.0.0"
  domain: workflow
  triggers: changelog, release notes, CHANGELOG.md, generate changelog
  role: workflow
  scope: release
  output-format: markdown
---

# Changelog Generator Skill (SchoolBridge)

Generates CHANGELOG.md entries from git commit history.

## Format

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Features
- Add homework reminder sweeper for due-soon items (`feat(homework)`)
- Add presigned attachment upload with MIME sniffing and AV scan (`feat(attachments)`)

### Bug Fixes
- Fix HomeworkStatus not transitioning to PUBLISHED after recipient materialization (`fix(homework)`)
- Fix LazyInitializationException in AttendanceService by adding readOnly transaction (`fix(attendance)`)

### Security
- Add signature verification on the WhatsApp inbound webhook (`security(integrations)`)
- Add Row-Level Security to tenant-scoped tables (`security(common)`)

### Performance
- Add @Transactional(readOnly=true) to remaining service read methods (`perf(*)`)

### Tests
- Add cross-tenant isolation tests for AttachmentRepository (`test(attachments)`)

### Internal
- Add TenantEntityArchUnitTest to enforce the @Filter convention (`chore(config)`)
- Bump Spring Boot from 3.4.4 to 3.4.5 (`chore(deps)`)
```

## Generation Commands

```bash
# All commits since last tag
git log $(git describe --tags --abbrev=0)..HEAD --oneline

# All commits in a date range
git log --since="2026-03-01" --until="2026-03-28" --oneline --no-merges

# Grouped by type (conventional commits)
git log --oneline --no-merges | grep "^[a-f0-9]* feat"
git log --oneline --no-merges | grep "^[a-f0-9]* fix"
git log --oneline --no-merges | grep "^[a-f0-9]* security"
```

## Inclusion Rules

| Commit Type | Include in Changelog? | Section |
|-------------|----------------------|---------|
| `feat` | Yes | Features |
| `fix` | Yes | Bug Fixes |
| `security` | Yes | Security |
| `perf` | Yes | Performance |
| `test` | Yes (if significant) | Tests |
| `chore` | Yes (if user-facing, e.g., dep updates) | Internal |
| `refactor` | No (internal only) | — |
| `docs` | No (unless README) | — |
| `ci` | No | — |

## Exclude

- Merge commits
- WIP commits
- Test-only commits that fix flaky tests (not user-facing)
