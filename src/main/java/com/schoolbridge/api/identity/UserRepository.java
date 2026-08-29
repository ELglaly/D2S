package com.schoolbridge.api.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository. All finders execute under the {@code tenantFilter} so school B is
 * invisible when a school A principal is bound, regardless of which finder is called.
 *
 * <p><b>Important:</b> {@link #findById} is overridden with an explicit JPQL query because
 * Hibernate's {@code @Filter} does NOT apply to {@code Session.get()}/{@code EntityManager.find()}
 * (direct primary-key lookups). The default Spring Data {@code findById} uses {@code em.find}, so
 * without this override a teacher in school A could load any user by id. Routing through a query
 * makes the filter apply. Every future {@code TenantEntity} repository MUST do the same â€” the
 * isolation test for {@link User} is the canonical template.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

  @Override
  @Query("select u from User u where u.id = :id")
  Optional<User> findById(@Param("id") UUID id);

  Optional<User> findByEmail(String email);

  Optional<User> findByPhoneHash(String phoneHash);

  /** Cross-school finder used by the unauthenticated parent OTP flow (TenantContext is null). */
  List<User> findAllByPhoneHash(String phoneHash);

  boolean existsByEmail(String email);

  boolean existsByPhoneHash(String phoneHash);
}

