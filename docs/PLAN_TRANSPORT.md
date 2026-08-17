# Bus Location Sharing — Driver Ping, Parent Polling, Arrival Push

New module `transport` (15th). Changelog `020-transport.sql`. Introduces the
`DRIVER` role and `NotificationCategory.TRANSPORT`.

Status: **PLANNED — not yet approved, no code written.**

---

## 1. Why

A parent standing at a stop has no way to know whether the bus is two minutes
away or twenty. Today SchoolBridge has no transport concept at all — no vehicle,
no route, no stop, no coordinate column anywhere in the schema.

Eight of the nine original requirements are *visibility*: start a trip, report a
position, and let a parent read where the bus is, which stop is next, when it
should arrive, when it did arrive, and when the trip ended. On top of those, a
parent is **pushed** a notification the moment the bus reaches their child's
stop, so the answer arrives without the app being open.

---

## 2. Decisions

### 2.1 A new module, not a home in an existing one

`transport` does not fit anywhere that already exists.

| Candidate | Why not |
|---|---|
| `attendance` | Models presence *in a class*. A vehicle's position is a different aggregate with a different lifecycle. |
| `classes` | A bus route deliberately crosses classes and grades. Folding it in couples the academic-grouping module to a concept it has no reason to know. |
| `notifications` / `integrations` | Both are dispatch-only. Neither owns domain data. |

So: `com.schoolbridge.api.transport`, depending on `common`, `config`, `tenant`,
`identity` (`UserRole`, `StaffPrincipal`), `classes` (read-only: `Student`,
`ParentStudentLinkRepository`), and — for the arrival push — `notifications`
(`NotificationPreferenceService`) and `integrations` (`NotificationDispatcher`,
`UserDispatchRequest`). That last pair is exactly what `attendance` already does
via `AttendanceAlertService`, so it is an established direction, not a new one.

Cross-module reads call `ParentStudentLinkRepository` directly — synchronous,
because the caller needs the answer inside the same request. Same pattern
`PermissionsHelper.parentLinkedTo` already uses. Foreign ids only, no JPA
association: `grep "@ManyToOne\|@OneToMany\|@JoinColumn"` across all of
`src/main/java` matches zero real entities, and `Enrollment` stores
`studentId`/`classId` as bare `UUID` columns joined via JPQL `on`. New entities
follow that exactly.

### 2.2 Adding `DRIVER` is nearly free — except for one file

`identity/UserRole.java:4-9` is `SUPER_ADMIN, SCHOOL_ADMIN, TEACHER, PARENT`.
Adding a fifth value turned out to be cheaper than expected:

- **No DB migration for the enum.** `004-identity.sql:21` declares
  `role VARCHAR(20) NOT NULL` with **no `CHECK` constraint**.
- **No auth change.** `BearerAuthenticationFilter.java:90-96` builds
  `StaffPrincipal(subjectId, schoolId, role)` from whatever role the JWT carries.
  No allow-list.
- **No user-creation change.** `identity/UserServiceImpl.java:58-85` branches only
  on `role == PARENT` vs everything else. `AuthServiceImpl` has zero role checks.

Consequence: `POST /api/v1/schools/{schoolId}/users` with `role: DRIVER` and
`POST /api/v1/auth/login` both work the moment the enum value exists.

**The one exception:** `assistant/llm/SystemPrompt.java:59-64` switches over
`UserRole` with no `default` arm. Adding `DRIVER` **will not compile** until a
`case DRIVER ->` arm is added there. It is the only such switch in the repo
(grepped). Phase 1 does both edits atomically.

### 2.3 ETA is haversine ÷ a configured average speed

Two options were live: a configured duration per consecutive stop pair, or
straight-line distance divided by an assumed speed.

Per-leg durations lose. They require an admin to enter a number for every
adjacent stop pair on every route — data entry that grows with every route edit
and goes stale silently. And they are static: they cannot answer "where is the
bus *now* relative to the next stop", which is the actual question.

Haversine wins because the coordinate data it needs (stop lat/lon) is already
mandatory for stop-arrival detection, so it costs zero additional admin input,
and it recomputes from the bus's real last-reported position on every ping. One
property — `schoolbridge.transport.average-speed-kmh`, default 20 — is the whole
configuration surface.

**Stated limitation, not a defect:** straight-line distance underestimates road
distance, and a flat speed ignores traffic, hills, and boarding time. This is a
rough estimate, not a routing-grade one. Correcting it requires an external
routing/traffic API, explicitly out of scope. `GeoMath` and
`BusTripServiceImpl.buildTripStatus` carry this in a code comment so nobody
"fixes" the accuracy later without realising it means adding a vendor.

### 2.4 Stop arrival is detected by proximity, not by a driver button

One mechanism only. The choice is between the driver explicitly marking each
arrival, and checking the ping's coordinates against the next stop's.

Proximity wins because it adds **zero new driver action**. The manual location
ping is already required, and a driver naturally pings while stopped — boarding
or dropping students is exactly when they are inside the radius. It also reuses
the same haversine call ETA already needs. An explicit button would be a second
thing to remember at the busiest moment of the job.

Mechanics: each ping checks only the **next unvisited stop**, not the whole
route. On a hit (`haversineMeters < stop-arrival-radius-meters`, default 150),
every not-yet-visited stop *up to and including* the hit is backfilled as
arrived. That backfill is what makes an infrequent or skipped ping survivable —
progression stays monotonic instead of stalling on a stop the bus already passed.

**Stated limitation:** accuracy is bounded by ping cadence. A very sparse ping
pattern can miss the exact arrival moment. Accepted deliberately rather than
adding a second detection path.

### 2.5 Trip completion stays an explicit action

This is *not* two mechanisms for one job. Per-stop arrival and trip-level
completion are different objects with different failure modes.

If completion were proximity-based on the final stop, a trip whose last ping
never lands inside that radius hangs open forever — and an open trip blocks the
next one via the active-trip guard (§3, `uk_bus_trips_route_active`). So
`POST /trips/{id}/complete` is a driver action, with a sweeper (§7 Phase 5) as
the backstop for a driver who forgets or whose phone dies.

### 2.6 Retention: last known ping only. No history table.

Every polling requirement is satisfiable from *current position* plus a small
per-stop event table. So there is no ping-history table at all — bounded or
otherwise.

| Where | What |
|---|---|
| `bus_trips` row | `last_latitude`, `last_longitude`, `last_ping_at` |
| `bus_trip_stop_events` | one row **per stop**, not per ping |

A breadcrumb trail on a map is a materially different feature with unbounded
storage. Out of scope.

The same reasoning applies to audit: `AuditService.record(...)` fires on trip
start and complete only. An audit row per ping would recreate the exact
unbounded-history problem this section exists to avoid.

### 2.7 Arrival push — and the four decisions inside it

When the bus reaches a stop, the parents of the students who board/alight at
**that stop** get a push notification. Four sub-decisions carry real weight.

#### 2.7.1 Students are assigned to a stop, not to a route

This is the schema consequence of the push requirement, and it would be a design
improvement even without it. If a student were assigned only to a *route*, then
"the bus arrived" could only be broadcast to every parent on the route — a
15-stop route would push 15 times to every parent, 14 of them irrelevant. That is
an uninstall driver, not a feature.

So `route_student_assignments` carries `route_stop_id`. The fan-out per arrival
is the handful of children who use that one stop.

#### 2.7.2 Transport is push-only — WhatsApp and SMS are excluded

`NotificationDispatcher` walks `PUSH → WHATSAPP → SMS` first-match-wins. For
transport the resolved channel list is **intersected with `{PUSH}`** before
dispatch.

The reason is cost. Two trips a day across ~180 school days is roughly 360
arrival messages per child per year. On a paid WhatsApp template that is a
material per-pupil line item for a message whose entire value expires in ninety
seconds. Push is free and is exactly the channel this belongs on.

A second benefit: no new WhatsApp template has to be registered and approved with
Meta, which removes an external dependency from the critical path.

If the parent has no registered device token, the notification is **dropped and
logged** — not escalated to a paid channel. Same perishability argument.

#### 2.7.3 A deferred transport notification is dropped, never queued

`NotificationDecision` has three outcomes: `suppressed`, `deferUntil != null`
(hold the row until the quiet window ends), or send now.

For transport, `deferred()` is treated as **drop**. Delivering "the bus has
arrived at your stop" three hours later, after the quiet window closes, is worse
than delivering nothing: it is actively misleading. Every other deferral in the
system holds a message whose meaning survives the wait — an announcement, a
homework reminder. A stop arrival does not.

This is a *consumer-side* rule, not a change to `isMutable()`. Making
`TRANSPORT` non-mutable (the `ATTENDANCE` treatment) would also strip a parent's
right to opt out entirely, which is not wanted — a bus ping is a convenience, not
a safeguarding obligation.

#### 2.7.4 `NotificationCategory.TRANSPORT` is mutable, on by default

Added as a normal mutable category, so `isMutable()` stays `this != ATTENDANCE`
and needs no edit. Two consequences worth knowing before the change lands:

- `NotificationPreferenceServiceImpl.toResponse` loops
  `NotificationCategory.values()`, so `GET /api/v1/notifications/preferences`
  grows a fourth category automatically, defaulting to `enabled = true` with the
  default channel order. **No API change, no Flutter change** — the preferences
  screen picks it up for free.
- **Blast radius:** any existing test asserting exactly three categories in that
  response will fail. Those assertions must be updated in the same change.

#### 2.7.5 Exactly-once per stop, at-least-once per delivery

The outbox row is written in the **same transaction** as the
`BusTripStopEvent` insert — no dual write. Because `bus_trip_stop_events` is
unique on `(trip_id, route_stop_id)`, a stop can only ever produce one event row,
so the backfill in §2.4 emits exactly one outbox row per stop even when a single
ping advances the bus past several stops.

RabbitMQ redelivery can still deliver a push twice. Accepted, not defended
against: a duplicate "bus arrived" push is benign, and a dedupe table would
reintroduce exactly the unbounded-row problem §2.6 rejects.

Trip *completion* does not push. Only arrival was asked for, and a completion
push fires while the parent is already looking at their child.

### 2.8 AM and PM routes: `direction` ships now — decided

Adding a `direction` column now, rather than deferring it.

The deferral is tempting because nothing in the requirements names it. It loses
anyway, on asymmetric cost:

- **Now:** one enum, one `NOT NULL` column on a table with no rows, and a
  three-column unique index instead of a two-column one. Hours.
- **Later:** the unique constraint `(school_id, student_id)` will already be
  enforcing "one route per student" against live data. Widening it means a
  migration that has to invent a `direction` value for every existing row, decide
  what a school that has been entering only morning routes actually meant, and
  coordinate a client change — all while the feature is in daily use by drivers
  at 07:00.

And the requirement is real, not hypothetical: a school running a different
afternoon route (later start, different drop order, a child collected by a
grandparent on Tuesdays) is the normal case, not an edge case. Shipping a schema
that structurally cannot express it guarantees the painful migration rather than
risking it.

**Shape:** `direction` lives on `bus_routes` as `RouteDirection` (`MORNING`,
`AFTERNOON`) and is **immutable after creation** — `PATCH /routes/{id}` rejects a
change to it. An admin creates an AM route and a PM route as two routes, which is
what they would do anyway.

Immutability is what makes the denormalised copy safe: `route_student_assignments`
also carries `direction`, copied from the route at assign time and never accepted
from the client, so the unique index can be `(school_id, student_id, direction)`.
Because the source column can never change, the copy can never drift. The
alternative — enforcing one-per-direction at the service layer with a join —
gives up the database-level guarantee for nothing.

---

## 3. Schema — `020-transport.sql`

Next free number: highest existing is `019-notification-preferences.sql`.

The file is **self-contained**, matching `018-attachments.sql`: its own tables,
its own RLS policies, its own permission INSERTs, its own grants — not appended
to `015-authz.sql`.

| # | Changeset | Table |
|---|---|---|
| 1 | `020-bus-routes-table` | `bus_routes(id, school_id, name, description, bus_label, direction, assigned_driver_user_id NULL, status, created_at, updated_at)` |
| 2 | `020-route-stops-table` | `route_stops(id, school_id, route_id, stop_order INT, name, latitude NUMERIC(9,6), longitude NUMERIC(9,6), …)` — unique `(route_id, stop_order)` |
| 3 | `020-route-student-assignments-table` | `route_student_assignments(id, school_id, route_id, route_stop_id, student_id, direction, …)` — unique `(school_id, student_id, direction)` |
| 4 | `020-bus-trips-table` | `bus_trips(id, school_id, route_id, driver_user_id, status, started_at, completed_at NULL, auto_closed BOOL DEFAULT FALSE, last_latitude NULL, last_longitude NULL, last_ping_at NULL, …)` |
| 5 | `020-bus-trip-stop-events-table` | `bus_trip_stop_events(id, school_id, trip_id, route_stop_id, arrived_at, …)` — unique `(trip_id, route_stop_id)` |
| 6 | `020-transport-rls` | `ENABLE ROW LEVEL SECURITY` + policies, all five tables in one changeset (matching `017`'s style) |
| 7 | `020-transport-permissions` | `INSERT INTO permissions` × 4 |
| 8 | `020-grant-transport-permissions` | `role_permissions` rows |

**Every FK to `users(id)` / `schools(id)` / `students(id)` is `ON DELETE
CASCADE`** — `COMMON_MISTAKES.md` #8: without it, existing tests' `deleteAll()`
teardown breaks with a `DataIntegrityViolationException`.

**Indexes beyond the uniques:**
- `uk_bus_trips_route_active ON bus_trips(route_id) WHERE status = 'IN_PROGRESS'`
  — partial unique index. Database-level guard against two concurrent trips on
  one route (double-tap, two drivers).
- `bus_trips(status, last_ping_at)` — the sweeper's scan.
- `bus_trip_stop_events(trip_id)`.
- `route_student_assignments(route_stop_id)` — the arrival fan-out's lookup.

**RLS policy text is copied verbatim from `017`/`018`**, not retyped:
`nullif(current_setting('app.current_tenant', true), '')::uuid` plus the
`app.tenant_bypass` escape. `COMMON_MISTAKES.md` #11 — a reset pooled connection
returns `''`, not NULL, and `''::uuid` raises instead of failing closed.

### 3.1 Permissions

Four new values in `common/security/authz/Permission.java`, mirroring the
existing `CLASS_MANAGE`/`CLASS_READ` split:

| Permission | Granted to |
|---|---|
| `TRANSPORT_MANAGE` | `SUPER_ADMIN`, `SCHOOL_ADMIN` |
| `TRANSPORT_READ` | `SUPER_ADMIN`, `SCHOOL_ADMIN` |
| `TRANSPORT_TRIP_RECORD` | `SUPER_ADMIN`, `DRIVER` |
| `TRANSPORT_TRIP_READ` | `SUPER_ADMIN`, `DRIVER`, `PARENT` |

`SUPER_ADMIN` must be listed **explicitly** in changeset 8. The catch-all seed in
`015-authz.sql` already ran and does not retroactively pick up permissions added
later — `018-grant-attachment-permissions` had to re-list it for the same reason.

Ping and complete share `TRANSPORT_TRIP_RECORD` rather than one permission each,
mirroring `ATTENDANCE_RECORD` covering both attendance writes.

---

## 4. API

All under `/api/v1/transport`. Slash-style action paths only, never `:verb`
(ADR-006, `COMMON_MISTAKES.md` #6). Responses auto-wrapped by
`ApiResponseBodyAdvice` — never hand-wrapped. Every mutating route carries
`@RequirePermission` plus a row-ownership `@PreAuthorize`, matching
`AttendanceController`.

### 4.1 Admin — routes, stops, student assignment

| Method | Path | Permission | `@PreAuthorize` | Request | Response |
|---|---|---|---|---|---|
| POST | `/routes` | `TRANSPORT_MANAGE` | `hasRole('SCHOOL_ADMIN')` | `CreateRouteRequest` | `RouteResponse` |
| GET | `/routes` | `TRANSPORT_READ` | `hasRole('SCHOOL_ADMIN')` | — | `List<RouteResponse>` |
| GET | `/routes/{id}` | `TRANSPORT_READ` | `hasRole('SCHOOL_ADMIN')` | — | `RouteResponse` (stops embedded) |
| PATCH | `/routes/{id}` | `TRANSPORT_MANAGE` | `hasRole('SCHOOL_ADMIN')` | `UpdateRouteRequest` | `RouteResponse` |
| POST | `/routes/{routeId}/stops` | `TRANSPORT_MANAGE` | `hasRole('SCHOOL_ADMIN')` | `AddStopRequest` | `RouteStopResponse` |
| DELETE | `/stops/{id}` | `TRANSPORT_MANAGE` | `hasRole('SCHOOL_ADMIN')` | — | — |
| POST | `/routes/{routeId}/students` | `TRANSPORT_MANAGE` | `hasRole('SCHOOL_ADMIN')` | `AssignStudentRequest` | `RouteStudentResponse` |
| GET | `/routes/{routeId}/students` | `TRANSPORT_READ` | `hasRole('SCHOOL_ADMIN')` | — | `List<RouteStudentResponse>` |
| DELETE | `/route-students/{id}` | `TRANSPORT_MANAGE` | `hasRole('SCHOOL_ADMIN')` | — | — |

`AssignStudentRequest` carries `studentId` **and `routeStopId`** (§2.7.1). The
service validates that the stop belongs to the route, and copies `direction` from
the route — the client never supplies it (§2.8).

`UpdateRouteRequest` rejects a change to `direction` with 422
(`error.transport.direction_immutable`).

No standalone `GET /routes/{id}/stops` — a route's stop count is small and stops
are embedded in `RouteResponse`.

### 4.2 Driver

| Method | Path | Permission | `@PreAuthorize` | Request | Response |
|---|---|---|---|---|---|
| GET | `/routes/my-assigned` | `TRANSPORT_TRIP_READ` | `hasRole('DRIVER')` | — | `List<RouteResponse>` |
| POST | `/trips/start` | `TRANSPORT_TRIP_RECORD` | `hasRole('DRIVER') and @perms.driverAssignedToRoute(#request.routeId())` | `StartTripRequest` | `TripResponse` |
| POST | `/trips/{id}/ping` | `TRANSPORT_TRIP_RECORD` | `hasRole('DRIVER') and @perms.isTripDriver(#id)` | `LocationPingRequest` | `TripResponse` |
| POST | `/trips/{id}/complete` | `TRANSPORT_TRIP_RECORD` | `hasRole('DRIVER') and @perms.isTripDriver(#id)` | — | `TripResponse` |
| GET | `/trips/{id}` | `TRANSPORT_TRIP_READ` | `hasRole('SCHOOL_ADMIN') or @perms.isTripDriver(#id)` | — | `TripResponse` |

A driver assigned to both an AM and a PM route sees both from
`/routes/my-assigned` and picks one at start time.

### 4.3 Parent

| Method | Path | Permission | `@PreAuthorize` | Response |
|---|---|---|---|---|
| GET | `/children/{studentId}/routes` | `TRANSPORT_TRIP_READ` | `hasRole('PARENT') and @perms.parentLinkedTo(#studentId)` | `List<RouteResponse>` (0–2: AM, PM) |
| GET | `/children/{studentId}/trip` | `TRANSPORT_TRIP_READ` | `hasRole('PARENT') and @perms.parentLinkedTo(#studentId)` | `TripStatusResponse` |

`/routes` is plural because a student can now hold an AM and a PM assignment. Each
`RouteResponse` carries its `direction` and the child's own `routeStopId`, so the
client can highlight the right stop.

`/trip` stays singular: it resolves the one `IN_PROGRESS` trip across *all* the
student's assigned routes. Only one bus carries a child at a time, so in practice
there is at most one; if both an AM and PM trip are somehow open, the most
recently started wins. It returns a not-started state rather than 404 when there
is no trip — "no bus running right now" is a normal answer, not an error.

`/routes` returns an empty list rather than 404 when the student is on no route.

### 4.4 Authorization — the parent chain

**A parent-facing endpoint never accepts a `tripId`.** Both are keyed on
`studentId`; the trip is resolved server-side:

```
PARENT principal
  → @perms.parentLinkedTo(studentId)      (existing, ParentStudentLinkRepository)
  → route_student_assignments.student_id  → route_id (0–2 rows, AM/PM)
  → bus_trips WHERE route_id IN (…) AND status = 'IN_PROGRESS'
```

There is no route by which a `PARENT` principal can address an arbitrary trip id:
`GET /trips/{id}` allows only `SCHOOL_ADMIN` or the trip's own driver.

`@perms.isTripDriver` reads `bus_trips.driver_user_id`, captured at trip start —
**not** the route's *current* `assignedDriverUserId`. If an admin reassigns the
route mid-trip, the in-flight trip stays owned by whoever started it.

Two new methods on `common/security/PermissionsHelper.java`:
`driverAssignedToRoute(UUID routeId)`, `isTripDriver(UUID tripId)` — with
`BusRouteRepository` / `BusTripRepository` injected via constructor.

---

## 5. Arrival push — event flow

### 5.1 Publish

`BusTripServiceImpl.recordPing`, in the **same transaction** as the
`BusTripStopEvent` insert, writes one outbox row per newly-arrived stop:

```
eventType:     transport.stop_arrived
aggregateType: BUS_TRIP
aggregateId:   tripId
payload (HashMap, never Map.of — COMMON_MISTAKES.md #3):
  schoolId, tripId, routeId, routeStopId, stopName, stopOrder,
  arrivedAt, traceId
```

`traceId` is carried so the consumer's log lines correlate back to the originating
`POST /trips/{id}/ping`, exactly as the attendance payload does.

### 5.2 Consume

`integrations/rabbit/TransportStopArrivedConsumer` — modelled line-for-line on
`AttendanceAlertConsumer`: `@RabbitListener` +
`@ConditionalOnProperty("schoolbridge.outbox.relay.enabled", havingValue = "true")`,
parse to `JsonNode`, restore `traceId` into MDC, wrap the work in
`TenantContext.runAs(schoolId, …)` so `@Filter` applies during recipient loads,
record a `transport.arrival.latency` timer, and expose a package-visible
`handle(byte[])` so integration tests can invoke it without RabbitMQ.

It delegates to `TransportAlertService` in the `transport` module — the consumer
owns no domain logic, mirroring how `AttendanceAlertConsumer` delegates to
`AttendanceAlertService`.

### 5.3 Fan-out — `TransportAlertService.dispatchStopArrival(tripId, routeStopId)`

```
route_student_assignments WHERE route_stop_id = ?     → studentIds  (§2.7.1)
  → ParentStudentLink                                  → parent userIds (distinct)
    → NotificationPreferenceService.resolve(schoolId, userId, TRANSPORT, now)
        suppressed        → drop, log, next parent
        deferred()        → drop, log, next parent          (§2.7.3)
        otherwise         → channels ∩ {PUSH}               (§2.7.2)
                              empty → drop, log
                              else  → NotificationDispatcher.dispatch(UserDispatchRequest)
```

`UserDispatchRequest` is built with `pushTitle` / `pushBody` rendered from the
i18n bundle in the parent's language, and `pushData` as a **`HashMap`** carrying
`tripId`, `routeId`, `routeStopId` for the app's deep link — the record's own
javadoc warns that entity ids in it are frequently null, so `Map.of` would NPE.

`DispatchRequest` still has to be constructed (it is a component of
`UserDispatchRequest`), but with the channel list intersected to `{PUSH}` the
WhatsApp and SMS fields are never read. `templateName` is set to the transport
key for symmetry and traceability, not because a Meta template exists.

### 5.4 New queue and routing key

`schoolbridge.rabbitmq.queues.transport-stop-arrived` plus its binding in
`RabbitConfig`, following the three attendance-alert queues already declared
there.

---

## 6. Configuration and i18n

```yaml
schoolbridge:
  transport:
    average-speed-kmh: 20
    stop-arrival-radius-meters: 150
    sweeper:
      enabled: true
      cron: "0 */10 * * * *"
      stale-trip-after: PT4H
  rabbitmq:
    queues:
      transport-stop-arrived: schoolbridge.transport.stop-arrived
```

Error keys, in **both** `messages_en.properties` and `messages_ar.properties` —
i18n parity is a hard requirement:

```
error.transport.route_not_found
error.transport.stop_not_found
error.transport.stop_not_on_route
error.transport.route_inactive
error.transport.direction_immutable
error.transport.driver_not_assigned
error.transport.trip_already_active
error.transport.trip_not_found
error.transport.trip_not_in_progress
error.transport.student_already_assigned
error.transport.student_not_on_route
error.transport.invalid_coordinates
```

Push content keys, both locales:

```
notification.transport.stop_arrived.title
notification.transport.stop_arrived.body     # params: {0} stop name, {1} child first name
```

No bespoke rate limiter for `/trips/{id}/ping`. `RateLimitInterceptor` already
throttles every authenticated mutating request at 120/min per principal
(`P0_REMEDIATION.md` §5), which a human-tapped button sits well inside.
`OtpRequestRateLimiter`'s per-phone dual-window model targets an unauthenticated
public endpoint — a different threat shape that does not apply here.

---

## 7. Phases

### Phase 1 — `DRIVER` role and authz plumbing
1. Add `DRIVER` to `identity/UserRole.java`.
2. Add `case DRIVER -> "bus driver";` to `assistant/llm/SystemPrompt.java:59-64`
   **in the same edit** — compile-blocking otherwise (§2.2).
3. Add the four `Permission` values.
4. Write `020-transport.sql` changesets 7–8 (permissions, grants). Authoring them
   before the tables is safe: changesets in one file execute in order.
5. Smoke check (no new code): `POST /api/v1/schools/{schoolId}/users` with
   `role: DRIVER` creates a staff-shaped user; `POST /api/v1/auth/login` returns a
   JWT carrying `role: DRIVER`.

### Phase 2 — Schema, entities, repositories
1. `020-transport.sql` changesets 1–6 (five tables + RLS).
2. `- include:` entry in `db.changelog-master.yaml`.
3. Entities, all extending `TenantEntity`, plain UUID FK fields, no JPA
   associations: `BusRoute`, `RouteStatus` (`ACTIVE`, `INACTIVE`),
   `RouteDirection` (`MORNING`, `AFTERNOON`), `RouteStop`,
   `RouteStudentAssignment`, `BusTrip`, `TripStatus` (`IN_PROGRESS`,
   `COMPLETED`), `BusTripStopEvent`.
4. Five repositories, **each overriding `findById` with an explicit `@Query`**
   (Critical Rule #1 — the Hibernate `@Filter` does not apply to
   `EntityManager.find()`). Plus: `existsByIdAndAssignedDriverUserId`,
   `existsByIdAndDriverUserId`, `findByRouteIdOrderByStopOrder`,
   `findByRouteIdAndStatus`, `findByStudentId`, `findByRouteStopId`,
   `findByTripId`, `findStaleInProgress(Instant, Pageable)`.

### Phase 3 — `GeoMath` and admin CRUD
1. `GeoMath.haversineMeters(lat1, lon1, lat2, lon2)` — pure static, no Spring
   dependency, unit-testable standalone.
2. `BusRouteService` / `Impl` — route create/list/get/update (rejecting a
   `direction` change); add/remove stop (validate `stop_order` uniqueness and
   lat/lon range); assign/remove student (validate the stop belongs to the route,
   copy `direction` from the route, pre-check `(school_id, student_id, direction)`
   for a friendly `ConflictException` rather than a raw constraint violation).
3. `BusRouteController` — §4.1 plus `/routes/my-assigned`.

### Phase 4 — Trip lifecycle, ETA, stop status
1. `startTrip` — validate route `ACTIVE`, no existing `IN_PROGRESS` trip
   (`ConflictException` ahead of the partial unique index), create `BusTrip`, one
   `AuditService.record(...)` with a `HashMap` payload.
2. `recordPing` — validate `IN_PROGRESS`, update
   `last_latitude`/`last_longitude`/`last_ping_at`, proximity-check the next
   unvisited stop, backfill `BusTripStopEvent` rows on a hit (§2.4).
3. `completeTrip` — validate `IN_PROGRESS`, set `COMPLETED` + `completedAt`,
   audit.
4. `buildTripStatus` — resolve last-visited/next stop from `BusTripStopEvent`
   rows; compute `etaMinutesToNextStop` and `expectedArrivalAtNextStop` from
   cumulative haversine over remaining legs from the current position ÷
   `average-speed-kmh`.
5. `BusTripController` + `ParentTransportController`, wired per §4.2/§4.3.
6. `PermissionsHelper.driverAssignedToRoute` / `.isTripDriver`.

Timestamps are `Instant` UTC throughout; conversion to a local zone happens only
at the controller edge.

### Phase 5 — Stale-trip sweeper
`BusTripSweeper`, modelled on `AttendanceSweeper` / `AttachmentSweeper`:
`@Scheduled(cron = "${schoolbridge.transport.sweeper.cron}")` +
`@ConditionalOnProperty(..., matchIfMissing = true)`, tenant-unbound
(`TenantContext.clear()`) so one pass covers every school. Force-completes trips
where `COALESCE(last_ping_at, started_at) < now() - stale-trip-after`, sets
`auto_closed = true`, audits each.

### Phase 6 — Arrival push
1. Add `TRANSPORT` to `NotificationCategory` (§2.7.4) and **fix the existing
   preference tests that assert a three-category response** — this is the whole
   blast radius, but it is not optional.
2. Emit the `transport.stop_arrived` outbox row from `recordPing`, inside the
   existing transaction (§5.1).
3. `TransportAlertService` / `Impl` in the `transport` module — the fan-out and
   the three drop rules (§5.3).
4. `TransportStopArrivedConsumer` in `integrations/rabbit` (§5.2), plus the queue
   and binding in `RabbitConfig` (§5.4).

### Phase 7 — Config and i18n
`application.yml` block and every message key from §6, both locales.

### Phase 8 — Tests
Written alongside each phase per TDD, not deferred. See §9.

---

## 8. Deliberately out of scope

| Left out | Why |
|---|---|
| Ping-history / breadcrumb table | Only the last ping is stored. A map trail is a different, unbounded-storage feature. |
| A `Bus` / fleet entity | A route carries a free-text `bus_label`. Nothing needs capacity, plates, or maintenance state. |
| "Bus arriving soon" pre-warning push | Only *arrival* was asked for. A proximity- or ETA-threshold pre-warning needs its own tuning (how many minutes? recomputed per ping?) and doubles the message volume. The ETA is already visible in-app. |
| Push on trip completion | Fires while the parent is already looking at their child. |
| WhatsApp / SMS for transport | §2.7.2 — cost, and no Meta template dependency. |
| Deduplicating redelivered pushes | §2.7.5 — benign, and a dedupe table reintroduces unbounded rows. |
| Admin acting on a driver's behalf | Only the assigned driver can start/ping/complete. If a driver is out, an admin reassigns the route (`PATCH /routes/{id}`). |
| Bespoke ping rate limiter | Global `RateLimitInterceptor` already covers it (§6). |
| Audit row per ping | Would recreate the unbounded-history problem §2.6 exists to avoid. |
| More than four permissions | Ping and complete share `TRANSPORT_TRIP_RECORD`, as `ATTENDANCE_RECORD` covers both attendance writes. |

---

## 9. Test strategy

**Unit (no Spring context)**
- `GeoMathTest` — known distance pairs, zero-distance, antipodal edge.
- `BusTripServiceImplTest` (Mockito) — ETA arithmetic, stop backfill on a
  proximity hit, ping rejected on a `COMPLETED` trip, one outbox row per newly
  arrived stop when a single ping advances past several.
- `TransportAlertServiceTest` (Mockito) — the three drop rules:
  `dispatchStopArrival_parentSuppressedCategory_doesNotDispatch`,
  **`dispatchStopArrival_insideQuietWindow_dropsInsteadOfDeferring`** (§2.7.3),
  `dispatchStopArrival_parentHasNoPushChannel_dropsWithoutFallingBackToWhatsApp`
  (§2.7.2), and
  **`dispatchStopArrival_onlyNotifiesParentsOfStudentsAtThatStop`** (§2.7.1 — the
  test that proves a 15-stop route does not push 15 times to everyone).

**Integration** — REST Assured + `AbstractIntegrationTest`, matching sibling
modules. Naming: `methodName_scenario_expectedBehavior`.
- `BusRouteControllerIntegrationTest` — admin CRUD happy path, 403 for non-admin,
  `updateRoute_changingDirection_returns422`,
  `assignStudent_stopNotOnRoute_returns422`,
  `assignStudent_sameStudentSameDirection_returns409`,
  `assignStudent_sameStudentOppositeDirection_succeeds` (§2.8's whole point).
- `BusTripControllerIntegrationTest` —
  `startTrip_driverAssignedToRoute_createsInProgressTrip`,
  `startTrip_driverNotAssigned_returns403`,
  `startTrip_routeAlreadyHasActiveTrip_returns409`,
  `ping_proximityWithinRadius_marksStopArrived`,
  `ping_notOwnTrip_returns403`,
  `complete_ownTrip_setsCompletedStatus`.
- `ParentTransportControllerIntegrationTest` —
  **`getTrip_unlinkedParent_returns404`** (the parent-cannot-view-another-child's-trip
  case: no `ParentStudentLink` → 404, proving the studentId-keyed path is the only
  way in), `getRoutes_linkedParent_returnsBothDirections`,
  `getTrip_noTripToday_returnsNotStartedState`.
- `TransportStopArrivedConsumerIntegrationTest` — invokes `handle(byte[])`
  directly (no RabbitMQ), asserts the push lands on the right parents and that
  tenant context was bound during the fan-out.
- `BusTripSweeperIntegrationTest` — mirrors
  `AnnouncementScheduleSweeperIntegrationTest`: stale trip force-completed with
  `autoClosed = true`; fresh trip untouched; a second tick does not
  double-process.

**Tenant isolation** — one per new repository, five total:
`BusRouteRepositoryIsolationTest`, `RouteStopRepositoryIsolationTest`,
`RouteStudentAssignmentRepositoryIsolationTest`, `BusTripRepositoryIsolationTest`,
`BusTripStopEventRepositoryIsolationTest`.

Each asserts **both** layers: school A's row invisible to a school B `findById`
(application-side `@Filter` proof) *and* a raw-SQL assertion under `RlsTestRole` /
`SET LOCAL ROLE` (database-side RLS proof). The `SET LOCAL ROLE` part is not
optional — Testcontainers connects as superuser, superusers bypass RLS
unconditionally, and `FORCE ROW LEVEL SECURITY` does not subject them, so an
isolation test on the default connection is false-green (`COMMON_MISTAKES.md`
#12).

**Existing tests to update:** any assertion that
`GET /api/v1/notifications/preferences` returns exactly three categories
(§2.7.4).

**ArchUnit** — `TenantEntityArchUnitTest` picks up all five new entities
automatically via package-wide `@AnalyzeClasses`. No test edit needed, but it must
be confirmed green.

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| `DRIVER` breaks the exhaustive switch in `SystemPrompt` → build fails | Phase 1.2 adds the arm in the same atomic edit as the enum value. |
| `TRANSPORT` category breaks existing preference-response assertions | Phase 6.1 updates them in the same change; called out explicitly rather than discovered at `mvn verify`. |
| A parent polls an arbitrary trip | Parent endpoints accept no `tripId` at all (§4.4); `GET /trips/{id}` is admin-or-own-driver only. |
| Every parent on a route gets pushed at every stop | Students are assigned to a *stop*, and the fan-out query is keyed on `route_stop_id` (§2.7.1). Has its own unit test. |
| A quiet-hours deferral delivers "bus arrived" hours late | Consumer treats `deferred()` as drop (§2.7.3). Has its own unit test. |
| Arrival push silently falls back to paid WhatsApp | Channel list intersected to `{PUSH}` before dispatch (§2.7.2). Has its own unit test. |
| Duplicate push on RabbitMQ redelivery | Accepted (§2.7.5) — benign, and a dedupe table reintroduces unbounded rows. Per-*stop* duplication is already impossible via the `(trip_id, route_stop_id)` unique index. |
| Two concurrent trips on one route | Partial unique index `uk_bus_trips_route_active` + a service pre-check returning 409 instead of a raw constraint 500. |
| Denormalised `direction` drifts from the route's | `bus_routes.direction` is immutable after creation and `PATCH` rejects changing it (§2.8), so the copy cannot go stale. |
| RLS GUC empty-string trap | Policy text copied verbatim from `017`/`018`, `nullif(...)` intact. `COMMON_MISTAKES.md` #11. |
| False-green isolation test on the superuser connection | `RlsTestRole` / `SET LOCAL ROLE`, per `TenantRlsIntegrationTest`. `COMMON_MISTAKES.md` #12. |
| Missing `ON DELETE CASCADE` breaks existing `deleteAll()` teardown | Every new FK is `ON DELETE CASCADE` (§3). `COMMON_MISTAKES.md` #8. |
| `Map.of(...)` NPE in the outbox payload or `pushData` | Both built with `HashMap` from day one. `COMMON_MISTAKES.md` #3 — and `UserDispatchRequest`'s own javadoc warns about exactly this. |
| ETA materially wrong on a route mixing a highway leg with dense residential legs | Documented here and in code comments on `GeoMath` / `buildTripStatus`. Not solvable without a routing API, which is out of scope. |
| Driver reassigned mid-trip | `isTripDriver` checks `bus_trips.driver_user_id` captured at start, not the route's current assignee (§4.4). |

---

## 11. Complexity

**Medium.** Five tables / entities / repositories, three services, three
controllers, one consumer, one new role, one new notification category. No new
libraries — no PostGIS, no routing SaaS, no WebSocket, no MQTT, no new broker, no
new WhatsApp template. The bulk is mechanical CRUD; the genuinely novel pieces are
`GeoMath` + `buildTripStatus` (small, independently unit-testable) and the
three-drop-rule fan-out in `TransportAlertService`.

---

## 12. Resolved questions

| Question | Decision | Where |
|---|---|---|
| AM/PM routes | **Ship `direction` now.** Immutable on the route, denormalised onto the assignment so the unique index can be `(school_id, student_id, direction)`. Deferring it means migrating live data under a constraint that already says one-route-per-student. | §2.8 |
| Arrival notification | **Push on stop arrival.** Push-only (no paid channels), dropped rather than deferred inside quiet hours, addressed to the parents of the students at *that stop* — which is why students are now assigned to a stop, not a route. | §2.7, §5 |
