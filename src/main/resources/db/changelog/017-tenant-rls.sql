--liquibase formatted sql

--changeset schoolbridge:017-tenant-rls
--comment: Enforce tenant isolation in PostgreSQL. nullif() makes an unbound pooled connection fail closed; production must use a non-owner role because these policies are not forced.
ALTER TABLE announcement_recipients ENABLE ROW LEVEL SECURITY;
CREATE POLICY announcement_recipients_tenant_isolation ON announcement_recipients
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE announcements ENABLE ROW LEVEL SECURITY;
CREATE POLICY announcements_tenant_isolation ON announcements
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE assistant_conversations ENABLE ROW LEVEL SECURITY;
CREATE POLICY assistant_conversations_tenant_isolation ON assistant_conversations
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE assistant_documents ENABLE ROW LEVEL SECURITY;
CREATE POLICY assistant_documents_tenant_isolation ON assistant_documents
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE assistant_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY assistant_messages_tenant_isolation ON assistant_messages
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE assistant_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY assistant_settings_tenant_isolation ON assistant_settings
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE attendance_alert_recipients ENABLE ROW LEVEL SECURITY;
CREATE POLICY attendance_alert_recipients_tenant_isolation ON attendance_alert_recipients
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE attendance_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY attendance_records_tenant_isolation ON attendance_records
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE class_subjects ENABLE ROW LEVEL SECURITY;
CREATE POLICY class_subjects_tenant_isolation ON class_subjects
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE device_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY device_tokens_tenant_isolation ON device_tokens
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE enrollments ENABLE ROW LEVEL SECURITY;
CREATE POLICY enrollments_tenant_isolation ON enrollments
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE grade_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY grade_records_tenant_isolation ON grade_records
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE homework_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY homework_items_tenant_isolation ON homework_items
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE homework_recipients ENABLE ROW LEVEL SECURITY;
CREATE POLICY homework_recipients_tenant_isolation ON homework_recipients
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE parent_student_links ENABLE ROW LEVEL SECURITY;
CREATE POLICY parent_student_links_tenant_isolation ON parent_student_links
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE school_classes ENABLE ROW LEVEL SECURITY;
CREATE POLICY school_classes_tenant_isolation ON school_classes
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE students ENABLE ROW LEVEL SECURITY;
CREATE POLICY students_tenant_isolation ON students
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE subjects ENABLE ROW LEVEL SECURITY;
CREATE POLICY subjects_tenant_isolation ON subjects
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE teacher_subject_assignments ENABLE ROW LEVEL SECURITY;
CREATE POLICY teacher_subject_assignments_tenant_isolation ON teacher_subject_assignments
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY users_tenant_isolation ON users
    USING (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
           OR current_setting('app.tenant_bypass', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_tenant', true), '')::uuid
                OR current_setting('app.tenant_bypass', true) = 'on');
--rollback DROP POLICY announcement_recipients_tenant_isolation ON announcement_recipients; ALTER TABLE announcement_recipients DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY announcements_tenant_isolation ON announcements; ALTER TABLE announcements DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY assistant_conversations_tenant_isolation ON assistant_conversations; ALTER TABLE assistant_conversations DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY assistant_documents_tenant_isolation ON assistant_documents; ALTER TABLE assistant_documents DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY assistant_messages_tenant_isolation ON assistant_messages; ALTER TABLE assistant_messages DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY assistant_settings_tenant_isolation ON assistant_settings; ALTER TABLE assistant_settings DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY attendance_alert_recipients_tenant_isolation ON attendance_alert_recipients; ALTER TABLE attendance_alert_recipients DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY attendance_records_tenant_isolation ON attendance_records; ALTER TABLE attendance_records DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY class_subjects_tenant_isolation ON class_subjects; ALTER TABLE class_subjects DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY device_tokens_tenant_isolation ON device_tokens; ALTER TABLE device_tokens DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY enrollments_tenant_isolation ON enrollments; ALTER TABLE enrollments DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY grade_records_tenant_isolation ON grade_records; ALTER TABLE grade_records DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY homework_items_tenant_isolation ON homework_items; ALTER TABLE homework_items DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY homework_recipients_tenant_isolation ON homework_recipients; ALTER TABLE homework_recipients DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY parent_student_links_tenant_isolation ON parent_student_links; ALTER TABLE parent_student_links DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY school_classes_tenant_isolation ON school_classes; ALTER TABLE school_classes DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY students_tenant_isolation ON students; ALTER TABLE students DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY subjects_tenant_isolation ON subjects; ALTER TABLE subjects DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY teacher_subject_assignments_tenant_isolation ON teacher_subject_assignments; ALTER TABLE teacher_subject_assignments DISABLE ROW LEVEL SECURITY;
--rollback DROP POLICY users_tenant_isolation ON users; ALTER TABLE users DISABLE ROW LEVEL SECURITY;
