package com.schoolbridge.api.assistant.rag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link KnowledgeDocument}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL because Hibernate's {@code @Filter} does
 * NOT apply to {@code EntityManager.find()} (direct primary-key lookups). The list/lookup queries
 * below also constrain {@code schoolId} explicitly so they are correct regardless of whether the
 * tenant filter is active on the current session.
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {

  @Override
  @Query("select d from KnowledgeDocument d where d.id = :id")
  Optional<KnowledgeDocument> findById(@Param("id") UUID id);

  @Query("select d from KnowledgeDocument d where d.schoolId = :schoolId order by d.createdAt desc")
  List<KnowledgeDocument> findBySchool(@Param("schoolId") UUID schoolId);

  @Query(
      "select d from KnowledgeDocument d where d.schoolId = :schoolId and d.checksum = :checksum")
  Optional<KnowledgeDocument> findBySchoolAndChecksum(
      @Param("schoolId") UUID schoolId, @Param("checksum") String checksum);
}

