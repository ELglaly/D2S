package com.schoolbridge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies a normal empty-container application startup completes the production schema setup. */
class MigrationSmokeIntegrationTest extends SqlIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void applicationStartup_appliesFinalLiquibaseChangesetAndEnablesPgvector() {
    Integer finalChangesetCount =
        jdbc.queryForObject(
            "select count(*) from databasechangelog where id = '020-authorization-normalization' "
                + "and author = 'schoolbridge'",
            Integer.class);
    Boolean pgvectorInstalled =
        jdbc.queryForObject(
            "select exists (select 1 from pg_extension where extname = 'vector')", Boolean.class);
    String vectorStore =
        jdbc.queryForObject("select to_regclass('public.assistant_vector_store')", String.class);

    assertThat(finalChangesetCount).isEqualTo(1);
    assertThat(pgvectorInstalled).isTrue();
    assertThat(vectorStore).isEqualTo("assistant_vector_store");
  }
}
