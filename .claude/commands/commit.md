---
description: Create a conventional git commit for SchoolBridge with proper type, scope, and message.
argument-hint: [optional message hint]
---

Create a conventional commit for the SchoolBridge project.

Hint: $ARGUMENTS

## Steps

1. **Check what changed**:
   ```bash
   git status
   git diff --staged
   git diff
   ```

2. **Determine commit type and scope**:

   | Type | When |
   |------|------|
   | `feat` | New endpoint, entity, or feature |
   | `fix` | Bug fix (wrong behavior, failing test) |
   | `refactor` | Code change with no behavior change |
   | `test` | Adding or fixing tests |
   | `docs` | README, CLAUDE.md, API map, domain model |
   | `chore` | pom.xml, migrations, Docker, config |
   | `perf` | Query optimization, caching |
   | `security` | Auth, JWT, webhook validation, RLS hardening |

   Scopes (match the module name):
   `common`, `config`, `tenant`, `identity`, `classes`, `subjects`, `grades`, `announcements`,
   `attendance`, `homework`, `attachments`, `notifications`, `integrations`, `assistant`,
   `test`, `migration`

3. **Security check before committing**:
   - [ ] No `application-local.yml` or `.env` staged
   - [ ] No hardcoded secrets (`JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `AES_KEY`, `BLIND_INDEX_KEY`,
         `WHATSAPP_APP_SECRET`, `WHATSAPP_ACCESS_TOKEN`, `OPENAI_API_KEY`, `STORAGE_SECRET_KEY`,
         `DB_PASSWORD`)
   - [ ] No `TODO: remove before commit` comments

4. **Stage and commit**:
   ```bash
   git add <specific files>  # never git add -A blindly
   git commit -m "<type>(<scope>): <description>"
   ```

   Commit message rules:
   - Present tense, lowercase, no period, max 72 chars
   - If breaking change: add `BREAKING CHANGE:` in body
   - If fixes issue: add `Fixes #123` in footer

5. **Example messages**:
   ```
   feat(homework): add reminder sweeper for due-soon items
   fix(attendance): verify parent-child link before accepting a response
   test(attachments): add isolation test for cross-tenant download
   chore(migration): add 020-fees-catalog.sql
   security(integrations): verify WhatsApp webhook signature before dispatch
   refactor(announcements): extract materializeRecipients into its own method
   ```
