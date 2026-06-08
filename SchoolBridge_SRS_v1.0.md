# Software Requirements Specification

## SchoolBridge

*A WhatsApp-first School–Parent Communication Platform*

| | |
|---|---|
| **Document Version** | 1.0 (Draft) |
| **Status** | Initial Specification – MVP Scope |
| **Target Market** | Private schools and tutoring centers (Egypt / MENA) |
| **Prepared for** | Founding team |
| **Date** | 26 May 2026 |

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Overall Description](#2-overall-description)
3. [System Features (Functional Requirements)](#3-system-features-functional-requirements)
4. [External Interface Requirements](#4-external-interface-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [High-Level Data Model](#6-high-level-data-model)
7. [Future Enhancements](#7-future-enhancements-out-of-scope-for-mvp)
8. [MVP Acceptance Criteria](#8-mvp-acceptance-criteria)
9. [Open Questions](#9-open-questions)
10. [Revision History](#10-revision-history)

---

## 1. Introduction

### 1.1 Purpose

This Software Requirements Specification (SRS) describes the functional and non-functional requirements for **SchoolBridge**, a Software-as-a-Service (SaaS) platform that enables structured, reliable communication between schools (or tutoring centers) and the parents of their students. The document is intended to guide the design, implementation, testing, and acceptance of the Minimum Viable Product (MVP) and to serve as a shared reference for the founding team, developers, designers, and early customer-facing stakeholders.

### 1.2 Document Conventions

- "School" refers to any educational institution using the platform, including K–12 private schools, language schools, and tutoring/learning centers.
- "Parent" refers to any guardian linked to one or more student records.
- "Shall" denotes a mandatory requirement. "Should" denotes a recommended requirement. "May" denotes an optional requirement.
- Functional requirements use the prefix **FR-**. Non-functional requirements use **NFR-**.

### 1.3 Intended Audience

- Product owners and the founding team — for scope and prioritization decisions.
- Engineers and designers — as the source of truth for build requirements.
- QA — to derive test cases and acceptance criteria.
- Early customers — as a transparent description of what the product does (and does not) do in v1.

### 1.4 Product Scope

SchoolBridge replaces the chaotic mix of WhatsApp groups, paper communication books, phone calls, and printed report cards that schools and parents currently rely on. The MVP focuses on five high-value workflows: **announcements, homework, attendance notifications, fee status and reminders, and direct teacher-to-parent messaging**. Parents primarily interact through WhatsApp; schools manage everything through a web-based admin dashboard. Heavier features such as full grade books, timetables, library systems, and transport tracking are explicitly out of scope for v1 and are listed in Section 7 (Future Enhancements).

### 1.5 Business Goals

- Reduce parent-side anxiety and information gaps about their child's school life.
- Eliminate 60%+ of WhatsApp-group and phone-based admin overhead reported by school staff.
- Improve on-time fee collection by at least 15% via structured reminders.
- Achieve product-market fit with 20 paying schools within 12 months of launch.

### 1.6 References

- WhatsApp Business Platform (Cloud API) documentation.
- Paymob and Fawry developer documentation (payment integration, optional v1.1).
- Egyptian Personal Data Protection Law No. 151 of 2020.
- OWASP Application Security Verification Standard (ASVS) v4.

---

## 2. Overall Description

### 2.1 Product Perspective

SchoolBridge is a new, self-contained SaaS product. It is multi-tenant: each school is a tenant with isolated data, users, and configuration. The system is composed of three primary interfaces:

1. A web-based admin dashboard for school staff.
2. A web/mobile-responsive portal for teachers.
3. A WhatsApp-based interface for parents (with an optional lightweight mobile web portal).

A backend API ties these together and integrates with the WhatsApp Business Cloud API and, in later versions, payment gateways.

### 2.2 Product Functions (Summary)

- Tenant onboarding: create school, import classes, students, parents, and teachers from spreadsheets.
- Role-based user management for administrators, teachers, and parents.
- Broadcast announcements scoped to whole-school, grade, class, or custom group.
- Homework posting with optional attachments and due dates.
- Daily attendance entry by teachers and instant absence alerts to parents.
- Fee status tracking with scheduled reminders before and after due date.
- Direct 1-to-1 messaging between a teacher and a parent, gated by school policy.
- Parent-facing WhatsApp bot for receiving messages and replying to confirmations.
- Reporting on message delivery, read rates, attendance rates, and fee collection status.

### 2.3 User Classes and Characteristics

| User Class | Technical Skill | Description and Primary Needs |
|---|---|---|
| Super Admin | High | Internal SchoolBridge staff. Provisions new tenants, manages billing, monitors platform health. |
| School Admin | Medium | Principal, vice-principal, or admin officer. Owns the school tenant. Manages users, classes, fees, and high-impact announcements. |
| Teacher | Low to Medium | Classroom teacher. Posts homework, takes attendance, sends class-level messages, replies to parents. |
| Parent | Low (WhatsApp users) | Receives messages and notifications on WhatsApp. Confirms attendance, views homework, sees fee status. Usability must assume minimal digital literacy. |
| Student | Varies (out of scope for v1) | Not a direct user in MVP. May be added in a later version with safeguards. |

### 2.4 Operating Environment

- Cloud-hosted backend (AWS, GCP, or DigitalOcean in a region close to MENA, e.g. `eu-south-1` / `me-south-1`).
- Admin and teacher dashboards run on modern desktop and mobile browsers (last two major versions of Chrome, Safari, Edge, Firefox).
- Parents access the system via WhatsApp on Android 8+ or iOS 13+ devices.
- Internet connection assumed but flows must tolerate intermittent connectivity (3G level).

### 2.5 Design and Implementation Constraints

- WhatsApp Business Cloud API rules: messages outside the 24-hour customer service window must use pre-approved message templates.
- Arabic and English must be supported in the UI and in message content (right-to-left layout for Arabic).
- All written communication shall be logged for audit, but message bodies containing personal data shall be encrypted at rest.
- Schools cannot send messages to non-enrolled phone numbers (anti-spam guarantee).

### 2.6 Assumptions and Dependencies

- The school provides accurate student–parent–phone mappings during onboarding.
- The school maintains a verified WhatsApp Business Account (WABA) or authorizes SchoolBridge to send via its shared sender pool.
- Payment integration (Fawry, Paymob, Vodafone Cash) is post-MVP unless a launch customer requires it.
- Local SMS fallback is optional in v1; required only if WhatsApp delivery rate falls below a defined threshold.

---

## 3. System Features (Functional Requirements)

### 3.1 Tenant and User Management

This feature covers the onboarding of new schools, the creation of users within a school, and authentication/authorization for all roles.

| ID | Requirement | Description |
|---|---|---|
| FR-1.1 | School onboarding | Super Admin shall be able to create a new school tenant by providing school name, country, primary contact, and subscription tier. |
| FR-1.2 | Bulk import | School Admin shall be able to import classes, students, parents, and teachers from a CSV or Excel template. |
| FR-1.3 | Role assignment | Each user shall be assigned exactly one role per school: School Admin, Teacher, or Parent. |
| FR-1.4 | Multi-child parents | A single parent (identified by phone number) shall be linkable to multiple students, across one or more classes. |
| FR-1.5 | Authentication (staff) | School Admins and Teachers shall log in using email + password with optional Google SSO. Passwords shall meet minimum complexity rules (see NFR-Security). |
| FR-1.6 | Authentication (parents) | Parents shall be identified and authenticated by their registered WhatsApp phone number; no password required for read/receive operations. Sensitive actions (e.g. viewing fee history) shall require a one-time PIN sent via WhatsApp. |
| FR-1.7 | Suspension | School Admin shall be able to suspend a user account, which revokes all access immediately. |

### 3.2 Announcements

Outbound, one-to-many communication from school staff to parents. Replaces broadcast WhatsApp groups.

| ID | Requirement | Description |
|---|---|---|
| FR-2.1 | Compose announcement | School Admin or Teacher shall be able to compose a text announcement, optionally with one image or PDF attachment up to 5 MB. |
| FR-2.2 | Audience selection | Sender shall be able to scope the announcement to: whole school, a grade, a class, or a custom list of parents. |
| FR-2.3 | Scheduling | Announcements shall be sendable immediately or scheduled for a future date and time within the school's timezone. |
| FR-2.4 | Language selection | Sender shall be able to send the same announcement in Arabic, English, or both (one message per language). |
| FR-2.5 | Delivery status | Sender shall see per-recipient delivery status: queued, sent, delivered, read, or failed. |
| FR-2.6 | Required acknowledgment | Sender shall be able to mark an announcement as 'requires acknowledgment'; parents tap a button in WhatsApp to acknowledge. The system shall list parents who have not yet acknowledged. |
| FR-2.7 | Recall | Sender shall be able to recall a scheduled announcement before it is sent. Already-delivered announcements cannot be recalled. |

### 3.3 Homework Management

Teachers post homework once per class; parents (and, indirectly, students) receive it on WhatsApp and in the parent portal.

| ID | Requirement | Description |
|---|---|---|
| FR-3.1 | Post homework | Teacher shall post a homework item with subject, description, optional attachments, and due date. |
| FR-3.2 | Class scoping | Homework shall be visible only to parents of students enrolled in the target class. |
| FR-3.3 | Edit / cancel | Teacher shall be able to edit homework before the due date. Edits shall send a single follow-up notification, not multiple. |
| FR-3.4 | Homework feed | Parent shall see a chronological list of homework items in the parent portal, filtered by child if they have multiple children. |
| FR-3.5 | Reminder | System shall send a WhatsApp reminder to parents the evening before the due date, configurable per school. |

### 3.4 Attendance Tracking and Notifications

Daily attendance is the single highest-value feature for parents: knowing immediately when their child is absent.

| ID | Requirement | Description |
|---|---|---|
| FR-4.1 | Daily roster | Teacher shall see a daily roster for each class assigned to them, pre-populated with all enrolled students. |
| FR-4.2 | Mark attendance | Teacher shall be able to mark each student as Present, Absent, Late, or Excused with one tap. |
| FR-4.3 | Absence alert | Within 5 minutes of an Absent or Late mark being saved, the system shall send a WhatsApp notification to the linked parent(s). |
| FR-4.4 | Parent confirmation | The absence notification shall include quick-reply options: 'My child is sick', 'Authorized absence', 'I will contact the school'. |
| FR-4.5 | Attendance history | Parents and Admin shall see a per-student attendance history with monthly and term-level summaries. |
| FR-4.6 | Bulk operations | Teacher shall be able to mark all students Present in one action and then flip specific students to other statuses. |

### 3.5 Fee Status and Reminders

v1 focuses on tracking and reminders. Actual payment processing is a post-MVP enhancement.

| ID | Requirement | Description |
|---|---|---|
| FR-5.1 | Fee structure | School Admin shall be able to define fee items (e.g. tuition, transport, activities) with amounts, due dates, and applicable classes. |
| FR-5.2 | Per-student ledger | System shall maintain a ledger per student showing each fee item, its amount, due date, paid amount, balance, and last payment date. |
| FR-5.3 | Bulk import payments | Admin shall be able to import recorded payments via CSV (bank statement reconciliation). |
| FR-5.4 | Reminder schedule | System shall send WhatsApp fee reminders on a configurable schedule, e.g. 7 days before, 1 day before, on due date, 7 days overdue. |
| FR-5.5 | Statement | Parent shall be able to request a current statement on WhatsApp by replying with a keyword; the system shall reply with a PDF statement for their child(ren). |
| FR-5.6 | Receipt | When Admin records a payment, the system shall optionally send a WhatsApp confirmation to the parent including reference number and remaining balance. |

### 3.6 Direct Teacher–Parent Messaging

Targeted 1-to-1 communication, fully logged and bounded by school policy to prevent off-platform leakage.

| ID | Requirement | Description |
|---|---|---|
| FR-6.1 | Initiate conversation | A teacher shall be able to start a direct conversation with a parent of a student in their class. |
| FR-6.2 | Parent reply | A parent shall be able to reply to any teacher message via WhatsApp; replies route back to the teacher's dashboard. |
| FR-6.3 | Quiet hours | School Admin shall be able to set quiet hours (e.g. 21:00–07:00) during which the system delays non-urgent messages until the next allowed window. |
| FR-6.4 | Conversation log | All messages shall be logged and retrievable by the School Admin for the legal retention period (see NFR-Compliance). |
| FR-6.5 | Block / report | Parents shall be able to report inappropriate teacher messages directly to the School Admin via a WhatsApp keyword. |

### 3.7 Admin Dashboard and Reporting

| ID | Requirement | Description |
|---|---|---|
| FR-7.1 | Overview dashboard | School Admin shall see a home dashboard with: today's attendance rate, overdue fees count, announcements sent this week, message volume. |
| FR-7.2 | Attendance report | Exportable report of attendance per class, per student, per date range. |
| FR-7.3 | Fee collection report | Report showing collected vs outstanding fees per class and per fee item. |
| FR-7.4 | Message delivery report | Per-announcement breakdown of delivery, read, and acknowledgment counts. |
| FR-7.5 | Audit log | Immutable log of admin actions (user created, fee edited, message sent) with timestamp and actor. |

---

## 4. External Interface Requirements

### 4.1 User Interfaces

- **Admin & Teacher Web Dashboard:** Responsive web application. Primary navigation by section (Classes, Announcements, Attendance, Fees, Reports, Settings). Optimized for desktop but fully usable on mobile browsers because many small-school admins work from phones.
- **Parent WhatsApp Interface:** All communication occurs as WhatsApp chat messages. Action prompts use WhatsApp interactive buttons and list messages where supported.
- **Parent Web Portal (optional, v1.1):** A lightweight, no-login-by-default page accessed via personalized links from WhatsApp. Used for richer views like full fee statements and attendance history.
- **Accessibility:** Dashboards shall meet WCAG 2.1 AA contrast and keyboard navigation requirements.

### 4.2 Software Interfaces

| System | Purpose | Interface / Protocol |
|---|---|---|
| WhatsApp Business Cloud API | Parent communication | HTTPS REST, webhooks for inbound messages. Pre-approved templates for non-session messages. |
| Paymob / Fawry (v1.1) | Fee payment collection | HTTPS REST + webhooks for payment confirmation. |
| SMS gateway (fallback) | Critical alerts when WhatsApp fails | HTTPS REST. Configurable per tenant. |
| Email service (SES / Sendgrid) | Staff account emails, reports | SMTP / HTTPS REST. |
| Object storage (S3-compatible) | Attachments, exports | S3 API, pre-signed URLs for download. |

### 4.3 Communication Interfaces

- All client–server traffic over HTTPS (TLS 1.2 or higher).
- Webhook endpoints for WhatsApp and payment gateways shall validate signatures and reject unsigned traffic.
- Rate limiting applied per tenant and per IP to mitigate abuse.

---

## 5. Non-Functional Requirements

### 5.1 Performance

- **NFR-P1:** Admin dashboard pages shall load (Largest Contentful Paint) in under 2.5 seconds on a 4G connection.
- **NFR-P2:** Attendance absence alerts shall be queued for delivery within 60 seconds of the teacher saving the roster, and delivered to WhatsApp within 5 minutes under normal API conditions.
- **NFR-P3:** The system shall support at least 100,000 students and 50,000 daily active parent recipients on a single tenancy cluster without architectural redesign.
- **NFR-P4:** Bulk announcement to 5,000 recipients shall complete dispatch within 10 minutes.

### 5.2 Reliability and Availability

- **NFR-R1:** Target uptime of 99.5% per calendar month for the dashboard and message dispatcher (scheduled maintenance excluded).
- **NFR-R2:** All outbound messages shall be persisted with at-least-once delivery semantics; deduplication prevents the same announcement from being sent twice to one parent.
- **NFR-R3:** Daily encrypted backups of the database with a recovery point objective (RPO) of 24 hours and a recovery time objective (RTO) of 4 hours.

### 5.3 Security

- **NFR-S1:** Passwords stored using a modern adaptive hash (Argon2id or bcrypt with cost ≥ 12).
- **NFR-S2:** Multi-tenant data isolation enforced at the application layer; every database query shall be scoped by `tenant_id` and verified by automated tests.
- **NFR-S3:** All personal data fields (phone numbers, names, message bodies) encrypted at rest using AES-256 or equivalent.
- **NFR-S4:** Role-based access control enforced server-side; client-side checks are advisory only.
- **NFR-S5:** Rate limits on login (max 5 failed attempts per 15 minutes per account) and on outbound messaging APIs.
- **NFR-S6:** Quarterly review of the OWASP Top 10 against the application; remediation of high-severity findings within 30 days.

### 5.4 Usability

- **NFR-U1:** A first-time School Admin shall be able to complete onboarding (import students, send first announcement) in under 30 minutes with the help of the in-product onboarding wizard.
- **NFR-U2:** Parents shall be able to receive and respond to messages with zero account setup beyond receiving the first WhatsApp message.
- **NFR-U3:** All parent-facing messages shall be readable by an adult with primary-school literacy in the chosen language.

### 5.5 Localization

- Arabic (default for Egypt) and English supported on day one.
- Right-to-left layout fully supported in dashboards when Arabic is selected.
- Dates, times, and currencies localized to the school's configured locale.
- French support targeted for v1.2 to address North African market.

### 5.6 Compliance

- Egyptian Personal Data Protection Law No. 151 of 2020 — explicit consent recorded for parents at onboarding, with the right to request data deletion.
- WhatsApp Business Messaging Policy compliance — no marketing to unconsented numbers, no harvesting, no scraping.
- Data retention: message and attendance data retained for the duration of the school's subscription plus 12 months, after which automatic anonymization.

### 5.7 Scalability

- Stateless application servers behind a load balancer; horizontal scaling by adding instances.
- Outbound message dispatch via a queue (e.g. SQS, RabbitMQ, or Redis streams) consumed by dedicated workers.
- Database read replicas added when read load exceeds 70% of primary capacity.

---

## 6. High-Level Data Model

The following entities form the core domain. Field lists are illustrative, not exhaustive.

| Entity | Key attributes and relationships |
|---|---|
| **School (Tenant)** | `id, name, country, timezone, locale, subscription_tier, created_at`. Owns Users, Classes, Students, etc. |
| **User** | `id, school_id, role (admin/teacher/parent), name, email, phone, password_hash, status, created_at`. |
| **Class** | `id, school_id, name, grade_level, academic_year, homeroom_teacher_id`. |
| **Student** | `id, school_id, class_id, full_name, date_of_birth, external_id` (school's own ID). |
| **Parent–Student link** | `parent_user_id, student_id, relationship (mother/father/guardian), primary_contact (bool)`. |
| **Attendance** | `id, student_id, class_id, date, status (P/A/L/E), marked_by_user_id, marked_at, parent_response`. |
| **Announcement** | `id, school_id, sender_id, scope_type, scope_id, body, attachments, scheduled_for, status`. |
| **Message** | `id, announcement_id (nullable), conversation_id (nullable), to_user_id, body, status, sent_at, delivered_at, read_at`. |
| **FeeItem** | `id, school_id, name, amount, due_date, scope_type (class/grade), scope_id`. |
| **Payment** | `id, student_id, fee_item_id, amount, paid_at, method, reference, recorded_by_user_id`. |

---

## 7. Future Enhancements (Out of Scope for MVP)

- In-app payments via Fawry, Paymob, Vodafone Cash, and credit cards.
- Full digital report cards with grade entry by teachers and parent acknowledgment.
- Timetable and substitute teacher management.
- Student-facing app for older grades, with parental consent controls.
- Bus/transport tracking with live location for parents.
- AI assistant for school admins (draft announcements, summarize parent replies).
- Open API for integration with existing school management systems (SMS, ERP).

---

## 8. MVP Acceptance Criteria

The MVP shall be considered ready for general availability when all of the following are demonstrated end-to-end in a pilot deployment with at least one paying school:

- A school of 500+ students can be onboarded in under 4 hours from data import to first message.
- 100% of absence marks generate a parent WhatsApp notification within 5 minutes.
- Announcements with required acknowledgment reach an acknowledgment rate of at least 70% within 24 hours.
- Fee reminders measurably reduce overdue accounts by at least 15% compared to the school's previous term.
- Zero data leakage incidents (no message sent to wrong parent) across 30 days of pilot operation.

---

## 9. Open Questions

- Should SchoolBridge maintain its own WhatsApp Business Account (WABA) per school, or operate a shared sender? Each model has cost and brand implications.
- Pricing model: per-student-per-month vs flat-rate per school. Per-student aligns incentives with growth but is harder for small schools to forecast.
- Should teachers see fee balances? Some schools want teachers to remind parents in person; others want strict separation.
- Localization scope: do we target Saudi/UAE Arabic variants on day one, or stay focused on Egyptian Arabic?

---

## 10. Revision History

| Version | Date | Author | Changes |
|---|---|---|---|
| 1.0 (Draft) | 26 May 2026 | Founding team | Initial SRS for MVP scope. |
