package com.schoolbridge.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import jakarta.persistence.Entity;

/**
 * Build-time guard: every domain {@code @Entity} carrying a {@code school_id} column must extend
 * {@link TenantEntity} so the Hibernate tenant filter applies to every read. Infrastructure
 * entities in {@code com.schoolbridge.api.common} (the outbox relay scans across tenants; audit
 * logs are queried with explicit school scoping) are exempt.
 */
@AnalyzeClasses(packages = "com.schoolbridge.api")
class TenantEntityArchUnitTest {

  @ArchTest
  static final com.tngtech.archunit.lang.ArchRule
      domain_entities_with_school_id_extend_tenant_entity =
          classes()
              .that()
              .areAnnotatedWith(Entity.class)
              .and()
              .resideOutsideOfPackages("..common..")
              .and(carry_school_id_column())
              .should()
              .beAssignableTo(TenantEntity.class)
              .because(
                  "any domain entity with a school_id column must extend TenantEntity so the"
                      + " tenant filter applies to every read");

  private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> carry_school_id_column() {
    return new com.tngtech.archunit.base.DescribedPredicate<>("declare a school_id column") {
      @Override
      public boolean test(JavaClass clazz) {
        return clazz.getAllFields().stream()
            .anyMatch(
                f ->
                    f.tryGetAnnotationOfType(jakarta.persistence.Column.class)
                        .map(c -> "school_id".equals(c.name()))
                        .orElse(false));
      }
    };
  }
}
