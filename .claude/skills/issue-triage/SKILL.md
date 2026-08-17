---
name: issue-triage
description: GitHub issue triage and categorization for SchoolBridge. Classifies bugs, features, and improvements with priority, domain, and effort labels.
metadata:
  version: "2.0.0"
  domain: workflow
  triggers: issue triage, GitHub issue, bug report, feature request, prioritize issues
  role: workflow
  scope: project-management
  output-format: structured
---

# Issue Triage Skill (SchoolBridge)

## Triage Output Format

For each issue, produce:
```
**Title**: [clear, specific title]
**Type**: Bug | Feature | Improvement | Security | Test | Docs
**Priority**: Critical | High | Medium | Low
**Domain**: common | config | tenant | identity | classes | subjects | grades | announcements | attendance | homework | attachments | notifications | integrations | assistant | test
**Effort**: XS (< 1h) | S (1-4h) | M (4-8h) | L (1-2d) | XL (2d+)
**Summary**: One-sentence description of the problem or request
**Acceptance Criteria**:
- [ ] ...
**Related**: #issue-number, file:line
```

## Priority Classification

| Priority | Criteria |
|----------|---------|
| **Critical** | Data loss, cross-tenant data leak, security vulnerability, production down |
| **High** | Feature not working at all, test suite broken, blocking another feature |
| **Medium** | Partial feature broken, degraded performance, missing validation |
| **Low** | UI polish, minor inconsistency, optional enhancement, documentation |

## Common Issue Patterns in SchoolBridge

### Bug Patterns
- `LazyInitializationException` → add `@Transactional(readOnly=true)` to service
- Cross-tenant data visible → missing `findById` `@Query` override on a `TenantEntity` repository
  (`docs/COMMON_MISTAKES.md` #1) — always **Critical**
- `401` with `instance="/error"` → non-`AuthenticationException` thrown in the filter chain, often
  `setAuthenticated(true)` called after the 3-arg token constructor (`docs/COMMON_MISTAKES.md` #2)
- Enum mapping error → check the exact enum value names in the real `public enum` source file, not
  a doc snapshot
- Notification silently not delivered → check whether a stub/no-op channel is reporting success
  when it shouldn't (`docs/COMMON_MISTAKES.md` #15)
- Teardown leaking data → missing `ON DELETE CASCADE` on a new FK to `users(id)`/`schools(id)`

### Feature Patterns
- New endpoint → Controller + Service/`*Impl` + Repository + Liquibase migration + integration test,
  all within the owning module (see `.claude/rules/java/schoolbridge-modules.md`)
- New cross-module interaction → an outbox row written in the same transaction; a RabbitMQ consumer
  in `integrations` dispatches
- New notification category → add to `NotificationCategory` enum, add per-user preference handling
  in `notifications`, add the dispatch client in `integrations` if a new channel is involved

### Security Issues
- Always **Critical** or **High**
- Mention specific OWASP category if known
- Include remediation suggestion

## Label Recommendations

```
type: bug
type: feature
type: improvement
type: security
priority: critical
priority: high
priority: medium
priority: low
domain: homework
domain: identity
effort: s
effort: m
```
