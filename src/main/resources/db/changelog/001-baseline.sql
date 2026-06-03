--liquibase formatted sql

--changeset schoolbridge:001-baseline-extensions
--comment: Baseline extensions. pgcrypto provides gen_random_uuid() and crypto primitives.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
--rollback DROP EXTENSION IF EXISTS pgcrypto;
