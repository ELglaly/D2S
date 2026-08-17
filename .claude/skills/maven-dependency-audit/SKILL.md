---
name: maven-dependency-audit
description: Maven dependency audit for SchoolBridge. Checks for outdated deps, vulnerabilities, and unused dependencies using mvn commands and OWASP plugin.
metadata:
  version: "2.0.0"
  domain: build
  triggers: maven, dependencies, outdated, vulnerability, pom.xml, dependency audit
  role: auditor
  scope: build
  output-format: checklist
---

# Maven Dependency Audit Skill (SchoolBridge)

## Current Key Versions

| Dependency | Version in pom.xml | Check For Update |
|-----------|---------|-----------------|
| Spring Boot | 3.4.5 | Check spring.io/spring-boot |
| Spring AI | 1.0.1 | Check spring.io/spring-ai |
| JJWT (`jjwt-api`/`jjwt-impl`/`jjwt-jackson`) | 0.12.6 | Check github.com/jwtk/jjwt |
| springdoc-openapi | 2.7.0 | Check springdoc.org |
| Resilience4j | 2.2.0 | Check github.com/resilience4j/resilience4j |
| AWS SDK v2 (S3-compatible storage) | 2.31.78 | Check github.com/aws/aws-sdk-java-v2 |
| Anthropic SDK | 2.35.0 | Check github.com/anthropics/anthropic-sdk-java |
| Google GenAI SDK | 1.57.0 | Check github.com/googleapis/java-genai |
| Firebase Admin (FCM push) | 9.3.0 | Check github.com/firebase/firebase-admin-java |
| Logstash Logback Encoder | 8.0 | Check github.com/logfellow/logstash-logback-encoder |
| WireMock (test) | 3.9.2 | Check github.com/wiremock/wiremock |
| ModelMapper | 3.2.0 | Check github.com/modelmapper/modelmapper |
| Spotless | 2.43.0 | Check github.com/diffplug/spotless |
| SpotBugs plugin | 4.8.6.4 | Check github.com/spotbugs/spotbugs-maven-plugin |
| Liquibase | (managed by Spring Boot BOM) | Check liquibase.org |

## Audit Commands (Windows)

```bash
# Check for outdated dependencies
mvnw.cmd versions:display-dependency-updates

# Check for outdated plugins
mvnw.cmd versions:display-plugin-updates

# Dependency tree (find transitive deps causing issues)
mvnw.cmd dependency:tree

# Check for unused/undeclared dependencies
mvnw.cmd dependency:analyze

# OWASP vulnerability check (add plugin if not present)
mvnw.cmd org.owasp:dependency-check-maven:check
```

## Checklist

### Outdated Dependencies
- [ ] Run `versions:display-dependency-updates` — review MINOR and MAJOR updates
- [ ] JJWT: security-sensitive, update promptly for CVE fixes
- [ ] AWS SDK / Spring AI / Anthropic SDK / Google GenAI move fast — check for breaking changes in
      release notes before bumping, not just the version number

### Security Vulnerabilities
- [ ] Run OWASP dependency-check — fix any CRITICAL or HIGH CVEs
- [ ] No known vulnerable versions of Spring Security in transitive deps
- [ ] No `commons-fileupload` < 1.5 (CVE-2023-24998) — relevant given the attachment upload pipeline

### Unused Dependencies
- [ ] Run `dependency:analyze` — remove "Unused declared dependencies"
- [ ] Check if any test-scope deps (WireMock, Testcontainers) accidentally ended up in compile scope

### Conflict Resolution
```bash
# Find which dependency brings in a conflicting version
mvnw.cmd dependency:tree -Dincludes=groupId:artifactId
```

### pom.xml Best Practices
- [ ] Version numbers in `<properties>` block, not inline
- [ ] No `SNAPSHOT` versions in production builds (current project version is
      `0.1.0-SNAPSHOT` — expected pre-release, revisit at cut time)
- [ ] Test dependencies have `<scope>test</scope>`
- [ ] No duplicate dependency declarations

## Critical Deps for SchoolBridge Security

These must stay up to date — any vulnerability is exploitable:
1. `spring-boot-starter-security` — auth/authz
2. `jjwt-api`, `jjwt-impl`, `jjwt-jackson` — JWT signing
3. `postgresql` JDBC driver — database access
4. AWS SDK S3 client — presigned URL generation for attachments
5. Whatever HTTP client backs the WhatsApp adapter — handles inbound webhook payloads from the
   public internet
