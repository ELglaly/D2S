--liquibase formatted sql

--changeset schoolbridge:018-attachments-table
--comment: Attachment metadata for the presigned upload/download pipeline (P0 item 10). The bytes live in S3-compatible object storage and never transit this API; this table is the record of what exists, who uploaded it, and whether it is safe to hand back. storage_key is generated server-side as {school_id}/{yyyy}/{MM}/{id} -- a client-influenced key would be a cross-tenant write primitive and would make the per-tenant prefix decorative, so it is derived, never accepted. declared_content_type is what the client claimed; content_type is what the stored bytes actually sniffed as, and only the latter is trusted. declared_size_bytes is signed into the presigned PUT as Content-Length so the object store itself rejects an oversized body (a presigned PUT cannot carry a content-length-range condition -- that is a presigned POST feature); size_bytes is the real length re-read from HeadObject at completion. Both FKs cascade per the FK-cascade rule: existing integration tests tear down with userRepository.deleteAll() and would break on a new child table otherwise.
CREATE TABLE attachments (
    id                    UUID          PRIMARY KEY,
    school_id             UUID          NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    uploader_user_id      UUID          NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    storage_key           VARCHAR(512)  NOT NULL,
    file_name             VARCHAR(255)  NOT NULL,
    declared_content_type VARCHAR(128)  NOT NULL,
    content_type          VARCHAR(128),
    declared_size_bytes   BIGINT        NOT NULL,
    size_bytes            BIGINT,
    status                VARCHAR(20)   NOT NULL,
    rejection_reason      VARCHAR(255),
    av_result             VARCHAR(20),
    av_signature          VARCHAR(255),
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);
CREATE UNIQUE INDEX uk_attachments_storage_key ON attachments (storage_key);
CREATE INDEX idx_attachments_school_created ON attachments (school_id, created_at DESC);
CREATE INDEX idx_attachments_uploader ON attachments (school_id, uploader_user_id, created_at DESC);
-- The sweeper's two queries. Partial on status so the abandoned-upload scan does not walk every
-- CLEAN row in the table on every run.
CREATE INDEX idx_attachments_incomplete ON attachments (created_at)
    WHERE status IN ('PENDING', 'UPLOADED');
CREATE INDEX idx_attachments_retention ON attachments (created_at)
    WHERE status = 'CLEAN';
--rollback DROP TABLE attachments;

--changeset schoolbridge:018-attachments-rls
--comment: Row-Level Security on attachments, in the shape changelog 017 established for every other tenant-owned table. Same reasoning applies with more force here: an attachment is a photo of a child, and the download path mints a bearer URL, so a missed application-side filter would leak an object that stays readable for the life of the presigned URL. nullif() is required, not decoration -- current_setting(k, true) yields '' rather than NULL once set_config has run on the connection, and ''::uuid raises, so without it the second unbound query on a pooled connection 500s instead of returning nothing. NOT FORCED, exactly as 014 and 017: the owner bypasses (local dev, Testcontainers) while the policy activates for the least-privilege application role -- see docs/RUNBOOK.md and RlsStartupValidator.
ALTER TABLE attachments ENABLE ROW LEVEL SECURITY;
CREATE POLICY attachments_tenant_isolation ON attachments
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');
--rollback DROP POLICY attachments_tenant_isolation ON attachments; ALTER TABLE attachments DISABLE ROW LEVEL SECURITY;

--changeset schoolbridge:018-attachment-permissions
--comment: Permission catalog entries for the attachment endpoints. Separate from the 015 seed because that changeset is immutable (forward-only migrations).
INSERT INTO permissions (id, name) VALUES
  (gen_random_uuid(), 'ATTACHMENT_UPLOAD'),
  (gen_random_uuid(), 'ATTACHMENT_READ'),
  (gen_random_uuid(), 'ATTACHMENT_DELETE');
--rollback DELETE FROM permissions WHERE name IN ('ATTACHMENT_UPLOAD', 'ATTACHMENT_READ', 'ATTACHMENT_DELETE');

--changeset schoolbridge:018-grant-attachment-permissions
--comment: Staff upload and read; only SUPER_ADMIN and SCHOOL_ADMIN delete, because deleting an attachment breaks the homework or announcement referencing it. PARENT gets ATTACHMENT_READ only -- a parent must be able to open the photo on their child's homework. The coarse grant is not the whole control: the download endpoint additionally checks, per row, that the caller is the uploader, an admin, or a parent addressed by the item the attachment is attached to.
INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), r.role, p.id
  FROM permissions p
  CROSS JOIN (VALUES ('SUPER_ADMIN'), ('SCHOOL_ADMIN'), ('TEACHER')) AS r(role)
  WHERE p.name IN ('ATTACHMENT_UPLOAD', 'ATTACHMENT_READ');

INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), r.role, p.id
  FROM permissions p
  CROSS JOIN (VALUES ('SUPER_ADMIN'), ('SCHOOL_ADMIN')) AS r(role)
  WHERE p.name = 'ATTACHMENT_DELETE';

INSERT INTO role_permissions (id, role, permission_id)
  SELECT gen_random_uuid(), 'PARENT', p.id FROM permissions p
  WHERE p.name = 'ATTACHMENT_READ';
--rollback DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE name IN ('ATTACHMENT_UPLOAD', 'ATTACHMENT_READ', 'ATTACHMENT_DELETE'));
