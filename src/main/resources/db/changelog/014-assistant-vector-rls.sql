--liquibase formatted sql

--changeset schoolbridge:014-assistant-vector-rls
--comment: Defense-in-depth tenant isolation on the RAG vector store via Row-Level Security. The application already filters every similarity search by metadata.school_id (RagRetriever); this policy enforces the same at the database so a missed or buggy filter can never leak another tenant's chunks. The tenant is supplied per transaction via set_config('app.current_tenant', <schoolId>, true), which RagRetriever and DocumentIngestionService set before touching the table. NOT forced, so the table owner / superuser (local dev + Testcontainers) bypasses the policy while it activates for the least-privilege application role used in production. current_setting(..., true) returns NULL when unset, so the predicate fails closed (no rows).
ALTER TABLE assistant_vector_store ENABLE ROW LEVEL SECURITY;
CREATE POLICY assistant_vs_tenant_isolation ON assistant_vector_store
    USING ((metadata ->> 'school_id') = current_setting('app.current_tenant', true))
    WITH CHECK ((metadata ->> 'school_id') = current_setting('app.current_tenant', true));
--rollback DROP POLICY assistant_vs_tenant_isolation ON assistant_vector_store;
--rollback ALTER TABLE assistant_vector_store DISABLE ROW LEVEL SECURITY;
