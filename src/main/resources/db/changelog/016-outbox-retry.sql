--liquibase formatted sql

--changeset schoolbridge:016-outbox-next-attempt-at
--comment: Backoff schedule for the outbox relay. Before this, a single broker blip marked an event FAILED terminally and the announcement or attendance alert it carried was silently dropped. Existing PENDING rows get now() so they are picked up on the next poll; PUBLISHED/FAILED rows are left NULL and ignored by the claim query.
ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMPTZ;
UPDATE outbox_events SET next_attempt_at = now() WHERE status = 'PENDING';
--rollback ALTER TABLE outbox_events DROP COLUMN next_attempt_at;

--changeset schoolbridge:016-outbox-retry-index
--comment: Replaces idx_outbox_pending. The relay now claims rows by (status, next_attempt_at) with FOR UPDATE SKIP LOCKED, so the old (status, created_at) partial index no longer matches the access path. Covers PENDING and FAILED because FAILED rows are retryable until they reach the attempt ceiling.
DROP INDEX IF EXISTS idx_outbox_pending;
CREATE INDEX idx_outbox_claimable ON outbox_events (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');
--rollback DROP INDEX idx_outbox_claimable; CREATE INDEX idx_outbox_pending ON outbox_events (status, created_at) WHERE status = 'PENDING';

--changeset schoolbridge:016-outbox-school-index
--comment: Per-tenant operational queries ("what is stuck for this school") did a sequential scan.
CREATE INDEX idx_outbox_school ON outbox_events (school_id, created_at DESC);
--rollback DROP INDEX idx_outbox_school;
