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
 * the filter stays disabled — those repos query without tenant scoping, which is what we want.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantFilterAspect {

  @PersistenceContext private EntityManager entityManager;

  // Target the Spring Data root interface so we catch methods declared in SimpleJpaRepository
  // (findById, findAll, save, …) — they're not in our package and a pointcut on
  // `com.schoolbridge.api..*Repository+` would silently miss every inherited finder.
  @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
  public Object enableTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
    UUID tenant = TenantContext.get().orElse(null);
    if (tenant != null && TransactionSynchronizationManager.isActualTransactionActive()) {
      Session session = entityManager.unwrap(Session.class);
      if (session.getEnabledFilter("tenantFilter") == null) {
        session.enableFilter("tenantFilter").setParameter("schoolId", tenant);
      }
    }
    return pjp.proceed();
  }
}
