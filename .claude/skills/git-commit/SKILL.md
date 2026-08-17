---
name: git-commit
description: Conventional commit messages for the SchoolBridge Java/Spring Boot project. Use when committing changes.
metadata:
  version: "2.0.0"
  domain: workflow
  triggers: git commit, commit message, stage and commit
  role: workflow
  scope: commit
  output-format: text
---

# Git Commit Skill (SchoolBridge)

Produces conventional commit messages for the SchoolBridge project.

## Format

```
<type>(<scope>): <short description>

[optional body — explain WHY not WHAT]

[optional footer — breaking changes, issue refs]
```

## Types

| Type | Use When |
|------|----------|
| `feat` | Adding a new feature (new endpoint, new entity, new service) |
| `fix` | Fixing a bug (wrong response, broken logic, failing test) |
| `refactor` | Code change with no behavior change (extract method, rename) |
| `test` | Adding or fixing tests |
| `docs` | Documentation only (README, CLAUDE.md, comments) |
| `chore` | Build, deps, config (pom.xml, Liquibase, Docker Compose) |
| `perf` | Performance improvement (query optimization, caching) |
| `security` | Security hardening, vulnerability fixes |

## Scopes (SchoolBridge modules)

`common`, `config`, `tenant`, `identity`, `classes`, `subjects`, `grades`, `announcements`,
`attendance`, `homework`, `attachments`, `notifications`, `integrations`, `assistant`,
`test`, `migration`

## Examples

```
feat(homework): add reminder sweeper for items due within 2 days

fix(attendance): transition alert to SENT only after dispatch confirms delivery

refactor(announcements): extract materializeRecipients into its own method

test(attachments): add ApplicationContext test for presign-to-download pipeline

chore(migration): add 020-fees-catalog.sql

security(integrations): verify WhatsApp webhook signature before processing

perf(homework): add @Transactional(readOnly=true) to parent feed query
```

## Rules

- Description: present tense, lowercase, no period, max 72 chars
- Body: explain the WHY (what problem, what decision, what trade-off)
- Breaking changes: add `BREAKING CHANGE:` footer
- Reference issues: `Closes #123` or `Fixes #456` in footer
- Never commit: `.env`, `application-local.yml`, credentials (already in `.gitignore`)
