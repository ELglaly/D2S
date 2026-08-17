# Skills

Skills are reusable prompts that teach Claude specific patterns for SchoolBridge Java development.

## Structure Convention

Each skill folder contains:

| File | Purpose | Audience |
|------|---------|----------|
| `SKILL.md` | Instructions for Claude | AI (loaded with `view`) |

## Available Skills

### Workflow
| Skill | Description |
|-------|-------------|
| [git-commit](git-commit/) | Conventional commit messages for SchoolBridge (feat/fix/security/perf scopes) |
| [changelog-generator](changelog-generator/) | Generate changelogs from git commits |
| [issue-triage](issue-triage/) | GitHub issue triage, prioritization, and labeling |

### Code Quality
| Skill | Description |
|-------|-------------|
| [java-code-review](java-code-review/) | Systematic Java code review (CRITICAL/HIGH/MEDIUM/LOW) |
| [api-contract-review](api-contract-review/) | REST API audit: HTTP semantics, ApiResponse envelope, i18n, security |
| [concurrency-review](concurrency-review/) | Thread safety, @Scheduled sweepers, outbox/webhook idempotency, RLS + tenant context |
| [performance-smell-detection](performance-smell-detection/) | N+1 queries, missing pagination, eager fetching, stream boxing |
| [test-quality](test-quality/) | REST Assured + JUnit 5 integration test patterns and checklist |
| [maven-dependency-audit](maven-dependency-audit/) | Audit dependencies for updates, CVEs, and unused deps |
| [security-audit](security-audit/) | OWASP Top 10, JWT validation, tenant isolation, RLS, webhook signature security |

### Architecture & Design
| Skill | Description |
|-------|-------------|
| [architecture-review](architecture-review/) | Layer boundaries, tenant isolation, package structure |
| [solid-principles](solid-principles/) | S.O.L.I.D. with Spring Boot examples |
| [design-patterns](design-patterns/) | Factory, Builder, Strategy, Observer, Decorator, etc. |
| [clean-code](clean-code/) | DRY, KISS, YAGNI, naming, function size |

### Framework & Data
| Skill | Description |
|-------|-------------|
| [spring-boot](spring-boot/) | Core Spring Boot 3.4.x patterns tuned for SchoolBridge |
| [spring-boot-patterns](spring-boot-patterns/) | Caching, outbox events, scheduling, validation patterns |
| [java-migration](java-migration/) | Java 21 features: records, pattern matching, virtual threads |
| [jpa-patterns](jpa-patterns/) | JPA/Hibernate patterns (N+1, lazy loading, transactions) |
| [logging-patterns](logging-patterns/) | Structured logging (JSON), SLF4J, MDC, AI-friendly formats |
| [code-quality](code-quality/) | General code quality analysis and metrics |

## Usage

Skills are automatically loaded by Claude Code based on context. You can also invoke them directly:

```bash
# Automatic - Claude detects when to use skills
> "Commit these changes"              # Loads git-commit
> "Review this code"                  # Loads java-code-review
> "Is this API design correct?"       # Loads api-contract-review
> "Check for N+1 queries"             # Loads performance-smell-detection

# Manual - invoke with slash command
> /git-commit
> /java-code-review
> /security-audit
> /test-quality
```

## Adding a New Skill

1. Create folder: `.claude/skills/<skill-name>/`
2. Create `SKILL.md` with frontmatter (name, description, metadata)
3. Add entry to this README table
4. Verify no significant overlap with existing skills
