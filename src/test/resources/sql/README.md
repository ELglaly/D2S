# SQL fixtures

Liquibase creates the test schema. These scripts import only test data after that migration has
completed; they must never create, alter, or drop production tables.

Use `@Sql` in this order for an endpoint/E2E test:

1. `classpath:sql/cleanup/all-data.sql` before the test;
2. the `SqlIntegrationTest` common imports (`fixtures/common/schools.sql`, `principals.sql`, and
   `role-permissions.sql`) plus `fixtures/common/academic-structure.sql` or other module-specific
   fixtures when a scenario needs classes or students;
3. `classpath:sql/cleanup/all-data.sql` after the test.

Fixture UUIDs are fixed and grouped by module. Every fixture must include its tenant and satisfy the
actual PostgreSQL foreign keys, unique indexes, and RLS policies. SQL fixture values for encrypted
columns must be generated using the application's configured test encryption keys; plain values are
not permitted for encrypted user, parent, or message fields.

`SqlIntegrationTest` applies its imports with `@SqlConfig(transactionMode = ISOLATED)`. New HTTP/E2E
tests extend it, authenticate through its real `/api/v1/auth/login` helper, and use Java only for
runtime tokens, request payloads, uploads, timestamps, and response IDs.
