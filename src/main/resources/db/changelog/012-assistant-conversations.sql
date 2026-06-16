--liquibase formatted sql

--changeset schoolbridge:012-assistant-conversations
--comment: Durable multi-turn assistant conversations owned by a single user within a tenant.
CREATE TABLE assistant_conversations (
    id              UUID         PRIMARY KEY,
    school_id       UUID         NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    owner_user_id   UUID         NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    title           VARCHAR(200),
    last_message_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_assistant_conversations_owner
    ON assistant_conversations (school_id, owner_user_id, last_message_at DESC, created_at DESC);
--rollback DROP TABLE assistant_conversations;

--changeset schoolbridge:012-assistant-messages
--comment: One turn in a conversation. Assistant rows are written only after a stream completes; pending_action_token links an assistant turn awaiting a CONFIRM/CANCEL reply to its Redis-held action.
CREATE TABLE assistant_messages (
    id                   UUID         PRIMARY KEY,
    school_id            UUID         NOT NULL REFERENCES schools(id)                  ON DELETE CASCADE,
    conversation_id      UUID         NOT NULL REFERENCES assistant_conversations(id)  ON DELETE CASCADE,
    role                 VARCHAR(20)  NOT NULL,
    content              TEXT         NOT NULL,
    pending_action_token VARCHAR(255),
    input_tokens         BIGINT,
    output_tokens        BIGINT,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_assistant_messages_conversation
    ON assistant_messages (conversation_id, created_at);
--rollback DROP TABLE assistant_messages;

--changeset schoolbridge:012-assistant-settings
--comment: Per-tenant editable assistant configuration. system_prompt is the editable persona; the immutable security guardrails are always appended server-side. One row per school.
CREATE TABLE assistant_settings (
    id            UUID         PRIMARY KEY,
    school_id     UUID         NOT NULL REFERENCES schools(id) ON DELETE CASCADE,
    system_prompt TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX uk_assistant_settings_school
    ON assistant_settings (school_id);
--rollback DROP TABLE assistant_settings;
