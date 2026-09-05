-- Runs after Liquibase. It intentionally leaves databasechangelog, permissions, and
-- role_permissions intact: Liquibase owns those migration/catalog records.
-- The order makes the script resilient if a future migration removes a cascade.
TRUNCATE TABLE
    attachments,
    notification_preferences,
    notification_settings,
    assistant_vector_store,
    assistant_documents,
    assistant_messages,
    assistant_conversations,
    assistant_settings,
    grade_records,
    homework_recipients,
    homework_items,
    attendance_alert_recipients,
    attendance_records,
    announcement_recipients,
    announcements,
    device_tokens,
    teacher_subject_assignments,
    class_subjects,
    subjects,
    parent_student_links,
    enrollments,
    school_classes,
    refresh_tokens,
    users,
    platform_admins,
    audit_logs,
    outbox_events,
    schools
RESTART IDENTITY CASCADE;
