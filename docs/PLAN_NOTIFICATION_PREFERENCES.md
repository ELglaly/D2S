# Notification Preferences + Per-User Quiet Hours + Push as a Channel

P0 item 11 from [`PLATFORM_REVIEW.md`](PLATFORM_REVIEW.md) §2.3 and §14. Changelog
`019-notification-preferences.sql`.

---

## 1. Why

Two separate gaps, one feature.

**Quiet hours were school-wide only.** `schools.alerts_respect_quiet_hours` is a
single boolean per school. A parent could not opt out of homework reminders,
could not choose a channel, and could not say "not after 21:00". §2.3 ranks a
22:00 message to a parent who never asked for it as the top uninstall driver and,
in several jurisdictions, a consent requirement.

**Push was not a channel.** `PushNotificationClient` had no production caller —
only `FcmPushClient`, `LoggingPushClient`, and a test fake referenced it.
`device_tokens` rows were collected by `DeviceController` and never read. So
"was this parent notified" had two unrelated answers, and the only free channel
sat unused while every message burned a paid WhatsApp template.

Announcements were also the last parent-facing message with **no deferral path at
all** — attendance and homework had one since M8/M9. That is the specific gap
that made a 22:00 notification possible.

---

## 2. Decisions

### 2.1 ATTENDANCE is not mutable

An absence alert carries the NFR-P2 five-minute SLA. A parent who had muted it
would simply not learn their child is missing from class. `HANDOFF_M8` already
decided attendance ignores quiet hours; this keeps that and extends it to opt-out.

The guarantee is enforced in `NotificationPreferenceService.resolve`, as the
*first* branch, so no later lookup can reach a suppression path. The write API
rejects an attempt to disable it with 422 rather than storing a row that lies
about what will happen — and a test writes the forbidden row straight to the
table to prove the resolver holds even when the endpoint is bypassed.

A stored channel *order* is still honoured for attendance, but applied as a
reordering: any channel the user left out is appended back at the end. Dropping
WhatsApp from an absence alert would be an opt-out wearing a channel
preference's clothes.

### 2.2 Two tables, not one

| Table | Grain | Holds |
|---|---|---|
| `notification_settings` | one row per user | quiet-hours window + `respect_quiet_hours` |
| `notification_preferences` | one row per (user, category) | `enabled` + ordered `channels` |

§2.3 describes "user × category × channel, opt-out, quiet hours", which reads as
one table. But a person means *one* thing by "do not message me at night".
Duplicating the window across three category rows lets the three copies disagree
and gives the UI three widgets for one intent.

### 2.3 Absent rows mean defaults, never "off"

A user with no rows is fully opted in, and `respect_quiet_hours` falls back to the
**school's** `alerts_respect_quiet_hours`. So a deployment where nobody has
touched their preferences behaves exactly as it did before this feature existed —
this is a widening, not a change. Nothing needed backfilling, and a failed write
can never silently mute someone.

The resolver also fails **open** on a missing school or an unreadable timezone:
failing closed there would mute a parent for a reason that has nothing to do with
what they asked for.

### 2.4 The decision is made by the fan-out services, not the dispatcher

Two of the three outcomes — suppress and defer — are decisions about a
*recipient row*, and the dispatcher has no row. The fan-out services already own
that state machine (`markDeferred` plus the sweepers), so they ask the resolver
and act; the dispatcher is handed only the resulting channel order.

### 2.5 The OTP path is untouched

`WhatsAppOtpDispatcher` still calls `dispatch(DispatchRequest)`, unchanged. A
login code a user can mute is a support ticket, not a preference — so
`UserDispatchRequest` *wraps* `DispatchRequest` rather than replacing it, and
the OTP path did not even recompile.

### 2.6 Default channel order is PUSH → WHATSAPP → SMS

Cheapest first. Push is free and the only channel that deep-links into the app;
WhatsApp is where these families already are; SMS costs the most per message and
carries no formatting.

A channel with no way to reach the user — no active device token, no phone —
is **unavailable**, not failed: the walk moves on without recording anything
against the recipient. "No device registered" and "FCM rejected the token" must
not produce the same outcome.

### 2.7 SUPPRESSED is distinct from FAILED

Both `DeliveryStatus` and `HomeworkDeliveryStatus` gained `SUPPRESSED`. An
honoured opt-out is not a delivery miss, and every report that counts failures
would be wrong if it were. `dispatchRecalled` therefore leaves SUPPRESSED rows
alone rather than sweeping them into FAILED.

---

## 3. What shipped

**Migration `019`** — two tables with `ON DELETE CASCADE` FKs, RLS policies in the
017/018 shape (including the `nullif(current_setting(k, true), '')::uuid`
fail-closed idiom), and `announcement_recipients.deferred_until` with a partial
index on the DEFERRED status.

**`com.schoolbridge.api.notifications`** — `NotificationCategory`, the two
entities and repositories (both overriding `findById` with JPQL per ADR-002),
`NotificationDecision`, `NotificationPreferenceService`/`Impl`, DTOs, and the
self-service controller.

**`QuietHoursCalculator` moved** from `attendance` to `common.time`, unchanged.
Three modules now evaluate the same window.

**`NotificationDispatcher`** — gained `PUSH`, a channel-ordered
`dispatch(UserDispatchRequest, List<NotificationChannel>)`, and the FCM client.
The existing Redis per-recipient WhatsApp failure counter is preserved exactly
and still demotes WhatsApp underneath the new ordering.

**`AnnouncementDeferralSweeper`** — new, in `integrations`. It is *not* folded
into `AnnouncementScheduleSweeper`, where the cron footprint would have been
cheaper, because announcement dispatch lives in `integrations`: putting the
release scan in the `announcements` module would have made that domain module
call into `integrations` and closed a package cycle.

**Endpoints** — `GET`/`PUT /api/v1/notifications/preferences`, gated by nothing
but authentication. The only row a caller can reach is their own, resolved from
the principal rather than a path variable, so there is no cross-user surface for
a permission to guard. A permission here would imply an administrator could edit
someone else's consent, which is exactly what must not be allowed. Precedent:
`DeviceController`.

---

## 4. Verification

29 new tests:

| Class | Covers |
|---|---|
| `NotificationPreferenceResolverTest` (8) | the whole decision table, incl. attendance immunity against a directly-written opt-out row |
| `NotificationPreferenceIsolationTest` (5) | Hibernate filter + `findById` override + RLS under an unprivileged role, both tables |
| `NotificationDispatcherChannelTest` (6) | channel walk, multi-device fan-out, unavailable-vs-failed, order honoured |
| `NotificationPreferenceControllerTest` (6) | materialised defaults, round-trip, 422s, 401 |
| `AnnouncementDeferralIntegrationTest` (4) | held at fan-out, released by the sweeper, opt-out suppressed, recall during hold |

---

## 5. Operational

New metrics: `push.send.success`, `push.send.failure`,
`notification.suppressed{category}`, `notification.deferred{category}`.

New scheduled job: `AnnouncementDeferralSweeper.releaseDeferredRecipients`, every
minute, `schoolbridge.announcements.deferral-sweeper.enabled` to disable.

Push in production requires `PUSH_FCM_ENABLED=true` and
`GOOGLE_APPLICATION_CREDENTIALS`. Without them `LoggingPushClient` stands in, and
it reports every send as **not accepted** — deliberately. Push is first in the
default order and the dispatcher stops at the first channel that accepts, so a
stub claiming success would have ended the walk and swallowed every notification
for any user with a registered device, in exactly the deployment where FCM has
not been configured yet. Instead the walk falls through to WhatsApp and
`push.send.failure` climbs, which is the signal that FCM is unconfigured. See
`RUNBOOK.md`.

---

## 6. Out of scope

- **Digest notifications** (§14, High) — needs a batching window and its own
  sweeper.
- **Unified `notification_deliveries`** — a consolidation of three existing
  per-feature tables, not a preferences change.
- **Consuming Meta delivery-status webhooks onto rows** — `message_id` is
  stored; nothing reads the callbacks back.
- **Per-category quiet hours** — deliberately rejected in §2.2.
