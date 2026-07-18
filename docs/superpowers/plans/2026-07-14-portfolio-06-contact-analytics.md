# Portfolio Contact, Email, and Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a privacy-conscious contact inbox with durable email notification, plus opt-in first-party analytics whose daily PV, daily UV, referrals, project activity, and download/click counts are reproducible and explainable.

**Architecture:** Public contact and event controllers accept only explicit request DTO fields and delegate to transactional application services. Contact submission inserts the message and a dedicated email outbox row atomically; a leased outbox worker sends mail without sharing the generic background-job queue. Analytics derives daily HMAC keys from short-lived browser identifiers, stores no IP or full User-Agent, aggregates with idempotent database jobs, and removes raw events after 30 days.

**Tech Stack:** Java 17, Spring Boot 3.5.7, Maven Wrapper 3.9.11, MyBatis-Plus 3.5.7, PostgreSQL 17, Flyway, Spring Mail, Jackson, JUnit 5, Testcontainers, AssertJ.

## Global Constraints

- Complete plans 01, 02, and 03 first. This plan consumes `RateLimiter`, `AuditService`, `CurrentAdminProvider`, `DomainException`, `BackgroundJobService`, `JobHandler`, and the current-publication lookup contract.
- Plan 01 pre-registers the exact shared limiter policies consumed here: `public-contact` is 5 requests per `PT15M`, and `public-events` is 60 requests per `PT1M`; do not rename them or create feature-local limiter instances.
- Use package root `xyz.yychainsaw.portfolio`; the `message` and `analytics` modules may communicate only through declared application interfaces.
- Never log or include in error responses a visitor email, message body, browser identifier, SMTP credential, HMAC secret, raw IP, or full User-Agent.
- Both public POST endpoints remain protected by plan 01's cookie-backed CSRF filter. Browser clients first use the anonymous no-store `GET /api/admin/auth/csrf` contract and send its declared header/token; a missing or invalid token returns the common `403 CSRF_INVALID` problem before application code.
- Contact submission must commit the message and outbox row in one transaction and return before SMTP delivery.
- The email outbox is separate from `background_job`; SMTP is at-least-once, uses a stable RFC `Message-ID`, and may duplicate after a process crash but may not lose a committed notification.
- Contact messages are retained for one year unless the administrator deletes them earlier. Deletion removes visitor PII and leaves only the existing redacted audit record.
- Analytics is off until the visitor explicitly opts in. `DNT=1` suppresses the consent prompt, identifier creation, and requests. Public pages must remain fully functional without analytics.
- Analytics stores no raw or hashed IP. IP may exist only in request memory long enough to build a rate-limit subject.
- A browser visitor ID rotates every 30 days; session IDs live in `sessionStorage` and rotate after 30 minutes of inactivity. The server persists only daily HMAC visitor/session keys.
- Site-day boundaries use `Asia/Hong_Kong`. Raw analytics events live for 30 days; daily aggregates are retained.
- Every production change follows red-green-refactor, runs the stated focused command, and ends in a small commit.

## File Map

- `backend-parent/portfolio-server/src/main/resources/db/migration/V9__contact_and_email.sql`: messages and dedicated email outbox.
- `backend-parent/portfolio-server/src/main/resources/db/migration/V10__privacy_analytics.sql`: raw events and daily aggregates.
- `backend-parent/portfolio-server/src/main/resources/db/migration/V11__analytics_retention_checkpoint.sql`: immutable per-date purge checkpoints and database guards for safe recovery.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/*`: contact intake, inbox, outbox worker, SMTP adapter, and retention.
- `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/*`: event validation, privacy transforms, aggregation, retention, and reports.
- `backend-parent/portfolio-server/src/main/resources/analytics/analytics-rules-v1.yml`: versioned event, page, and crawler rules.
- `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/*`: message, outbox, API, and retention tests.
- `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/*`: event, deduplication, aggregation, privacy, and report tests.
- `docs/operations/contact-analytics.md`: operating definitions, privacy rules, retention, retry, and troubleshooting.

---

### Task 1: Add contact and analytics migrations

**Files:**
- Create: `backend-parent/portfolio-server/src/main/resources/db/migration/V9__contact_and_email.sql`
- Create: `backend-parent/portfolio-server/src/main/resources/db/migration/V10__privacy_analytics.sql`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/ContactAnalyticsSchemaMigrationTest.java`

**Interfaces:**
- Consumes: Plan 01 `xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase`; plan 02 already owns V3-V6 and plan 03 owns V7/V8.
- Produces: `contact_message`, `email_outbox`, `analytics_event`, and `analytics_daily`.

- [x] **Step 1: Write the failing migration test**

Create a `@SpringBootTest`, `@Isolated` PostgreSQL integration test extending
`PostgresIntegrationTestBase`. It must inspect the catalog and exercise real inserts/updates,
not merely check that the four relation names exist. Assert the exact column whitelist,
bounded sensitive text, named keys/checks/indexes/triggers, Flyway V1-V10 order, database
rejection behavior, and the two exact plan-01 rate policies.

Inspect direct table and column ACLs rather than granting full table CRUD to every component.
The shared capability role has this exact matrix:

- `contact_message`: table `SELECT/INSERT/DELETE`; column `UPDATE` only for `status`,
  `version`, and `updated_at`;
- `email_outbox`: table `SELECT/INSERT`; column `UPDATE` only for delivery state,
  attempts, retry time, lease fields, sanitized error summary, `sent_at`, and `updated_at`;
- `analytics_event`: table `SELECT/INSERT/DELETE`, with no update capability;
- `analytics_daily`: table `SELECT/INSERT/DELETE`; column `UPDATE` only for
  `metric_count`, `aggregation_version`, and `updated_at`.

Assert that `PUBLIC` and the runtime login have no direct grants, all objects remain
owned by the migrator, no grant is grantable, and the runtime has no schema `CREATE`,
`TRUNCATE`, `REFERENCES`, `TRIGGER`, or `MAINTAIN` capability.

- [x] **Step 2: Run the migration test and verify it fails**

Run from `backend-parent`:

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=ContactAnalyticsSchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the V9 and V10 tables do not exist.

- [x] **Step 3: Create V9 with message and outbox constraints**

Implement the canonical, schema-qualified V9 migration in the file named above. In addition to
the declared columns, it must include:

- named checks for message status, lowercase 64-hex dedupe keys, non-negative version, and
  privacy acceptance no later than creation;
- a one-to-one outbox foreign key (`contact_message_id UNIQUE`) with `ON DELETE CASCADE`, plus
  a unique stable message ID;
- fenced outbox state: `SENDING` requires a trimmed, non-blank lease owner and lease expiry;
  every other state has neither, and only `SENT` has `sent_at`;
- stable inbox, dedupe, message-retention, ready-outbox, and expired-lease indexes;
- the existing `portfolio.set_updated_at()` trigger on both mutable tables;
- explicit `REVOKE ALL` from `PUBLIC` and `portfolio_runtime_access` before granting only the
  ACL matrix above.

The configured site-owner address is the only `to_address`. Do not send an automatic reply to the visitor in this phase.

- [x] **Step 4: Create V10 with analytics privacy and reporting constraints**

Implement the canonical, schema-qualified V10 migration in the file named above. Preserve the
exact column whitelist and add named constraints that enforce:

- lowercase 64-hex daily visitor/session keys and the exact event/device/locale enums;
- `site_date = (received_at AT TIME ZONE 'Asia/Hong_Kong')::date`;
- the version-1 page allowlist (`HOME`, `ABOUT`, `WORK`, `ROADMAP`, `CONTACT`, `PRIVACY`,
  `PROJECT_DETAIL`) for raw events and PAGE aggregates;
- referrers are null, `(direct)`/`(none)`, or normalized lowercase ASCII hostnames—never a URL,
  userinfo, query, IP literal, or embedded daily key;
- PV and DAILY_UV pair only with PAGE_VIEW; EVENT_COUNT may pair with every allowed event;
- dimension-specific values, a separate daily-key leak guard, and non-negative metric counts;
- stable event-date/dedupe/retention and daily-report indexes, the aggregate update trigger,
  explicit revocation, and only the ACL matrix above.

Do not add IP, IP hash, browser identifier, session identifier, full URL query, or full User-Agent columns. Constrain aggregate dimension values by dimension: `ALL` uses `(all)`, `PAGE` uses an allowlisted uppercase key, `PROJECT` uses a canonical lowercase UUID, `REFERRER` uses a normalized lowercase hostname or `(direct)`/`(none)`, `DEVICE` uses the device enum, and `LOCALE` uses `zh-CN`/`en`. The durable aggregate table must reject any raw visitor/session day HMAC, including one embedded in another value.

- [x] **Step 5: Re-run the migration test and inspect Flyway order**

Run the Step 2 command.

Expected: PASS; Flyway applies V1 through V10 in order, all four tables exist, runtime has their required DML but no TRUNCATE/schema-CREATE privilege, and both public limiter policies match plan 01 exactly.

- [x] **Step 6: Commit the schema slice**

```powershell
git add backend-parent/portfolio-server/src/main/resources/db/migration/V9__contact_and_email.sql backend-parent/portfolio-server/src/main/resources/db/migration/V10__privacy_analytics.sql backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/ContactAnalyticsSchemaMigrationTest.java docs/superpowers/plans/2026-07-14-portfolio-06-contact-analytics.md
git commit -m "feat(contact): add message and analytics schema"
```

---

### Task 2: Accept contact submissions transactionally and privately

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/SubmitContactCommand.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/ContactSubmissionResult.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/ContactMessageService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/ContactFingerprintService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/config/ContactProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/persistence/ContactMessageRecord.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/persistence/ContactMessageMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/persistence/EmailOutboxRecord.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/persistence/EmailOutboxMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/web/PublicContactController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/web/PublicContactRequest.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/web/PublicContactBodyReader.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/web/ContactRateLimitSubjectHasher.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/common/error/GlobalProblemHandler.java`
- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Modify: `backend-parent/portfolio-server/src/test/resources/application-test.yml`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/application/ContactMessageServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/web/PublicContactControllerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/web/PublicContactBodyReaderTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/config/ContactPropertiesTest.java`

**Interfaces:**
- Consumes: plan-01 CSRF enforcement, `RateLimiter#consume("public-contact", subject)`, `DomainException`, Jackson, `Clock`, and a request-IP resolver that trusts only the plan-01 proxy boundary.
- Produces: `POST /api/public/contact` and one committed `email_outbox` row per accepted non-duplicate message.

- [x] **Step 1: Write service tests for atomic insert, deduplication, and the honey field**

```java
@Test
void acceptedMessageAndNotificationCommitTogether() {
    SubmitContactCommand command = new SubmitContactCommand(
        "Player One", "player@example.com", "UE collaboration",
        "I would like to discuss your project.", "", true,
        "a".repeat(64));

    ContactSubmissionResult result = service.submit(command);

    assertThat(result.accepted()).isTrue();
    assertThat(contactMapper.findById(result.messageId())).isPresent();
    assertThat(outboxMapper.findByMessageId(result.messageId())).hasSize(1);
}

@Test
void repeatedContentInSameTenMinuteWindowReturnsGenericAcceptanceWithoutSecondRow() {
    clock.set(instant("2026-07-14T10:01:00Z"));
    ContactSubmissionResult first = service.submit(validCommand());
    clock.set(instant("2026-07-14T10:08:59Z"));
    ContactSubmissionResult second = service.submit(validCommand());

    assertThat(first.accepted()).isTrue();
    assertThat(second).isEqualTo(ContactSubmissionResult.acceptedWithoutIdentifier());
    assertThat(contactMapper.count()).isEqualTo(1);
    assertThat(outboxMapper.count()).isEqualTo(1);
}

@Test
void populatedHoneyFieldReturnsGenericAcceptanceAndWritesNothing() {
    ContactSubmissionResult result = service.submit(honeypotCommand());

    assertThat(result).isEqualTo(ContactSubmissionResult.acceptedWithoutIdentifier());
    assertThat(contactMapper.count()).isZero();
    assertThat(outboxMapper.count()).isZero();
}
```

Use a PostgreSQL integration test for the rollback case: force the outbox insert to fail and assert that no `contact_message` survives.

- [x] **Step 2: Run focused tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=ContactMessageServiceTest,PublicContactControllerTest,PublicContactBodyReaderTest,ContactPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the intake service and endpoint do not exist.

- [x] **Step 3: Define the strict request and command boundary**

```java
public record PublicContactRequest(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 160) String subject,
    @NotBlank @Size(max = 5000) String message,
    @Size(max = 200) String website,
    @AssertTrue boolean privacyAccepted
) {}

public record SubmitContactCommand(
    String name,
    String email,
    String subject,
    String body,
    String website,
    boolean privacyAccepted,
    String rateLimitSubject
) {}

public record ContactSubmissionResult(boolean accepted, UUID messageId) {
    public static ContactSubmissionResult accepted(UUID id) {
        return new ContactSubmissionResult(true, id);
    }

    public static ContactSubmissionResult acceptedWithoutIdentifier() {
        return new ContactSubmissionResult(true, null);
    }
}
```

Reject unknown and duplicate JSON fields for this DTO, disable scalar coercion by checking every tree value's exact JSON type, and reject trailing tokens. An endpoint-specific bounded reader must reject both declared and chunked/no-length bodies above 32,768 raw bytes before deserializing them, returning `413 PAYLOAD_TOO_LARGE`; the multipart limit does not cover JSON. The controller deliberately does not apply eager `@Valid` before the honeypot check: it maps a null `website` to `""`, returns the same generic `202` immediately for any nonblank honeypot value, and only then invokes manual Bean Validation/application normalization for a real submission. Syntax/type/unknown/duplicate failures are `400 MALFORMED_REQUEST`; well-formed field failures use the existing `422 VALIDATION_ERROR` contract. Normalize Unicode to NFC, trim Unicode whitespace at the boundary, normalize CRLF/CR and Unicode line separators to LF in the body, lowercase the email domain only, reject malformed UTF-16, single-line separators, unsafe format controls, and control characters except LF in the body, and never reflect submitted values in the response.

- [x] **Step 4: Implement a secret-keyed rolling ten-minute duplicate check**

```java
public String contactKey(SubmitContactCommand command) {
    String material = normalizeEmail(command.email()) + "\n"
        + normalizeText(command.subject()) + "\n"
        + normalizeText(command.body());
    return hex(hmacSha256(contactProperties.dedupeSecret(), material));
}
```

Store the secret as canonical base64 environment configuration and require at least 256 decoded bits. After normalization and rate limiting, open an explicit `READ_COMMITTED` transaction, acquire a PostgreSQL transaction advisory lock derived from this keyed fingerprint, then read the injected `Clock` exactly once (truncated to PostgreSQL microsecond precision). Query for the same fingerprint at or after `acceptedAt - 10 minutes`, and use that same `acceptedAt` for consent, message, and outbox timestamps. This makes the window rolling, lets a lock waiter see the preceding commit, and prevents concurrent duplicates without retaining a reusable unkeyed hash of contact content. Exactly ten minutes remains suppressed; ten minutes plus one microsecond is accepted.

The public rate-limit subject uses a separate process-random 256-bit HMAC key and a `public-contact` domain separator. Resolve the raw address only at the controller boundary through `TrustedClientAddressResolver`, hash it immediately, and pass only the 64-character result onward. Never reuse the persistent contact-content dedupe secret for IP subjects.

- [x] **Step 5: Implement the transactional service**

Keep the honeypot check, internal hashed-subject validation, limiter call, normalization, and
content HMAC outside any database transaction. A denied limiter becomes
`CONTACT_RATE_LIMITED` with a bounded positive `retryAfterSeconds` field; the controller copies
that exact safe value to `Retry-After` before rethrowing.

Use `TransactionTemplate` (or a separate proxied writer) with explicit `READ_COMMITTED` for the
single critical section below; do not use a self-invoked `@Transactional` method. Bound both the
overall transaction and PostgreSQL advisory-lock wait, and assert that the lock mapper is never
called without an active transaction:

```text
pg_advisory_xact_lock(first 64 bits of keyed fingerprint)
→ acceptedAt = truncateToMicros(clock.instant())
→ exists(dedupe_key, created_at >= acceptedAt - 10 minutes)
→ insert contact_message with explicit acceptedAt timestamps
→ insert exactly one email_outbox with the same timestamps
→ commit
```

Both inserts must affect exactly one row and use no `ON CONFLICT DO NOTHING`. Store the complete
RFC header value `<portfolio-contact-{messageId}@{validatedAsciiDomain}>` once; Task 3 sends it
unchanged. Any outbox failure escapes the transaction and rolls back the message. JDBC/MyBatis
queries use bound parameters only; a `JdbcClient` mapper is acceptable and needs no XML.

`validateAndNormalize` returns a new immutable command containing only the normalized values; it never tries to mutate the incoming record. The controller ignores the internal `messageId` and serializes only `{ "accepted": true }`.

The endpoint returns `202 Accepted` with only `{ "accepted": true }`; it never returns `messageId` publicly. Add a two-thread PostgreSQL test proving the advisory lock permits only one insert, plus boundary tests proving 10 minutes minus one microsecond and exactly 10 minutes are suppressed while 10 minutes plus one microsecond is accepted. Add a controller test with a populated honeypot plus otherwise-invalid/missing fields and assert the same generic `202` with no rate-limit, message, or outbox write. Every PII-bearing request, command, persistence row, and configuration type must override `toString()` with a redacted representation.

- [x] **Step 6: Add controller tests for validation, privacy, rate limiting, and redaction**

Verify:

- valid input returns `202`;
- acquire the real anonymous CSRF cookie/token, then prove missing, invalid, and mismatched cookie/header requests return `403 CSRF_INVALID` before body, limiter, service, or database work;
- 32,768 raw bytes are accepted for parsing while 32,769 bytes, including a no-length/chunked stream, return `413 PAYLOAD_TOO_LARGE` without consuming quota;
- missing consent, invalid email, and overlong fields return `422 VALIDATION_ERROR`; malformed, unknown, duplicate, or wrong-type JSON returns `400 MALFORMED_REQUEST`;
- rate limit returns `429` plus `Retry-After`;
- the injected limiter fake records exactly `consume("public-contact", hashedSubject)` and never receives a raw IP or any other policy name;
- untrusted peers cannot spoof `X-Real-IP`; only the configured proxy's single valid header affects the hashed subject, while duplicate/invalid headers map to the safe unknown subject;
- logs and response bodies do not contain email or message body;
- the controller does not pass a raw IP into persistence.

- [x] **Step 7: Run tests and commit**

Run the Step 2 command.

Expected: PASS with one message and one outbox row committed atomically.

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/common/error/GlobalProblemHandler.java backend-parent/portfolio-server/src/main/resources/application.yml backend-parent/portfolio-server/src/test/resources/application-test.yml backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message docs/superpowers/plans/2026-07-14-portfolio-05-public-site-seo.md docs/superpowers/plans/2026-07-14-portfolio-06-contact-analytics.md
git commit -m "feat(contact): accept private contact submissions"
```

---

### Task 3: Deliver notification email through a leased outbox

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/email/EmailSenderPort.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/email/ContactNotification.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/email/SmtpEmailSender.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/email/LeasedEmail.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/email/EmailOutboxRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/email/EmailOutboxWorker.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/config/EmailOutboxProperties.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/persistence/EmailOutboxMapper.java`
- Modify: `backend-parent/portfolio-server/pom.xml`
- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/email/EmailOutboxWorkerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/email/EmailOutboxLeaseIntegrationTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/email/EmailOutboxConfigurationTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/email/EmailOutboxSmtpWireTest.java`

**Interfaces:**
- Consumes: `JavaMailSender`, V9 tables, `Clock`, and contact message reads.
- Produces: `EmailSenderPort#send(ContactNotification)` and an independently scheduled outbox worker.

- [x] **Step 1: Write failing retry and lease tests**

```java
@Test
void smtpFailureSchedulesExponentialRetryWithoutChangingMessage() {
    sender.failWith(new MailSendException("mailbox unavailable"));
    UUID outboxId = insertPendingOutbox();

    worker.runOnce();

    EmailOutboxRecord row = outboxMapper.require(outboxId);
    assertThat(row.status()).isEqualTo("FAILED");
    assertThat(row.attempts()).isEqualTo(1);
    assertThat(row.nextAttemptAt()).isEqualTo(now.plusMinutes(1));
    assertThat(row.lastErrorSummary()).doesNotContain("player@example.com");
    assertThat(contactMapper.count()).isEqualTo(1);
}

@Test
void tenthFailureMovesNotificationToDead() {
    UUID outboxId = insertFailedOutboxWithAttempts(9);
    sender.failWith(new MailSendException("still unavailable"));

    worker.runOnce();

    assertThat(outboxMapper.require(outboxId).status()).isEqualTo("DEAD");
}
```

The integration test starts two claimers and asserts each ready row is leased once via `FOR UPDATE SKIP LOCKED`.

- [x] **Step 2: Run focused tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=EmailOutbox*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the outbox worker does not exist.

- [x] **Step 3: Add Spring Mail and typed configuration**

Add `spring-boot-starter-mail` without an explicit version. Bind:

```yaml
portfolio:
  email:
    enabled: ${PORTFOLIO_EMAIL_ENABLED:false}
    from: ${PORTFOLIO_EMAIL_FROM:}
    poll-interval: 10s
    lease-duration: 2m
    batch-size: 10
spring:
  mail:
    host: ${SMTP_HOST:}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME:}
    password: ${SMTP_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
      mail.smtp.starttls.required: true
      mail.smtp.ssl.checkserveridentity: true
      mail.smtp.connectiontimeout: 10000
      mail.smtp.timeout: 10000
      mail.smtp.writetimeout: 10000
```

The worker reuses `portfolio.contact.owner-email` and the already persisted complete
`stable_message_id`; it must not define duplicate owner-address or mail-id-domain settings under
`portfolio.email`.

Production startup fails if email is enabled but required values are blank or transport TLS is disabled. Development may keep delivery disabled while preserving outbox rows, and `management.health.mail.enabled` follows `PORTFOLIO_EMAIL_ENABLED` so a deliberately disabled transport cannot make readiness fail. Enabled delivery also rejects JNDI mail sessions, structured SSL/SSL bundles, trust overrides, custom socket factories, protocol/cipher overrides, and mail debug flags; these alternate paths cannot bypass the validated STARTTLS transport or leak credentials. If the selected provider requires implicit SMTPS instead of STARTTLS, use a separately tested `smtps` profile with certificate validation; never fall back to cleartext SMTP or trust-all TLS.

- [x] **Step 4: Implement atomic lease acquisition**

Use one transaction containing:

```sql
with candidates as (
    select id
    from email_outbox
    where status in ('PENDING','FAILED')
      and next_attempt_at <= #{now}
      and (lease_until is null or lease_until < #{now})
    order by next_attempt_at, created_at
    for update skip locked
    limit #{limit}
)
update email_outbox e
set status = 'SENDING',
    attempts = attempts + 1,
    lease_owner = #{workerId},
    lease_until = #{leaseUntil},
    updated_at = #{now}
from candidates c
where e.id = c.id
returning e.*;
```

Before each claim cycle, recover a bounded batch of expired `SENDING` rows to `FAILED` with `FOR UPDATE SKIP LOCKED`; an expired attempt 10 is terminalized as `DEAD` instead of being stranded. Every claim cycle receives a fresh random `lease_owner` fencing token, and completion/failure updates require outbox ID, that token, the current attempt, and `status='SENDING'`; a delayed sender cannot overwrite a row reclaimed by another worker, including an ABA reclaim by the same process. Renew each row immediately before SMTP so later rows in a batch retain the full lease. Never hold a database transaction open during SMTP I/O.

- [x] **Step 5: Implement sending, stable headers, and retry policy**

```java
public interface EmailSenderPort {
    void send(ContactNotification notification);
}

public record ContactNotification(
    UUID outboxId,
    String stableMessageId,
    String to,
    String replyTo,
    String visitorName,
    String subject,
    String body,
    Instant receivedAt
) {}
```

Set `Message-ID` from `stableMessageId`, set `Reply-To` to the visitor email, target the currently validated `portfolio.contact.owner-email`, render a UTF-8 plain-text body, and use an allowlisted subject prefix such as `[Portfolio Contact]`. Strip CR/LF from all header values. Retry at 1, 5, 15, 60, 240, 720 minutes and then every 24 hours until attempt 10; attempt 10 becomes `DEAD`. Persist only the exception class and a redacted category, not the SMTP server response body. Stable `Message-ID` makes the unavoidable crash-window resend identifiable, but SMTP delivery remains at-least-once rather than physically exactly-once.

- [x] **Step 6: Verify disabled delivery and crash recovery behavior**

Add tests that:

- delivery disabled leaves rows pending without increasing attempts;
- an expired lease is reclaimed;
- a sent row is never selected again;
- a deleted message cascades its unsent outbox;
- the same stable `Message-ID` is used on every retry.

- [x] **Step 7: Run tests and commit**

Run the Step 2 command.

Expected: PASS; SMTP failure changes only outbox state and all leases are recoverable.

```powershell
git add backend-parent/portfolio-server/pom.xml backend-parent/portfolio-server/src/main/resources/application.yml backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message docs/superpowers/plans/2026-07-14-portfolio-06-contact-analytics.md
git commit -m "feat(contact): deliver notifications from email outbox"
```

---

### Task 4: Build the authenticated inbox, retry, and retention APIs

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageInboxService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageInboxRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageCursor.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessagePage.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageSummary.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageDetail.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/EmailDeliveryView.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageStatus.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageRetentionJobHandler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageRetentionRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/JdbcMessageRetentionRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/application/MessageRetentionScheduler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/web/AdminMessageController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/web/AdminMessageStatusBodyReader.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/web/UpdateMessageStatusRequest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/web/AdminMessageControllerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/application/MessageRetentionJobHandlerTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/application/MessageCursorTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/application/MessageInboxServiceIntegrationTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/application/MessageRetentionIntegrationTest.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message/email/EmailOutboxRepository.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditMetadataRedactor.java`

**Interfaces:**
- Consumes: authenticated admin/CSRF rules, `CurrentAdminProvider`, `AuditService`, plan-02 `JobHandler`, and cursor pagination convention.
- Produces: inbox list/detail/status/delete/retry APIs consumed by plan 04.

- [x] **Step 1: Write failing inbox and optimistic-lock tests**

Cover the exact API:

```text
GET    /api/admin/messages?status=UNREAD&cursor={opaque}&limit=30
GET    /api/admin/messages/{id}
PATCH  /api/admin/messages/{id}/status
POST   /api/admin/messages/{id}/email/retry
DELETE /api/admin/messages/{id}
```

The PATCH body is:

```json
{ "status": "READ", "version": 0 }
```

Verify unauthenticated requests return `401`, missing CSRF returns `403`, a stale version returns `409`, and list responses never contain the message body.

- [x] **Step 2: Run focused tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=AdminMessageControllerTest,MessageRetentionJobHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because admin inbox use cases are absent.

- [x] **Step 3: Implement cursor pagination and status transitions**

Encode `(createdAt,id)` as an opaque base64url JSON cursor. Clamp limit to 1–100. Allow transitions among `UNREAD`, `READ`, `ARCHIVED`, and `SPAM`; execute `update contact_message set status = #{status}, version = version + 1, updated_at = #{now} where id = #{id} and version = #{expectedVersion}`, and throw `409 MESSAGE_VERSION_CONFLICT` when zero rows update.

Return:

```java
public record MessageSummary(
    UUID id, String visitorName, String visitorEmail, String subject,
    MessageStatus status, String emailStatus, Instant createdAt, int version
) {}

public record MessageDetail(
    UUID id, String visitorName, String visitorEmail, String subject, String body,
    MessageStatus status, EmailDeliveryView email, Instant privacyAcceptedAt,
    Instant createdAt, Instant updatedAt, int version
) {}

public record EmailDeliveryView(
    String status,
    int attempts,
    Instant nextAttemptAt,
    Instant sentAt,
    Instant updatedAt,
    String errorCategory
) {}
```

`EmailDeliveryView.status` is one of the V9 outbox statuses. `errorCategory` is the existing sanitized category or `null`; it never contains an SMTP response, recipient, server hostname, exception message, or stack trace. These DTOs are admin-only. Every inbox response uses `Cache-Control: no-store`; escape content in the Vue renderer and never render message text as HTML.

- [x] **Step 4: Implement manual email retry and hard deletion**

Manual retry changes `FAILED` or `DEAD` to `PENDING`, clears the redacted error, resets `next_attempt_at` to now, and leaves attempt history intact. Reject retry for `SENT` or `SENDING` with `409`. Delete the message and its outbox rows in one transaction. Status changes, manual retry, and deletion each write an audit event containing only message UUID, previous/new state where applicable, creation date, and action—no visitor PII.

- [x] **Step 5: Add the one-year retention job**

Implement `MessageRetentionJobHandler` with job type `CONTACT_RETENTION` and idempotency key `contact-retention:{siteDate}`. Delete in batches of 500 where `created_at < now - 1 year`; cascade outbox rows; report only counts to `maintenance_run`. Schedule one job per local day through `BackgroundJobService`.

- [x] **Step 6: Run tests and commit**

Run the Step 2 command.

Expected: PASS; stale writes are rejected, manual retry is safe, and expired PII is removed.

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/message backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message
git commit -m "feat(contact): add inbox and retention controls"
```

---

### Task 5: Collect allowlisted opt-in analytics without identity data

**Files:**
- Create: `backend-parent/portfolio-server/src/main/resources/analytics/analytics-rules-v1.yml`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/domain/AnalyticsEventType.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/domain/DeviceClass.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/CollectAnalyticsCommand.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsCollector.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsPrivacyService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsRules.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsEventDeduplicator.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/config/AnalyticsProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/config/AnalyticsProductionConfigurationValidator.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/persistence/AnalyticsEventRecord.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/persistence/AnalyticsEventMapper.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/web/AnalyticsRateLimitSubjectHasher.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/web/PublicAnalyticsBodyReader.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/web/PublicAnalyticsController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/web/PublicAnalyticsBatchRequest.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/web/PublicAnalyticsEventRequest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsCollectorTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsDeduplicationIntegrationTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsPrivacyServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsRulesTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/config/AnalyticsPropertiesTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/web/AnalyticsRateLimitSubjectHasherTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/web/PublicAnalyticsBodyReaderTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/web/PublicAnalyticsControllerTest.java`
- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Modify: `backend-parent/portfolio-server/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: plan-01 CSRF enforcement, `RateLimiter#consume("public-events", subject)`, `CurrentPublicationQuery#isCurrentPublishedProject(UUID)`, request headers in memory, and a base64 HMAC secret.
- Produces: `POST /api/public/events`, returning `204 No Content` for accepted or consent-suppressed batches.

- [x] **Step 1: Write failing privacy and allowlist tests**

```java
@Test
void storesDailyKeysAndCoarseDeviceButNoRequestIdentity() {
    collector.collect(validPageView(
        "visitor-random-128-bit", "session-random-128-bit",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)",
        "203.0.113.9"));

    AnalyticsEventRecord row = mapper.findAll().getFirst();
    assertThat(row.visitorDayKey()).hasSize(64).doesNotContain("visitor-random");
    assertThat(row.sessionDayKey()).hasSize(64).doesNotContain("session-random");
    assertThat(row.deviceClass()).isEqualTo(DeviceClass.MOBILE);
    assertThat(allColumnNames()).doesNotContain("ip", "ip_hash", "user_agent", "visitor_id", "session_id");
}

@Test
void rejectsUnpublishedProjectAndUnknownEventProperties() {
    assertThatThrownBy(() -> collector.collect(projectViewFor(unpublishedProjectId)))
        .isInstanceOfSatisfying(DomainException.class,
            error -> assertThat(error.code()).isEqualTo("ANALYTICS_EVENT_INVALID"));
}
```

- [x] **Step 2: Run focused tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=AnalyticsCollectorTest,AnalyticsDeduplicationIntegrationTest,PublicAnalyticsControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because collection has not been implemented.

- [x] **Step 3: Define a bounded batch request**

```java
public record PublicAnalyticsBatchRequest(
    boolean analyticsConsent,
    @Size(max = 64) String visitorId,
    @Size(max = 64) String sessionId,
    @Size(max = 20) List<PublicAnalyticsEventRequest> events
) {}

public record PublicAnalyticsEventRequest(
    @NotNull UUID eventId,
    @NotNull AnalyticsEventType type,
    @NotBlank @Size(max = 200) String pageKey,
    UUID projectId,
    @Size(max = 2048) String referrer,
    @NotNull LocaleCode locale
) {}
```

Reject unknown JSON fields and all extra event properties. The controller deliberately does not run eager bean validation on this outer record: it checks `DNT` and `analyticsConsent` first and returns `204`, then the collector validates nonblank identifiers, a nonempty batch, every nested field, and the 22–64 character identifier bounds for consented requests. This ordering lets a no-consent request contain no identifier. Referrer is the only accepted URL-shaped field and is reduced to a hostname before persistence; do not accept arbitrary labels, page query strings, user text, revenue, screen dimensions, or browser metadata.

- [x] **Step 4: Add versioned rules and normalize fields**

```yaml
version: analytics-rules-v1
eventTypes:
  PAGE_VIEW: { projectRequired: false }
  PROJECT_VIEW: { projectRequired: true }
  RESUME_DOWNLOAD: { projectRequired: false }
  DEMO_DOWNLOAD: { projectRequired: true }
  OUTBOUND_CLICK: { projectRequired: false }
pageKeys:
  - HOME
  - ABOUT
  - WORK
  - ROADMAP
  - CONTACT
  - PRIVACY
  - PROJECT_DETAIL
crawlerTokens:
  - bot
  - crawler
  - spider
  - preview
referrerDomains:
  - baidu.com
  - bing.com
  - bilibili.com
  - csdn.net
  - discord.com
  - duckduckgo.com
  - gitee.com
  - github.com
  - google.com
  - juejin.cn
  - linkedin.com
  - reddit.com
  - t.co
  - x.com
  - youtube.com
  - zhihu.com
```

Normalize referrer to lowercase ASCII hostname only, map empty/same-site referrers to `(direct)`, classify the in-memory User-Agent to `DESKTOP`, `MOBILE`, `TABLET`, or `OTHER`, and discard known crawlers before persistence. Page keys must come from the file; project events require a currently published project ID.
Reduce allowlisted external subdomains to their configured root domain and map every other valid external hostname to `(none)`, so attacker-controlled subdomains cannot become permanent analytics dimensions.

- [x] **Step 5: Derive daily privacy keys**

```java
LocalDate siteDate = receivedAt.atZone(ZoneId.of("Asia/Hong_Kong")).toLocalDate();
String visitorDayKey = hex(hmac(secret, siteDate + "\nvisitor\n" + request.visitorId()));
String sessionDayKey = hex(hmac(secret, siteDate + "\nsession\n" + request.sessionId()));
```

Validate browser IDs as base64url-encoded 128–256 bit random values before HMAC. The raw IDs exist only in method-local values and are never logged. Implement `ContactProperties` and `AnalyticsProperties` as final classes with redacted `toString()` methods; do not use record/Lombok string generation for HMAC secrets or the owner email. Production startup validates decoded secret entropy is at least 256 bits.

- [x] **Step 6: Enforce exact ten-second duplicate suppression under concurrency**

For each normalized `(sessionDayKey,eventType,pageKey,projectId)` tuple, acquire a PostgreSQL transaction advisory lock derived from a keyed 64-bit hash. Then query the newest row for the tuple and skip insertion when `received_at >= currentReceivedAt - interval '10 seconds'`. Keep the lock and query in the same short transaction. `client_event_id` also prevents network retries from inserting twice.

The integration test launches two transactions for the same tuple and proves that only one event persists. Add a boundary test at 9.999 seconds (one row) and 10.001 seconds (two rows).

- [x] **Step 7: Implement endpoint privacy behavior and rate limit**

If `analyticsConsent` is false, return `204` without validating identifiers or persisting. Otherwise call `rateLimiter.consume("public-events", hashedSubject)`, return `429` plus `Retry-After` when denied, ignore known crawlers, accept at most 20 events/32 KiB, use server receipt time for day and dedupe decisions, and return `204`. `DNT` behavior remains a browser obligation in plan 05; if a request nevertheless carries `DNT: 1`, also return `204` without persistence as defense in depth.

Controller tests send CSRF for all consent/no-consent/DNT application-path cases and separately prove that a missing/invalid token returns `403 CSRF_INVALID` with zero rows. The no-consent and DNT short-circuits occur after the security filter but before identifier validation or rate-limit consumption. For a consented request, the injected limiter fake records exactly `consume("public-events", hashedSubject)` and never receives a raw IP or any other policy name.

Do not expose this route in production until Task 6 aggregation and 30-day raw-event retention are enabled. The production reverse proxy must also disable access logging for `/api/public/events` or use a dedicated format that omits client IP, User-Agent, Referrer, cookies, and query strings.

- [x] **Step 8: Run tests and commit**

Run the Step 2 command.

Expected: PASS; no forbidden identity data exists in the schema or record, and concurrent duplicates collapse to one event.

```powershell
git add backend-parent/portfolio-server/src/main/resources/analytics backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics
git commit -m "feat(analytics): collect opt-in private events"
```

---

### Task 6: Aggregate daily metrics and purge raw events

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsAggregationService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsAggregationJobHandler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsRetentionJobHandler.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/persistence/AnalyticsDailyRecord.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/persistence/AnalyticsDailyMapper.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsAggregationServiceTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsRetentionJobHandlerTest.java`

**Interfaces:**
- Consumes: plan-02 `BackgroundJobService`, `JobHandler`, `maintenance_run`, and V10 events.
- Produces: reproducible `analytics_daily` rows and daily job types `ANALYTICS_AGGREGATE` and `ANALYTICS_RETENTION`.

- [x] **Step 1: Write a fixed-fixture aggregation test**

Insert a fixture spanning `2026-07-14` Hong Kong time with:

- three page views from two visitor-day keys;
- two project views for one project from two visitors;
- one repeated event already suppressed by Task 5;
- one resume download, one demo download, and one outbound click;
- two locales, two device classes, and a direct plus external referrer.

Assert exact rows for ALL, PAGE, PROJECT, REFERRER, DEVICE, and LOCALE dimensions, including `PV=3`, `DAILY_UV=2` for all page views, and each event count.

- [x] **Step 2: Run focused tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=AnalyticsAggregationServiceTest,AnalyticsRetentionJobHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because no aggregates or retention handlers exist.

- [x] **Step 3: Implement deterministic rebuild-and-upsert**

Within one transaction for a requested `siteDate`:

1. take a transaction advisory lock for that date and `aggregationVersion`;
2. delete existing rows for the date;
3. insert `PV` for `PAGE_VIEW` counts;
4. insert `DAILY_UV` using `count(distinct visitor_day_key)`;
5. insert `EVENT_COUNT` for every event type;
6. generate ALL with `(all)` and each applicable dimension with `(none)` for absent optional values;
7. record a successful or failed `maintenance_run` with counts only.

The job idempotency key is `analytics-aggregate:{siteDate}:analytics-rules-v1`. Re-running produces byte-equivalent result rows apart from `updated_at`.

Implementation note: the rebuild uses one materialized SQL snapshot after stable date/version advisory locks. The queued payload carries the exact aggregation version so a rules deployment cannot silently execute an old key with new semantics.

- [x] **Step 4: Schedule late-arrival-safe daily work**

At 00:15 `Asia/Hong_Kong`, enqueue aggregation for the previous day and the current day. Every hour, enqueue the current day again with an hour-suffixed job idempotency key so the dashboard catches late events. The aggregation itself remains date-idempotent.

Implementation note: the daily previous-day job and hourly current-day job both run at minute 15 without sharing a key. At minute 45, a bounded recent-window scan enqueues the first incomplete date with an independent hour-scoped repair key, so a dead original job cannot permanently strand a date.

- [x] **Step 5: Implement 30-day event retention**

The daily `ANALYTICS_RETENTION` handler first verifies that every date being purged has aggregate rows, then deletes events in batches of 5,000 where `received_at < now - 30 days`. Do not delete aggregate rows. Record only deleted row count and cutoff in `maintenance_run`.

Implementation note: V11 writes an immutable checkpoint under the same date lock before the first raw deletion and compares the full expected dimension rowset against the stored aggregates. Runtime has no direct raw-delete capability; a scoped database function enforces the database-clock 30-day boundary and the 5,000-row limit. Database triggers reject uncheckpointed raw deletion, late raw insertion, and aggregate mutation after retention starts. A missing expired aggregate may be rebuilt only while no checkpoint exists. Each leased job handles at most ten 5,000-row batches; its final batch atomically creates an attempt-fenced idempotent successor when more rows remain.

- [x] **Step 6: Run tests and commit**

Run the Step 2 command twice.

Expected: both runs PASS and the second aggregation leaves the same metric keys and counts.

Verification note: the focused command passed twice with 31 tests each. A clean full Maven verify passed 1,888 tests with zero failures and zero errors; an independent concurrency, recovery, and retention review found no remaining P1/P2 findings.

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics
git commit -m "feat(analytics): aggregate and retain private metrics"
```

---

### Task 7: Expose admin analytics reports with explicit definitions

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsReportService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsQuery.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsSummary.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsPoint.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/application/AnalyticsBreakdownItem.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics/web/AdminAnalyticsController.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/web/AdminAnalyticsControllerTest.java`

**Interfaces:**
- Consumes: current admin authentication and V10 aggregate rows.
- Produces: summary, time-series, and ranked breakdown APIs consumed by plan 04.

- [ ] **Step 1: Write failing report API tests**

Test these exact endpoints:

```text
GET /api/admin/analytics/summary?from=2026-07-01&to=2026-07-14&locale=zh-CN&zone=Asia%2FHong_Kong
GET /api/admin/analytics/timeseries?from=2026-07-01&to=2026-07-14&metric=PV&eventType=PAGE_VIEW&zone=Asia%2FHong_Kong
GET /api/admin/analytics/breakdown?from=2026-07-01&to=2026-07-14&metric=EVENT_COUNT&eventType=PROJECT_VIEW&dimension=PROJECT&limit=10&zone=Asia%2FHong_Kong
```

Assert auth, date validation, max 366-day range, limit 1–100, zero-filled missing days, stable tie ordering by `dimensionValue`, and that the response declares data delay and metric definitions. Accept only `zone=Asia/Hong_Kong`; reject other zones with `400 ANALYTICS_ZONE_UNSUPPORTED` rather than relabeling fixed site-day aggregates.

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest=AdminAnalyticsControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because admin report endpoints are absent.

- [ ] **Step 3: Implement report DTOs and queries**

```java
public record AnalyticsSummary(
    long pageViews,
    long dailyUniqueVisitors,
    long projectViews,
    long resumeDownloads,
    long demoDownloads,
    long outboundClicks,
    Instant dataCompleteThrough,
    String zone,
    Map<String, String> definitions
) {}

public record AnalyticsPoint(LocalDate date, long value) {}

public record AnalyticsBreakdownItem(String dimensionValue, long value) {}
```

`dailyUniqueVisitors` across a range is the sum of each site's daily UV, not a cross-day unique-person count. Return the definition text explicitly in both languages through stable message keys. Resolve project dimension UUIDs to current or archived project titles through a read-only plan-03 query, but retain the UUID when a title no longer exists.

- [ ] **Step 4: Apply response security and cache rules**

Admin analytics responses use `Cache-Control: no-store`, never include raw events, visitor-day keys, session-day keys, or contact data, and require CSRF only for mutations (these endpoints are GET). Audit only export actions if CSV export is added in a later scope; do not add export now.

- [ ] **Step 5: Run tests and commit**

Run the Step 2 command.

Expected: PASS with exact fixture totals and zero-filled dates.

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/analytics backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics
git commit -m "feat(analytics): expose admin metric reports"
```

---

### Task 8: Verify the whole contact and analytics slice and document operations

**Files:**
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message/ContactEmailJourneyIntegrationTest.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics/AnalyticsPrivacyContractTest.java`
- Create: `docs/operations/contact-analytics.md`
- Modify: `deploy/.env.example`

**Interfaces:**
- Consumes: all tasks in this plan plus the plan-04/05 browser journeys.
- Produces: an executable server-side acceptance suite and production operating instructions.

- [ ] **Step 1: Add a contact journey integration test**

The test submits a public message, observes immediate `202`, confirms one unread inbox item, forces mail failure, confirms the message remains, manually retries, confirms sent status, changes to archived with optimistic version, and deletes the message plus PII. Assert every authenticated administrator mutation creates a redacted audit event; the public response and logs contain no submission identifier or visitor value.

- [ ] **Step 2: Add an analytics privacy contract test**

The test submits no-consent and `DNT: 1` requests and asserts zero rows; submits consented fixed events; verifies exact dedupe, Hong Kong date boundary, aggregate totals, report definitions, and 30-day purge. Query `information_schema.columns` and structured captured logs to prove no forbidden identity field or raw identifier is present.

- [ ] **Step 3: Run the complete phase test set**

```powershell
.\mvnw.cmd -pl portfolio-server -am -Dtest='Contact*Test,EmailOutbox*Test,AdminMessageControllerTest,MessageRetentionJobHandlerTest,Analytics*Test,PublicAnalyticsControllerTest,AdminAnalyticsControllerTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; Testcontainers starts PostgreSQL 17, mail failure does not remove a message, and analytics privacy/aggregation checks are green.

- [ ] **Step 4: Document configuration and operating definitions**

Document:

- SMTP environment names, disabled mode, stable `Message-ID`, lease recovery, retry schedule, manual retry, and dead-row triage;
- contact validation, ten-minute dedupe, one-year retention, manual PII deletion, and redacted logging;
- opt-in and `DNT` behavior, browser identifier rotation, HMAC secret rotation consequences, event allowlist, 10-second dedupe, crawler filter version, `Asia/Hong_Kong` day, and 30-day raw retention;
- PV, summed daily UV, event-count definitions, expected aggregation delay, replay procedure, and maintenance-run inspection;
- the fact that analytics is not a cross-device or long-lived people counter.

Add only names and safe defaults to `.env.example`:

```dotenv
PORTFOLIO_EMAIL_ENABLED=false
PORTFOLIO_EMAIL_FROM=
PORTFOLIO_OWNER_EMAIL=
PORTFOLIO_MAIL_ID_DOMAIN=yychainsaw.xyz
SMTP_HOST=
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
PORTFOLIO_CONTACT_DEDUPE_SECRET=
PORTFOLIO_ANALYTICS_HMAC_SECRET=
```

- [ ] **Step 5: Scan for leaked values and commit**

```powershell
git grep -n -E 'player@example\.com|203\.0\.113\.9|SMTP_PASSWORD=.+|ANALYTICS_HMAC_SECRET=.+' -- . ':!**/src/test/**' ':!docs/superpowers/plans/*'
```

Expected: no production/config match; the excluded test fixtures may use reserved example values only in test sources.

```powershell
git add backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/message backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/analytics docs/operations/contact-analytics.md deploy/.env.example
git commit -m "test(contact): verify contact and analytics privacy"
```

---

## Phase Completion Gate

- [ ] `POST /api/public/contact` returns before SMTP and commits message plus outbox atomically.
- [ ] SMTP failure preserves the message, retry is leased and bounded, and the admin can retry dead mail.
- [ ] Inbox list/detail/status/archive/spam/delete behavior is authenticated, CSRF-protected for mutations, versioned, audited, and redacted.
- [ ] One-year message retention and manual early deletion remove visitor PII.
- [ ] No-consent and `DNT=1` produce no identifier and no event in the browser journey and no row server-side.
- [ ] Analytics rows contain daily HMAC keys but no IP, IP hash, raw browser/session ID, or full User-Agent.
- [ ] Ten-second duplicate suppression is correct across concurrent requests and time boundaries.
- [ ] PV, summed daily UV, referrals, project views, downloads, and outbound clicks match fixed fixtures in `Asia/Hong_Kong`.
- [ ] Raw events older than 30 days are removed only after aggregates exist.
- [ ] Admin reports state definitions and data delay and never expose raw-event identity keys.
- [ ] Operations documentation contains no secret values and explains retry, retention, and recovery.
