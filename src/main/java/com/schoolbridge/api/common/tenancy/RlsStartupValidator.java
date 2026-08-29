package com.schoolbridge.api.common.tenancy;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot the production profile when Row-Level Security is not actually in force for the
 * connecting database role.
 *
 * <p>The changelog-017 policies are deliberately not {@code FORCE}d, which means PostgreSQL lets
 * the table owner bypass them entirely. That is what keeps local development and the Testcontainers
 * suite working unchanged â€” but it also means a production deployment that connects as the owner
 * gets no database-level tenant isolation at all, and gets it <em>silently</em>. Nothing would
 * fail; the policies would simply never evaluate. This check turns that silent misconfiguration
 * into a loud startup failure.
 *
 * <p>{@code row_security_active} is the right question to ask because it answers it for the current
 * user directly, rather than inferring the answer from role membership or ownership tables.
 */
@Component
@Profile("prod")
public class RlsStartupValidator {

  private static final Logger log = LoggerFactory.getLogger(RlsStartupValidator.class);

  /** Representative tenant table â€” if RLS is bypassed here it is bypassed on all twenty. */
  private static final String PROBE_TABLE = "students";

  private final JdbcTemplate jdbcTemplate;

  public RlsStartupValidator(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @PostConstruct
  void validate() {
    Boolean active =
        jdbcTemplate.queryForObject("select row_security_active(?)", Boolean.class, PROBE_TABLE);
    if (!Boolean.TRUE.equals(active)) {
      throw new IllegalStateException(
          "Row-Level Security is not active on "
              + PROBE_TABLE
              + " for the current database role. The application is connecting as the table owner"
              + " (or a superuser), which bypasses every tenant-isolation policy from changelog 017."
              + " Connect as the least-privilege application role and run migrations separately as"
              + " the owner via spring.liquibase.user â€” see docs/RUNBOOK.md, 'Database roles'.");
    }
    log.info("rls_active table={} role_bypass=false", PROBE_TABLE);
  }
}

