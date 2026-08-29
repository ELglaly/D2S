package com.schoolbridge.api.common.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Activates the Hibernate {@code tenantFilter} on every Spring Data repository call when a tenant
 * is bound. Runs inside whichever transaction Spring opens for the repo method (Spring Boot's
 * default tx interceptor wraps repository calls before AOP aspects at LOWEST precedence), so {@code
 * em.unwrap(Session.class)} resolves to the session about to be used.
 *
 * <p>When {@link TenantContext} is empty (admin maintenance code, the unauthenticated parent OTP
 * lookup, infrastructure repositories like {@code OutboxRepository}/{@code AuditLogRepository}),
 * the filter stays disabled â€” those repos query without tenant scoping, which is what we want.
 *
 * <p>The same branch also publishes the tenant to PostgreSQL via {@link TenantSessionBinder} for
 * the changelog-017 RLS policies. Both controls hang off one condition on purpose: if the Hibernate
 * filter and the database policy could ever disagree about which tenant is bound, the weaker of the
 * two would silently win.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantFilterAspect {

  @PersistenceContext private EntityManager entityManager;

  private final TenantSessionBinder sessionBinder;

  public TenantFilterAspect(TenantSessionBinder sessionBinder) {
    this.sessionBinder = sessionBinder;
  }

  // Target the Spring Data root interface so we catch methods declared in SimpleJpaRepository
  // (findById, findAll, save, â€¦) â€” they're not in our package and a pointcut on
  // `com.schoolbridge.api..*Repository+` would silently miss every inherited finder.
  @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
  public Object enableTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
    UUID tenant = TenantContext.get().orElse(null);
    if (tenant != null && TransactionSynchronizationManager.isActualTransactionActive()) {
      Session session = entityManager.unwrap(Session.class);
      if (session.getEnabledFilter("tenantFilter") == null) {
        session.enableFilter("tenantFilter").setParameter("schoolId", tenant);
        // Guarded by the same check, so this costs one round trip per transaction rather than one
        // per repository call.
        sessionBinder.bindTenant(tenant);
      }
    }
    return pjp.proceed();
  }
}

