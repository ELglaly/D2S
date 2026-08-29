package com.schoolbridge.api.common.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the current tenant to PostgreSQL as a transaction-local GUC so the Row-Level Security
 * policies from changelog 017 (and 014, for the vector store) can evaluate it.
 *
 * <p>Both statements pass {@code is_local = true}, which scopes the setting to the surrounding
 * transaction and resets it on commit or rollback. That is the property that makes this safe behind
 * a connection pool â€” a session-scoped {@code SET} would leave one request's tenant bound on the
 * connection for whoever borrows it next, which is the exact leak RLS is meant to prevent.
 *
 * <p>The {@link JdbcTemplate} participates in the surrounding Spring transaction, so the GUC lands
 * on the same connection Hibernate is about to use.
 */
@Component
public class TenantSessionBinder {

  private static final String BIND_TENANT_SQL = "select set_config('app.current_tenant', ?, true)";
  private static final String SET_BYPASS_SQL = "select set_config('app.tenant_bypass', ?, true)";

  private final JdbcTemplate jdbcTemplate;

  public TenantSessionBinder(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Binds {@code schoolId} for the remainder of the current transaction. Called from {@link
   * TenantFilterAspect} on the same once-per-transaction guard that enables the Hibernate {@code
   * tenantFilter}, so the two controls can never disagree about which tenant is in scope.
   */
  public void bindTenant(UUID schoolId) {
    jdbcTemplate.queryForObject(BIND_TENANT_SQL, String.class, schoolId.toString());
  }

  /**
   * Runs {@code action} with the RLS tenant predicate suspended, then restores it.
   *
   * <p>This exists for exactly two reads, both on {@code users}, both of which resolve <em>who the
   * caller is</em> and therefore cannot know a tenant yet: staff login by email, and the parent OTP
   * flow by phone hash. Both run with {@link TenantContext} empty, so neither the Hibernate filter
   * nor the tenant GUC is bound â€” and an unbound GUC fails closed, which would mean every login
   * resolves to zero rows.
   *
   * <p>Note what does <em>not</em> need this: any path that knows its school can simply bind it
   * with {@link TenantContext#runAs}, which satisfies the filter and the policy together. That is
   * the correct answer nearly everywhere; reach for a bypass only when there is genuinely no tenant
   * to bind.
   *
   * <p>The scope is deliberately narrow â€” reset happens in a {@code finally} so the rest of the
   * transaction (including any INSERT) is still governed by {@code WITH CHECK}. Keeping every use
   * behind this one method is what makes the exemptions greppable and reviewable.
   *
   * <p>This is not a hole in the control. RLS here defends against an <em>application</em> bug â€” a
   * missed filter, a native query, a {@code findById} that skipped its JPQL override â€” and a bug
   * does not call this method. Anyone able to set the GUC arbitrarily already has arbitrary SQL
   * execution, at which point RLS was never the thing standing in their way.
   */
  public <T> T withBypass(Supplier<T> action) {
    setBypass("on");
    try {
      return action.get();
    } finally {
      setBypass("off");
    }
  }

  private void setBypass(String value) {
    jdbcTemplate.queryForObject(SET_BYPASS_SQL, String.class, value);
  }
}

