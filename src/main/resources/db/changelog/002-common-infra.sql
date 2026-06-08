--liquibase formatted sql

--changeset schoolbridge:002-outbox-events
--comment: Transactional outbox for exactly-once-from-DB external messaging.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    school_id       UUID NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    last_error      VARCHAR(2000)
);
CREATE INDEX idx_outbox_pending ON outbox_events (status, created_at) WHERE status = 'PENDING';
--rollback DROP TABLE outbox_events;

--changeset schoolbridge:002-audit-logs
--comment: Append-only audit log of high-impact actions.
CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY,
    school_id      UUID NOT NULL,
    actor_user_id  UUID,
    action         VARCHAR(100) NOT NULL,
    entity_type    VARCHAR(100),
    entity_id      UUID,
    metadata       JSONB,
    ip_address     VARCHAR(45),
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_school_created ON audit_logs (school_id, created_at DESC);
--rollback DROP TABLE audit_logs;
