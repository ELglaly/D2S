-- Assistant fixture ownership: School One owns the seeded assistant state; School Two owns a
-- separate conversation for cross-tenant assertions.
INSERT INTO assistant_conversations
    (id, school_id, owner_user_id, title, last_message_at, created_at, updated_at)
VALUES
    ('60000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000010', 'Fixture assistant thread', '2025-01-03T00:00:00Z',
     '2025-01-03T00:00:00Z', '2025-01-03T00:00:00Z'),
    ('60000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
     '20000000-0000-0000-0000-000000000012', 'Other tenant thread', NULL, '2025-01-03T00:00:00Z',
     '2025-01-03T00:00:00Z');

INSERT INTO assistant_messages
    (id, school_id, conversation_id, role, content, pending_action_token, input_tokens,
     output_tokens, created_at, updated_at)
VALUES
    ('61000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     '60000000-0000-0000-0000-000000000001', 'USER', 'What classes do I teach?', NULL, 4, 8,
     '2025-01-03T00:01:00Z', '2025-01-03T00:01:00Z'),
    ('61000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
     '60000000-0000-0000-0000-000000000001', 'ASSISTANT', 'You teach one class.', NULL, 10, 5,
     '2025-01-03T00:02:00Z', '2025-01-03T00:02:00Z');

INSERT INTO assistant_settings
    (id, school_id, system_prompt, created_at, updated_at)
VALUES
    ('62000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'Be concise and helpful to school staff.', '2025-01-03T00:00:00Z', '2025-01-03T00:00:00Z');

INSERT INTO assistant_documents
    (id, school_id, type, title, lang, checksum, status, chunk_count, created_at, updated_at)
VALUES
    ('63000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'GUIDE',
     'Fixture handbook', 'en',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'INDEXED', 1,
     '2025-01-03T00:00:00Z', '2025-01-03T00:00:00Z');
