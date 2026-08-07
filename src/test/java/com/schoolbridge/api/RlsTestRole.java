package com.schoolbridge.api;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An unprivileged database role for row-level-security tests, plus the statement that assumes it.
 *
 * <p><b>Why this exists.</b> {@code PostgreSQLContainer} sets {@code POSTGRES_USER}, which makes
 * that role the container's bootstrap <em>superuser</em>, and superusers bypass RLS
 * unconditionally. {@code ALTER TABLE … FORCE ROW LEVEL SECURITY} does not help: FORCE only removes
 * the table <em>owner's</em> exemption. So an RLS assertion made on the default test connection
 * asserts nothing — it passes with the policy dropped.
 *
 * <p>This role owns no tables and is {@code NOBYPASSRLS}, which is the closest thing in a test to
 * the production application role described in docs/RUNBOOK.md. Assume it with {@link #ASSUME}
 * <em>inside</em> a transaction: {@code SET LOCAL} reverts on commit or rollback, so no other test
 * can inherit it and there is no teardown statement that a failure can skip.
 */
public final class RlsTestRole {

  public static final String NAME = "rls_test_app_role";

  /** Must run inside an active transaction to be transaction-scoped. */
  public static final String ASSUME = "SET LOCAL ROLE " + NAME;

  private RlsTestRole() {}

  /**
   * Creates the role if a previous test class in this JVM has not already done so, and re-grants
   * table privileges. The grant is repeated on every call because {@code GRANT … ON ALL TABLES} is
   * a snapshot of the tables that exist at that moment, not a standing rule.
   */
  public static void ensureExists(JdbcTemplate jdbc) {
    jdbc.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + NAME
            + "') THEN CREATE ROLE "
            + NAME
            + " NOLOGIN NOBYPASSRLS; END IF; END $$");
    jdbc.execute("GRANT USAGE ON SCHEMA public TO " + NAME);
    jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + NAME);
  }
}
