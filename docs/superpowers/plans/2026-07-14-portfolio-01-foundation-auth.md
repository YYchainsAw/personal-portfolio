# Portfolio Foundation and Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a reproducible Java/PostgreSQL foundation and a production-shaped, single-admin authentication boundary with Argon2id, password-plus-TOTP login, one-time recovery codes, PostgreSQL-backed sessions, CSRF, immutable audit records, and local bootstrap/recovery CLIs.

**Architecture:** Build a Maven modular monolith with `portfolio-pojo`, `portfolio-common`, and `portfolio-server`; keep public contracts and infrastructure primitives in the first two modules while authentication, audit persistence, session enforcement, and CLI orchestration live in feature packages inside `portfolio-server`. PostgreSQL 17 is the only state store: Flyway owns all schema creation, Spring Session JDBC owns session row behavior but never schema initialization or cleanup scheduling, and the application links durable session metadata to Spring Session's stable `PRIMARY_ID` before final authentication rotates the public session ID.

**Tech Stack:** Java 17; Spring Boot 3.5.7; Maven Wrapper 3.9.11; Spring Security and Spring Session JDBC from the Spring Boot BOM; MyBatis-Plus 3.5.7; PostgreSQL 17; Flyway from the Spring Boot BOM; Jackson; Spring Boot Test; Spring Security Test; Testcontainers PostgreSQL.

## Global Constraints

- Use Maven coordinates `xyz.yychainsaw:portfolio-backend-parent:1.0.0-SNAPSHOT` and Java package root `xyz.yychainsaw.portfolio`; do not introduce the reference project's `com.yychainsaw` packages.
- Java is exactly 17, Spring Boot is exactly 3.5.7, Maven Wrapper is exactly 3.9.11, MyBatis-Plus is exactly 3.5.7, Springdoc is exactly 2.8.5, and PostgreSQL uses the Docker major tag `17-bookworm`.
- This plan creates exactly three Maven modules: `portfolio-pojo`, `portfolio-common`, and `portfolio-server`; domain code in the server is package-by-feature, not global controller/service/mapper folders.
- Migration numbering is reserved: this plan creates only `V1__extensions_and_core.sql` and `V2__authentication_and_audit.sql`; plan 02 owns V3, and plan 03 starts at V4.
- Flyway uses a dedicated migration account. The runtime account has no DDL permission and cannot update/delete `audit_log`.
- PostgreSQL primary keys are UUID, persistent timestamps are `timestamptz`, and application/test clocks use UTC.
- Phase one has no Redis, message queue, Elasticsearch, Kubernetes, microservices, JWT, public registration, email password reset, or multi-role RBAC.
- Authentication uses Spring Security, Argon2id, two stages (password then TOTP or one-time recovery code), PostgreSQL server-side sessions, and CSRF; do not copy MD5, hard-coded JWT, custom login interceptors, broad CORS, or Fastjson 1.x from QingLian.
- Every unsafe admin or public HTTP request carries Spring Security's cookie-backed CSRF token. Anonymous clients obtain it from the permit-all, `Cache-Control: no-store` `GET /api/admin/auth/csrf`; contact/events are not exempted merely because they are public.
- Session cookies are `HttpOnly`, `Secure` in production, and `SameSite=Strict`; idle timeout is 30 minutes and absolute lifetime is 8 hours.
- TOTP secrets use AES-256-GCM with a versioned key ring supplied only through environment variables. Recovery codes are shown once and stored only as Argon2id hashes.
- No password, TOTP secret, recovery code, database password, encryption key, session token, raw IP, or complete User-Agent may enter Git, command-line arguments, audit metadata, application logs, exception responses, or test snapshots.
- CORS is disabled for production because admin and API are same-origin; the development allow-list is explicit and never uses `*` with credentials.
- Spring Session's official JDBC tables are copied into Flyway V2. Set `spring.session.jdbc.initialize-schema=never` and `spring.session.jdbc.cleanup-cron=-`; only the application-owned cleanup job expires and deletes sessions.
- `admin_session_metadata.session_primary_id` is nullable, unique, references `spring_session.primary_id`, and uses `ON DELETE SET NULL`; active-session access time is read from `spring_session`, while terminal metadata and reasons remain durable.
- API errors use Spring `ProblemDetail` plus stable `code`, `traceId`, and `fieldErrors`; clients never receive stack traces, SQL, filesystem paths, or internal exception messages.
- The reusable cross-plan contracts are exactly:
  - `xyz.yychainsaw.portfolio.audit.AuditService#record(AuditCommand)`
  - `xyz.yychainsaw.portfolio.auth.CurrentAdminProvider#requireAdminId(): UUID`
  - `xyz.yychainsaw.portfolio.common.error.DomainException(String code, HttpStatus status, Map<String,String> fieldErrors)`
  - `xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter#consume(String policy, String subject): RateLimitDecision`
- The in-memory rate limiter is bounded to 10,000 subjects, contains no raw identifiers, and is suitable for the single-process phase-one deployment. Policy `admin-login` is five attempts per 15 minutes.
- Authenticated password, TOTP, and recovery-code changes require current password plus current TOTP, policy `admin-security`, CSRF, no-store responses, immutable audit, and atomic revocation of every session except the current one. A candidate TOTP is encrypted in the current session for exactly ten minutes.
- Audit reads use descending `(created_at,id)` keyset pagination, limit 1–100, authenticated no-store responses, and an explicit metadata allowlist. Plan 04 consumes `AdminAuditItem` and `AdminAuditPage` from `portfolio-pojo` without reading raw audit JSON.
- Docker 26 on Ubuntu 22.04 is the production target. Development PostgreSQL may bind only to `127.0.0.1`; production PostgreSQL must have no published host port.
- Every behavior change follows red-green-refactor, every task ends in a focused commit, and `git diff --check` plus the relevant tests must pass before that commit.
- The planning handoff commits this plan file before execution starts. If it is still untracked, commit only the planning document first; never sweep it or unrelated user files into the frontend baseline commit.

---

## File and Interface Map

| Path | Responsibility |
|---|---|
| `.gitignore` | Exclude worktrees, runtime secrets, recovery dumps, and generated frontend bundles without deleting user files. |
| `.env.example` | Name every local environment variable with empty secret values and exact generation commands in comments. |
| `backend-parent/pom.xml` | Parent/BOM, module list, fixed versions, enforcer rules, and plugin management. |
| `backend-parent/.mvn/wrapper/*`, `backend-parent/mvnw*` | Maven 3.9.11 reproducible entrypoint. |
| `backend-parent/portfolio-pojo` | Future cross-boundary DTO/VO module; this slice establishes its build boundary only. |
| `backend-parent/portfolio-common` | `DomainException`, trace support, and rate-limit contracts. |
| `backend-parent/portfolio-server` | Boot application plus `audit`, `auth`, `common.error`, and configuration packages. |
| `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/audit/` | Redacted `AdminAuditItem` and `AdminAuditPage` contracts consumed by the admin UI in Plan 04. |
| `deploy/compose.dev.yml` | PostgreSQL 17 development service bound to loopback. |
| `deploy/postgres/init/01-create-roles.sh` | Create separate migrator/runtime roles without embedding credentials. |
| `V1__extensions_and_core.sql` | PostgreSQL extension and shared timestamp trigger function. |
| `V2__authentication_and_audit.sql` | Official Spring Session tables, admin, recovery-code, durable session metadata, and immutable audit tables. |
| `xyz.yychainsaw.portfolio.common.error.DomainException` | Stable domain failure carrying `code`, `HttpStatus`, and field errors. |
| `xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter` | Cross-plan fixed-window consumption contract. |
| `xyz.yychainsaw.portfolio.audit.AuditService` | Cross-plan immutable audit write contract. |
| `xyz.yychainsaw.portfolio.auth.CurrentAdminProvider` | Cross-plan authenticated-admin lookup contract. |
| `/api/admin/security/password`, `/totp/enrollment`, `/totp/confirm`, `/recovery-codes/regenerate` | Reauthenticated credential maintenance that retains only the current session. |
| `GET /api/admin/audit` | Authenticated, filtered, opaque-cursor audit page with allowlisted metadata. |
| `docs/runbooks/admin-access.md` | Exact bootstrap, login/session, recovery, key-rotation, and secret-handling runbook. |

### Cross-plan Java contracts

```java
package xyz.yychainsaw.portfolio.common.error;

public final class DomainException extends RuntimeException {
    private final String code;
    private final org.springframework.http.HttpStatus status;
    private final java.util.Map<String, String> fieldErrors;

    public DomainException(
            String code,
            org.springframework.http.HttpStatus status,
            java.util.Map<String, String> fieldErrors) {
        super(java.util.Objects.requireNonNull(code, "code"));
        this.code = code;
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.fieldErrors = java.util.Map.copyOf(
                java.util.Objects.requireNonNull(fieldErrors, "fieldErrors"));
    }

    public String code() { return code; }
    public org.springframework.http.HttpStatus status() { return status; }
    public java.util.Map<String, String> fieldErrors() { return fieldErrors; }
}
```

```java
package xyz.yychainsaw.portfolio.common.ratelimit;

public interface RateLimiter {
    RateLimitDecision consume(String policy, String subject);
}

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {}
```

```java
package xyz.yychainsaw.portfolio.audit;

public interface AuditService {
    void record(AuditCommand command);
}
```

```java
package xyz.yychainsaw.portfolio.auth;

public interface CurrentAdminProvider {
    java.util.UUID requireAdminId();
}
```

---

### Task 1: Establish a reviewable Git baseline and isolated implementation worktree

**Files:**
- Modify: `.gitignore`
- Verify: `frontend/package.json`
- Verify: `docs/superpowers/specs/2026-07-14-portfolio-full-backend-design.md`

**Interfaces:**
- Consumes: The approved design commit and the current untracked `frontend/` tree.
- Produces: A clean baseline commit containing the existing frontend source, followed by branch `feature/portfolio-foundation-auth` in an isolated sibling worktree.

- [ ] **Step 1: Record the pre-existing state before staging anything**

Run from the primary checkout:

```powershell
git status --short --branch
git ls-files
git log -3 --oneline
```

Expected before baseline preparation: branch `main`; `.gitignore`, the approved design, and this implementation plan are tracked; `frontend/` is reported as untracked. If this plan is still untracked, make the planning-only commit described in Global Constraints before continuing. If any tracked file is modified, stop and ask its owner before staging it.

- [ ] **Step 2: Prove the existing frontend baseline builds**

```powershell
Push-Location frontend
npm ci
npm run type-check
npm run build
Pop-Location
```

Expected: all three commands exit `0`; Vite writes only ignored `frontend/dist/` output.

- [ ] **Step 3: Add exact ignore rules for implementation-local state**

Append these lines to `.gitignore`:

```gitignore

#Isolated implementation worktrees and generated review bundles
/.worktrees/
/frontend/portfolio-dist.zip
/frontend/artifacts/

#Recovery CLI output (database dumps remain outside Git)
/runtime/recovery/
```

Do not delete the currently present ZIP or screenshots; the ignore rules preserve them locally while preventing accidental baseline staging.

- [ ] **Step 4: Stage only the reproducible frontend source and inspect it**

```powershell
git add .gitignore frontend
git status --short
git diff --cached --check
git diff --cached --name-only | Select-String -Pattern '(\.env($|\.)|\.pem$|\.key$|portfolio-dist\.zip|^frontend/artifacts/)'
```

Expected: source/config/assets under `frontend/` are staged; the final secret/generated-file scan prints no matches; `git diff --cached --check` exits `0`.

- [ ] **Step 5: Commit the existing application baseline separately from backend work**

```powershell
git commit -m "chore: establish portfolio frontend baseline"
git status --short
```

Expected: commit succeeds and the worktree is clean (ignored local artifacts do not appear).

- [ ] **Step 6: Create the isolated feature worktree using the required worktree skill**

Invoke `superpowers:using-git-worktrees`, then create the sibling worktree:

```powershell
git worktree add ..\personal-portfolio-foundation-auth -b feature/portfolio-foundation-auth
Set-Location ..\personal-portfolio-foundation-auth
git status --short --branch
```

Expected: branch is `feature/portfolio-foundation-auth` and status is clean. All remaining steps run in this worktree.

---

### Task 2: Create the Maven 3.9.11 three-module build

**Files:**
- Create: `backend-parent/pom.xml`
- Create: `backend-parent/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend-parent/mvnw`
- Create: `backend-parent/mvnw.cmd`
- Create: `backend-parent/portfolio-pojo/pom.xml`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/pojo/package-info.java`
- Create: `backend-parent/portfolio-common/pom.xml`
- Create: `backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/package-info.java`
- Create: `backend-parent/portfolio-server/pom.xml`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/PortfolioApplication.java`

**Interfaces:**
- Consumes: Clean isolated Git worktree from Task 1.
- Produces: `./mvnw -f backend-parent/pom.xml` build entrypoint and the package root `xyz.yychainsaw.portfolio` used by every later task.

- [ ] **Step 1: Verify that the backend build does not exist yet**

```powershell
Test-Path backend-parent\pom.xml
Test-Path backend-parent\mvnw.cmd
```

Expected: both values are `False`.

- [ ] **Step 2: Create the parent POM with fixed versions and banned reference-stack dependencies**

Create `backend-parent/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.7</version>
    <relativePath/>
  </parent>

  <groupId>xyz.yychainsaw</groupId>
  <artifactId>portfolio-backend-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>portfolio-pojo</module>
    <module>portfolio-common</module>
    <module>portfolio-server</module>
  </modules>

  <properties>
    <java.version>17</java.version>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <mybatis-plus.version>3.5.7</mybatis-plus.version>
    <springdoc.version>2.8.5</springdoc.version>
    <tencent-cos.version>5.6.227</tencent-cos.version>
    <totp.version>1.7.1</totp.version>
    <maven-enforcer.version>3.5.0</maven-enforcer.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>xyz.yychainsaw</groupId>
        <artifactId>portfolio-pojo</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>xyz.yychainsaw</groupId>
        <artifactId>portfolio-common</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        <version>${mybatis-plus.version}</version>
      </dependency>
      <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>${springdoc.version}</version>
      </dependency>
      <dependency>
        <groupId>com.qcloud</groupId>
        <artifactId>cos_api</artifactId>
        <version>${tencent-cos.version}</version>
      </dependency>
      <dependency>
        <groupId>dev.samstevens.totp</groupId>
        <artifactId>totp</artifactId>
        <version>${totp.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>${maven-enforcer.version}</version>
        <executions>
          <execution>
            <id>enforce-build-and-dependencies</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion><version>[17,18)</version></requireJavaVersion>
                <requireMavenVersion><version>[3.9.11,3.10)</version></requireMavenVersion>
                <bannedDependencies>
                  <excludes>
                    <exclude>com.alibaba:fastjson</exclude>
                    <exclude>com.auth0:java-jwt</exclude>
                  </excludes>
                  <searchTransitive>true</searchTransitive>
                </bannedDependencies>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Create the two boundary-module POMs and package markers**

Create `backend-parent/portfolio-pojo/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>xyz.yychainsaw</groupId><artifactId>portfolio-backend-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath>
  </parent>
  <artifactId>portfolio-pojo</artifactId>
  <dependencies>
    <dependency><groupId>jakarta.validation</groupId><artifactId>jakarta.validation-api</artifactId></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-annotations</artifactId></dependency>
  </dependencies>
</project>
```

Create `backend-parent/portfolio-common/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>xyz.yychainsaw</groupId><artifactId>portfolio-backend-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath>
  </parent>
  <artifactId>portfolio-common</artifactId>
  <dependencies>
    <dependency><groupId>org.springframework</groupId><artifactId>spring-web</artifactId></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
    <dependency><groupId>jakarta.servlet</groupId><artifactId>jakarta.servlet-api</artifactId><scope>provided</scope></dependency>
    <dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

Create the package markers:

```java
// backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/pojo/package-info.java
@org.jspecify.annotations.NullMarked
package xyz.yychainsaw.portfolio.pojo;
```

```java
// backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/package-info.java
@org.jspecify.annotations.NullMarked
package xyz.yychainsaw.portfolio.common;
```

Add `org.jspecify:jspecify` to both module POMs so the package annotations compile:

```xml
<dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId></dependency>
```

- [ ] **Step 4: Create the server POM and application entrypoint**

Create `backend-parent/portfolio-server/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>xyz.yychainsaw</groupId><artifactId>portfolio-backend-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath>
  </parent>
  <artifactId>portfolio-server</artifactId>

  <dependencies>
    <dependency><groupId>xyz.yychainsaw</groupId><artifactId>portfolio-pojo</artifactId></dependency>
    <dependency><groupId>xyz.yychainsaw</groupId><artifactId>portfolio-common</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-thymeleaf</artifactId></dependency>
    <dependency><groupId>org.springframework.session</groupId><artifactId>spring-session-jdbc</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId></dependency>
    <dependency><groupId>dev.samstevens.totp</groupId><artifactId>totp</artifactId></dependency>
    <dependency><groupId>org.bouncycastle</groupId><artifactId>bcprov-jdk18on</artifactId></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
```

Create `PortfolioApplication.java`:

```java
package xyz.yychainsaw.portfolio;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class PortfolioApplication {
    public static void main(String[] args) {
        boolean cli = Arrays.stream(args).anyMatch(arg -> arg.startsWith("--portfolio.cli.command="));
        SpringApplication application = new SpringApplication(PortfolioApplication.class);
        application.setWebApplicationType(cli ? WebApplicationType.NONE : WebApplicationType.SERVLET);
        ConfigurableApplicationContext context = application.run(args);
        if (cli) {
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }
    }
}
```

- [ ] **Step 5: Generate and pin Maven Wrapper 3.9.11**

From `backend-parent/`, run with an installed Maven once:

```powershell
mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -DmavenVersion=3.9.11
```

Verify `backend-parent/.mvn/wrapper/maven-wrapper.properties` contains:

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

- [ ] **Step 6: Resolve the fixed stack and validate all modules**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml --version
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -DskipTests validate
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server dependency:tree `
  -Dincludes=org.springframework.boot:*,org.springframework.security:*,org.springframework.session:*,org.flywaydb:*,org.postgresql:postgresql,com.baomidou:*,dev.samstevens.totp:*
```

Expected: Maven reports `3.9.11`, Java reports `17`, reactor order is pojo/common/server, and dependency resolution succeeds without Fastjson or `java-jwt`.

- [ ] **Step 7: Commit the reproducible build**

```powershell
git add backend-parent
git diff --cached --check
git commit -m "build: scaffold modular Spring Boot backend"
```

---

### Task 3: Add loopback-only PostgreSQL 17 Compose and secret-only configuration

**Files:**
- Create: `.env.example`
- Create: `deploy/compose.dev.yml`
- Create: `deploy/postgres/init/01-create-roles.sh`
- Create: `backend-parent/portfolio-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Maven server module from Task 2.
- Produces: PostgreSQL database `portfolio`, owner/migrator/runtime role separation, and environment property names used by Flyway, JDBC, Spring Session, TOTP encryption, rate limiting, and recovery CLI tasks.

- [ ] **Step 1: Verify Compose and application configuration are absent**

```powershell
Test-Path deploy\compose.dev.yml
Test-Path backend-parent\portfolio-server\src\main\resources\application.yml
```

Expected: both values are `False`.

- [ ] **Step 2: Create a non-secret environment template**

Create `.env.example` with empty secret values:

```dotenv
POSTGRES_DB=portfolio
POSTGRES_DEV_PORT=5432
POSTGRES_OWNER=portfolio_owner
POSTGRES_OWNER_PASSWORD=
PORTFOLIO_DB_MIGRATOR_USER=portfolio_migrator
PORTFOLIO_DB_MIGRATOR_PASSWORD=
PORTFOLIO_DB_RUNTIME_USER=portfolio_runtime
PORTFOLIO_DB_RUNTIME_PASSWORD=
PORTFOLIO_DB_URL=jdbc:postgresql://localhost:5432/portfolio?currentSchema=portfolio
PORTFOLIO_DB_MIGRATOR_URL=jdbc:postgresql://localhost:5432/portfolio?currentSchema=portfolio
PORTFOLIO_TOTP_ACTIVE_KEY_VERSION=1
PORTFOLIO_TOTP_KEY_RING=
PORTFOLIO_SESSION_COOKIE_SECURE=false
PORTFOLIO_ADMIN_DEV_ORIGIN=http://localhost:5174
PORTFOLIO_RECOVERY_DIRECTORY=runtime/recovery
PORTFOLIO_PG_DUMP_BIN=pg_dump
```

The empty values are intentional: Compose fails with a named error until `.env.local` supplies secrets, and `.env.local` is already ignored.

- [ ] **Step 3: Create separate database roles without putting passwords in SQL or Git**

Create `deploy/postgres/init/01-create-roles.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${PORTFOLIO_DB_MIGRATOR_USER:?PORTFOLIO_DB_MIGRATOR_USER is required}"
: "${PORTFOLIO_DB_MIGRATOR_PASSWORD:?PORTFOLIO_DB_MIGRATOR_PASSWORD is required}"
: "${PORTFOLIO_DB_RUNTIME_USER:?PORTFOLIO_DB_RUNTIME_USER is required}"
: "${PORTFOLIO_DB_RUNTIME_PASSWORD:?PORTFOLIO_DB_RUNTIME_PASSWORD is required}"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migrator_user="$PORTFOLIO_DB_MIGRATOR_USER" \
  --set=migrator_password="$PORTFOLIO_DB_MIGRATOR_PASSWORD" \
  --set=runtime_user="$PORTFOLIO_DB_RUNTIME_USER" \
  --set=runtime_password="$PORTFOLIO_DB_RUNTIME_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'migrator_user', :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator_user') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user') \gexec

SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO %I', current_database(), :'migrator_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'runtime_user') \gexec
SELECT format('ALTER ROLE %I SET search_path TO portfolio, public', :'migrator_user') \gexec
SELECT format('ALTER ROLE %I SET search_path TO portfolio, public', :'runtime_user') \gexec
SQL
```

Mark the script executable in Git:

```powershell
git add deploy/postgres/init/01-create-roles.sh
git update-index --chmod=+x deploy/postgres/init/01-create-roles.sh
```

- [ ] **Step 4: Create the PostgreSQL 17 development Compose service**

Create `deploy/compose.dev.yml`:

```yaml
name: portfolio-dev

services:
  postgres:
    image: postgres:17-bookworm
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB:?POSTGRES_DB is required}
      POSTGRES_USER: ${POSTGRES_OWNER:?POSTGRES_OWNER is required}
      POSTGRES_PASSWORD: ${POSTGRES_OWNER_PASSWORD:?POSTGRES_OWNER_PASSWORD is required}
      PORTFOLIO_DB_MIGRATOR_USER: ${PORTFOLIO_DB_MIGRATOR_USER:?PORTFOLIO_DB_MIGRATOR_USER is required}
      PORTFOLIO_DB_MIGRATOR_PASSWORD: ${PORTFOLIO_DB_MIGRATOR_PASSWORD:?PORTFOLIO_DB_MIGRATOR_PASSWORD is required}
      PORTFOLIO_DB_RUNTIME_USER: ${PORTFOLIO_DB_RUNTIME_USER:?PORTFOLIO_DB_RUNTIME_USER is required}
      PORTFOLIO_DB_RUNTIME_PASSWORD: ${PORTFOLIO_DB_RUNTIME_PASSWORD:?PORTFOLIO_DB_RUNTIME_PASSWORD is required}
      TZ: UTC
      PGTZ: UTC
    ports:
      - "127.0.0.1:${POSTGRES_DEV_PORT:-5432}:5432"
    volumes:
      - portfolio_postgres_data:/var/lib/postgresql/data
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 5s
      timeout: 3s
      retries: 20
    stop_grace_period: 30s
    networks: [portfolio_private]

volumes:
  portfolio_postgres_data:

networks:
  portfolio_private:
    internal: true
```

- [ ] **Step 5: Configure Boot without secret defaults**

Create `backend-parent/portfolio-server/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: portfolio-server
  datasource:
    url: ${PORTFOLIO_DB_URL}
    username: ${PORTFOLIO_DB_RUNTIME_USER}
    password: ${PORTFOLIO_DB_RUNTIME_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 1
      connection-timeout: 10s
      data-source-properties:
        ApplicationName: portfolio-server
  flyway:
    enabled: true
    url: ${PORTFOLIO_DB_MIGRATOR_URL:${PORTFOLIO_DB_URL}}
    user: ${PORTFOLIO_DB_MIGRATOR_USER}
    password: ${PORTFOLIO_DB_MIGRATOR_PASSWORD}
    default-schema: portfolio
    schemas: portfolio
    create-schemas: true
    locations: classpath:db/migration
    validate-migration-naming: true
  session:
    store-type: jdbc
    timeout: 30m
    jdbc:
      initialize-schema: never
      cleanup-cron: "-"
      table-name: SPRING_SESSION
  jackson:
    default-property-inclusion: non_null
    time-zone: UTC

server:
  address: ${PORTFOLIO_BIND_ADDRESS:127.0.0.1}
  port: ${PORTFOLIO_PORT:18080}
  servlet:
    session:
      timeout: 30m
      cookie:
        name: PORTFOLIO_SESSION
        http-only: true
        secure: ${PORTFOLIO_SESSION_COOKIE_SECURE:true}
        same-site: strict

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false

portfolio:
  security:
    totp:
      active-key-version: ${PORTFOLIO_TOTP_ACTIVE_KEY_VERSION}
      key-ring: ${PORTFOLIO_TOTP_KEY_RING}
      issuer: yychainsaw.xyz
      pending-lifetime: PT5M
      max-second-factor-attempts: 5
    session:
      absolute-lifetime: PT8H
      cleanup-interval: PT1M
  rate-limit:
    maximum-subjects: 10000
    policies:
      admin-login:
        limit: 5
        window: PT15M
      admin-security:
        limit: 10
        window: PT15M
      public-contact:
        limit: 5
        window: PT15M
      public-events:
        limit: 60
        window: PT1M
  web:
    development-origin: ${PORTFOLIO_ADMIN_DEV_ORIGIN:http://localhost:5174}
  recovery:
    directory: ${PORTFOLIO_RECOVERY_DIRECTORY:runtime/recovery}
    pg-dump-bin: ${PORTFOLIO_PG_DUMP_BIN:pg_dump}

---
spring:
  config:
    activate:
      on-profile: prod
logging:
  structured:
    format:
      console: logstash
```

- [ ] **Step 6: Generate ignored local secrets and validate the Compose model**

Use this PowerShell once; it does not print generated secrets:

```powershell
Copy-Item .env.example .env.local

function New-RandomBase64([int]$length) {
  $bytes = New-Object byte[] $length
  [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  return [Convert]::ToBase64String($bytes)
}

$owner = New-RandomBase64 32
$migrator = New-RandomBase64 32
$runtime = New-RandomBase64 32
$totp = New-RandomBase64 32
$content = Get-Content -Raw .env.local
$content = $content -replace '(?m)^POSTGRES_OWNER_PASSWORD=$', "POSTGRES_OWNER_PASSWORD=$owner"
$content = $content -replace '(?m)^PORTFOLIO_DB_MIGRATOR_PASSWORD=$', "PORTFOLIO_DB_MIGRATOR_PASSWORD=$migrator"
$content = $content -replace '(?m)^PORTFOLIO_DB_RUNTIME_PASSWORD=$', "PORTFOLIO_DB_RUNTIME_PASSWORD=$runtime"
$content = $content -replace '(?m)^PORTFOLIO_TOTP_KEY_RING=$', "PORTFOLIO_TOTP_KEY_RING=1=$totp"
[IO.File]::WriteAllText((Join-Path $PWD '.env.local'), $content, [Text.UTF8Encoding]::new($false))

docker compose --env-file .env.local -f deploy/compose.dev.yml config --quiet
```

Expected: Compose validation exits `0`, and `git status --short` does not show `.env.local`.

- [ ] **Step 7: Start PostgreSQL and prove role separation exists**

```powershell
docker compose --env-file .env.local -f deploy/compose.dev.yml up -d postgres
docker compose --env-file .env.local -f deploy/compose.dev.yml ps
docker compose --env-file .env.local -f deploy/compose.dev.yml exec -T postgres `
  psql -U portfolio_owner -d portfolio -Atc "select current_setting('server_version_num')::int / 10000"
docker compose --env-file .env.local -f deploy/compose.dev.yml exec -T postgres `
  psql -U portfolio_owner -d portfolio -Atc "select rolname from pg_roles where rolname in ('portfolio_migrator','portfolio_runtime') order by 1"
```

Expected: service is healthy; PostgreSQL prints `17`; both role names are returned.

- [ ] **Step 8: Commit Compose and configuration without local secrets**

```powershell
git add .env.example deploy backend-parent/portfolio-server/src/main/resources/application.yml
git diff --cached --check
git diff --cached | Select-String -Pattern '(PASSWORD=.+|KEY_RING=.+)'
```

Expected: the scan finds only empty template assignments or property names, never a populated value.

```powershell
git commit -m "build: add PostgreSQL 17 development environment"
```

---

### Task 4: Create Flyway V1/V2 and verify Spring Session is migration-owned

**Files:**
- Create: `backend-parent/portfolio-server/src/main/resources/db/migration/V1__extensions_and_core.sql`
- Create: `backend-parent/portfolio-server/src/main/resources/db/migration/V2__authentication_and_audit.sql`
- Create: `backend-parent/portfolio-server/src/test/resources/db/test/00-test-roles.sql`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/support/PostgresIntegrationTestBase.java`
- Create: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/system/MigrationSmokeTest.java`

**Interfaces:**
- Consumes: Datasource/Flyway/Spring Session properties from Task 3.
- Produces: Schema `portfolio`, official Spring Session tables, `admin_user`, `totp_recovery_code`, `admin_session_metadata`, `audit_log`, restricted runtime grants, and inherited test-only `PostgresIntegrationTestBase#migratorDataSource()` / `migratorJdbc()` helpers for assertions that must run as the migration role.

- [ ] **Step 1: Write a migration smoke test before creating any migration**

Create `PostgresIntegrationTestBase.java`:

```java
package xyz.yychainsaw.portfolio.support;

import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
@ActiveProfiles("test")
public abstract class PostgresIntegrationTestBase {
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-bookworm")
                    .withDatabaseName("portfolio_test")
                    .withUsername("test_owner")
                    .withPassword("test_owner_password")
                    .withInitScript("db/test/00-test-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = POSTGRES.getJdbcUrl() + "?currentSchema=portfolio";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "portfolio_runtime");
        registry.add("spring.datasource.password", () -> "runtime_test_password");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "portfolio_migrator");
        registry.add("spring.flyway.password", () -> "migrator_test_password");
        registry.add("portfolio.recovery.host", POSTGRES::getHost);
        registry.add("portfolio.recovery.port", POSTGRES::getFirstMappedPort);
        registry.add("portfolio.recovery.database", POSTGRES::getDatabaseName);
        registry.add("portfolio.recovery.username", () -> "portfolio_migrator");
        registry.add("portfolio.recovery.password", () -> "migrator_test_password");
        registry.add("portfolio.security.totp.active-key-version", () -> "1");
        registry.add("portfolio.security.totp.key-ring",
                () -> "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    protected static DriverManagerDataSource migratorDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl() + "?currentSchema=portfolio");
        dataSource.setUsername("portfolio_migrator");
        dataSource.setPassword("migrator_test_password");
        return dataSource;
    }

    protected static JdbcClient migratorJdbc() {
        return JdbcClient.create(migratorDataSource());
    }
}
```

Create `MigrationSmokeTest.java`:

```java
package xyz.yychainsaw.portfolio.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class MigrationSmokeTest extends PostgresIntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @Test
    void flywayAppliesFoundationVersionsInOrderAndCreatesSessionTables() {
        List<String> versions = migratorJdbc()
                .sql("select version from flyway_schema_history where success order by installed_rank")
                .query(String.class).list();
        assertThat(versions).contains("1", "2");
        assertThat(versions).containsSubsequence("1", "2");

        Integer tableCount = jdbc.sql("""
                select count(*) from information_schema.tables
                where table_schema='portfolio' and table_name in
                ('spring_session','spring_session_attributes','admin_user',
                 'totp_recovery_code','admin_session_metadata','audit_log')
                """).query(Integer.class).single();
        assertThat(tableCount).isEqualTo(6);
    }

    @Test
    void runtimeAccountCannotCreateTables() {
        assertThatThrownBy(() -> jdbc.sql("create table runtime_must_not_create(id integer)").update())
                .hasMessageContaining("permission denied");
    }
}
```

- [ ] **Step 2: Run the migration test and observe the expected failure**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=MigrationSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL during Flyway startup because `db/migration` has no V1/V2 scripts.

- [ ] **Step 3: Create the Testcontainers-only database roles**

Create `backend-parent/portfolio-server/src/test/resources/db/test/00-test-roles.sql`:

```sql
CREATE ROLE portfolio_migrator LOGIN PASSWORD 'migrator_test_password';
CREATE ROLE portfolio_runtime LOGIN PASSWORD 'runtime_test_password';
GRANT CONNECT ON DATABASE portfolio_test TO portfolio_migrator, portfolio_runtime;
GRANT CREATE ON DATABASE portfolio_test TO portfolio_migrator;
ALTER ROLE portfolio_migrator SET search_path TO portfolio, public;
ALTER ROLE portfolio_runtime SET search_path TO portfolio, public;
```

- [ ] **Step 4: Create V1 with the shared extension and timestamp trigger function**

Create `V1__extensions_and_core.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS portfolio AUTHORIZATION portfolio_migrator;
REVOKE ALL ON SCHEMA portfolio FROM PUBLIC;
GRANT USAGE ON SCHEMA portfolio TO portfolio_runtime;

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA portfolio;

CREATE OR REPLACE FUNCTION portfolio.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = portfolio, pg_temp
AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION portfolio.set_updated_at() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portfolio.set_updated_at() TO portfolio_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE portfolio_migrator IN SCHEMA portfolio
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE portfolio_migrator IN SCHEMA portfolio
    REVOKE ALL ON FUNCTIONS FROM PUBLIC;
```

- [ ] **Step 5: Create V2 with the official Spring Session tables and authentication data**

Create `V2__authentication_and_audit.sql`. Keep the first two tables and three indexes byte-for-byte structurally equivalent to Spring Session's PostgreSQL schema, including `PRIMARY_ID` as the stable foreign-key target:

```sql
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

CREATE TABLE admin_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton_key BOOLEAN NOT NULL DEFAULT TRUE,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    totp_key_version INTEGER NOT NULL,
    totp_nonce BYTEA NOT NULL,
    totp_ciphertext BYTEA NOT NULL,
    last_login_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT admin_user_singleton_uk UNIQUE (singleton_key),
    CONSTRAINT admin_user_singleton_ck CHECK (singleton_key),
    CONSTRAINT admin_user_status_ck CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT admin_user_username_ck CHECK (username = btrim(username) AND length(username) BETWEEN 3 AND 64),
    CONSTRAINT admin_user_totp_nonce_ck CHECK (octet_length(totp_nonce) = 12)
);

CREATE UNIQUE INDEX admin_user_username_lower_uk ON admin_user (lower(username));
CREATE TRIGGER admin_user_set_updated_at
BEFORE UPDATE ON admin_user
FOR EACH ROW EXECUTE FUNCTION portfolio.set_updated_at();

CREATE TABLE totp_recovery_code (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX totp_recovery_code_available_ix
    ON totp_recovery_code (admin_id, created_at, id)
    WHERE used_at IS NULL;

CREATE TABLE admin_session_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
    session_primary_id CHAR(36) UNIQUE
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE SET NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ended_at TIMESTAMPTZ,
    client_summary VARCHAR(255) NOT NULL,
    revocation_reason VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admin_session_metadata_status_ck CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT admin_session_metadata_terminal_ck CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL AND revocation_reason IS NULL)
        OR (status IN ('REVOKED', 'EXPIRED') AND ended_at IS NOT NULL AND revocation_reason IS NOT NULL)
    )
);

CREATE INDEX admin_session_metadata_admin_status_ix
    ON admin_session_metadata (admin_id, status, created_at DESC);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_admin_id UUID REFERENCES admin_user(id) ON DELETE SET NULL,
    action VARCHAR(96) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128),
    outcome VARCHAR(16) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT audit_log_outcome_ck CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT audit_log_metadata_object_ck CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX audit_log_created_at_ix ON audit_log (created_at DESC, id);
CREATE INDEX audit_log_actor_ix ON audit_log (actor_admin_id, created_at DESC);

CREATE OR REPLACE FUNCTION portfolio.reject_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = portfolio, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is immutable' USING ERRCODE = '55000';
END;
$$;

REVOKE ALL ON FUNCTION portfolio.reject_audit_mutation() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portfolio.reject_audit_mutation() TO portfolio_runtime;

CREATE TRIGGER audit_log_reject_mutation
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION portfolio.reject_audit_mutation();

CREATE TRIGGER audit_log_reject_truncate
BEFORE TRUNCATE ON audit_log
FOR EACH STATEMENT EXECUTE FUNCTION portfolio.reject_audit_mutation();

GRANT SELECT, INSERT, UPDATE, DELETE ON SPRING_SESSION TO portfolio_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON SPRING_SESSION_ATTRIBUTES TO portfolio_runtime;
GRANT SELECT, INSERT, UPDATE ON admin_user TO portfolio_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON totp_recovery_code TO portfolio_runtime;
GRANT SELECT, INSERT, UPDATE ON admin_session_metadata TO portfolio_runtime;
GRANT SELECT, INSERT ON audit_log TO portfolio_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM portfolio_runtime;
```

- [ ] **Step 6: Run migrations and verify the tests pass**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=MigrationSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; Flyway history contains exactly versions 1 and 2; the runtime DDL attempt fails with permission denied.

- [ ] **Step 7: Verify Spring Session never creates or cleans its own schema**

```powershell
Select-String -Path backend-parent\portfolio-server\src\main\resources\application.yml `
  -Pattern 'initialize-schema: never','cleanup-cron: "-"'
Get-ChildItem backend-parent\portfolio-server\src\main\resources\db\migration | Select-Object Name
```

Expected: both properties match; only V1 and V2 exist.

- [ ] **Step 8: Commit the database foundation**

```powershell
git add backend-parent/portfolio-server/src/main/resources/db `
        backend-parent/portfolio-server/src/test
git diff --cached --check
git commit -m "feat: add Flyway authentication foundation"
```

---

### Task 5: Add DomainException, trace-aware ProblemDetail, and the reusable bounded RateLimiter

**Files:**
- Create: `backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/error/DomainException.java`
- Create: `backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/ratelimit/RateLimiter.java`
- Create: `backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/ratelimit/RateLimitDecision.java`
- Create: `backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/trace/TraceIds.java`
- Create: `backend-parent/portfolio-common/src/main/java/xyz/yychainsaw/portfolio/common/trace/TraceIdFilter.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/config/TimeConfiguration.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/common/ratelimit/RateLimitProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/common/ratelimit/InMemoryRateLimiter.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/common/error/GlobalProblemHandler.java`
- Test: `backend-parent/portfolio-common/src/test/java/xyz/yychainsaw/portfolio/common/error/DomainExceptionTest.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/common/ratelimit/InMemoryRateLimiterTest.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/common/error/ProblemHandlingTest.java`

**Interfaces:**
- Consumes: `portfolio-common` and `portfolio-server` module boundaries from Task 2 and rate-limit properties from Task 3.
- Produces: Exact `DomainException(String,HttpStatus,Map<String,String>)`; exact `RateLimiter#consume(String,String)` and `RateLimitDecision`; server-generated `traceId` in MDC, response headers, ProblemDetail, and later audit commands.

- [ ] **Step 1: Write failing contract tests**

Create `DomainExceptionTest.java`:

```java
package xyz.yychainsaw.portfolio.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DomainExceptionTest {
    @Test
    void copiesFieldErrorsAndExposesTheStableContract() {
        Map<String, String> source = new HashMap<>();
        source.put("translations.en.summary", "required");

        DomainException exception = new DomainException(
                "PROJECT_TRANSLATION_INCOMPLETE", HttpStatus.UNPROCESSABLE_ENTITY, source);
        source.clear();

        assertThat(exception.code()).isEqualTo("PROJECT_TRANSLATION_INCOMPLETE");
        assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(exception.fieldErrors()).containsEntry("translations.en.summary", "required");
        assertThatThrownBy(() -> exception.fieldErrors().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

Create the core of `InMemoryRateLimiterTest.java`:

```java
package xyz.yychainsaw.portfolio.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties.Policy;

class InMemoryRateLimiterTest {
    @Test
    void deniesAfterTheConfiguredWindowAndAllowsAfterRollover() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-14T12:00:00Z"));
        RateLimitProperties properties = new RateLimitProperties(
                10_000, Map.of("admin-login", new Policy(2, Duration.ofMinutes(1))));
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(properties, clock);

        assertThat(limiter.consume("admin-login", "sha256-subject").allowed()).isTrue();
        assertThat(limiter.consume("admin-login", "sha256-subject").allowed()).isTrue();
        RateLimitDecision denied = limiter.consume("admin-login", "sha256-subject");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(60);

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.consume("admin-login", "sha256-subject").allowed()).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
```

- [ ] **Step 2: Run the tests and observe missing-type failures**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-common,portfolio-server -am `
  -Dtest=DomainExceptionTest,InMemoryRateLimiterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because the exception, decision, properties, and implementation types do not exist.

- [ ] **Step 3: Implement the exact common contracts**

Create `DomainException.java`:

```java
package xyz.yychainsaw.portfolio.common.error;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public DomainException(String code, HttpStatus status, Map<String, String> fieldErrors) {
        super(Objects.requireNonNull(code, "code"));
        this.code = code;
        this.status = Objects.requireNonNull(status, "status");
        this.fieldErrors = Map.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors"));
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}
```

Create the rate-limit types:

```java
// RateLimiter.java
package xyz.yychainsaw.portfolio.common.ratelimit;

public interface RateLimiter {
    RateLimitDecision consume(String policy, String subject);
}
```

```java
// RateLimitDecision.java
package xyz.yychainsaw.portfolio.common.ratelimit;

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
    public RateLimitDecision {
        if (retryAfterSeconds < 0) throw new IllegalArgumentException("retryAfterSeconds must be non-negative");
    }

    public static RateLimitDecision allow() { return new RateLimitDecision(true, 0); }
    public static RateLimitDecision deny(long seconds) { return new RateLimitDecision(false, Math.max(1, seconds)); }
}
```

- [ ] **Step 4: Implement server-generated trace IDs**

Create `TraceIds.java` and `TraceIdFilter.java`:

```java
package xyz.yychainsaw.portfolio.common.trace;

import java.util.UUID;
import org.slf4j.MDC;

public final class TraceIds {
    public static final String MDC_KEY = "traceId";
    private TraceIds() {}

    public static String current() {
        String current = MDC.get(MDC_KEY);
        return current == null || current.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : current;
    }
}
```

```java
package xyz.yychainsaw.portfolio.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = java.util.UUID.randomUUID().toString().replace("-", "");
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
```

- [ ] **Step 5: Implement the bounded fixed-window rate limiter**

Create `RateLimitProperties.java`:

```java
package xyz.yychainsaw.portfolio.common.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.rate-limit")
public record RateLimitProperties(int maximumSubjects, Map<String, Policy> policies) {
    public RateLimitProperties {
        if (maximumSubjects < 1) throw new IllegalArgumentException("maximumSubjects must be positive");
        policies = Map.copyOf(policies);
    }

    public record Policy(int limit, Duration window) {
        public Policy {
            if (limit < 1 || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("rate-limit policy must have positive limit and window");
            }
        }
    }
}
```

Create `InMemoryRateLimiter.java`:

```java
package xyz.yychainsaw.portfolio.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public final class InMemoryRateLimiter implements RateLimiter {
    private final RateLimitProperties properties;
    private final Clock clock;
    private final Cache<Key, Bucket> buckets;

    public InMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maximumSubjects())
                .expireAfterAccess(Duration.ofDays(1))
                .build();
    }

    @Override
    public RateLimitDecision consume(String policy, String subject) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject is required");
        RateLimitProperties.Policy configured = Objects.requireNonNull(
                properties.policies().get(policy), "unknown rate-limit policy: " + policy);
        long now = clock.instant().getEpochSecond();
        long windowSeconds = configured.window().toSeconds();
        long windowStart = now - Math.floorMod(now, windowSeconds);
        AtomicReference<RateLimitDecision> decision = new AtomicReference<>();

        buckets.asMap().compute(new Key(policy, subject), (key, existing) -> {
            Bucket current = existing == null || existing.windowStart() != windowStart
                    ? new Bucket(windowStart, 0) : existing;
            if (current.used() >= configured.limit()) {
                decision.set(RateLimitDecision.deny(windowStart + windowSeconds - now));
                return current;
            }
            decision.set(RateLimitDecision.allow());
            return new Bucket(windowStart, current.used() + 1);
        });
        return decision.get();
    }

    private record Key(String policy, String subject) {}
    private record Bucket(long windowStart, int used) {}
}
```

Create `TimeConfiguration.java`:

```java
package xyz.yychainsaw.portfolio.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {
    @Bean Clock applicationClock() { return Clock.systemUTC(); }
}
```

- [ ] **Step 6: Implement safe ProblemDetail mapping**

Create `GlobalProblemHandler.java`:

```java
package xyz.yychainsaw.portfolio.common.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

@RestControllerAdvice
public class GlobalProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException exception) {
        return response(exception.status(), exception.code(), "Request could not be processed", exception.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Request validation failed", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable() {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        String traceId = TraceIds.current();
        log.error("Unhandled request failure traceId={} type={}", traceId, exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", Map.of());
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status, String code, String title, Map<String, String> fields) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, title);
        problem.setTitle(title);
        problem.setType(URI.create("urn:portfolio:problem:" + code.toLowerCase(Locale.ROOT)));
        problem.setProperty("code", code);
        problem.setProperty("traceId", TraceIds.current());
        problem.setProperty("fieldErrors", fields);
        return ResponseEntity.status(status).body(problem);
    }
}
```

- [ ] **Step 7: Add a web-level redaction/trace test**

Create `ProblemHandlingTest.java`:

```java
package xyz.yychainsaw.portfolio.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.common.trace.TraceIdFilter;

class ProblemHandlingTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalProblemHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void domainFailureContainsCodeTraceAndFields() throws Exception {
        mvc.perform(get("/test/domain"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("TRANSLATION_INCOMPLETE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors['translations.en.summary']").value("required"));
    }

    @Test
    void internalFailureDoesNotExposeItsMessage() throws Exception {
        String body = mvc.perform(get("/test/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret/database/path");
    }

    @RestController
    static class FailureController {
        @GetMapping("/test/domain")
        void domain() {
            throw new DomainException("TRANSLATION_INCOMPLETE", HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("translations.en.summary", "required"));
        }
        @GetMapping("/test/internal")
        void internal() { throw new IllegalStateException("secret/database/path"); }
    }
}
```

- [ ] **Step 8: Run all contract tests**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-common,portfolio-server -am `
  -Dtest=DomainExceptionTest,InMemoryRateLimiterTest,ProblemHandlingTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; no response body contains the internal exception message.

- [ ] **Step 9: Commit the reusable infrastructure contracts**

```powershell
git add backend-parent/portfolio-common backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/common `
        backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/config `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/common
git diff --cached --check
git commit -m "feat: add problem details tracing and rate limiting"
```

---

### Task 6: Implement immutable audit persistence and publish the shared admin contracts

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditOutcome.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditCommand.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/JdbcAuditService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/CurrentAdminProvider.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/audit/JdbcAuditServiceTest.java`

**Interfaces:**
- Consumes: `audit_log` from V2 and `TraceIds.current()` from Task 5.
- Produces: Exact cross-plan `AuditService#record(AuditCommand)`, immutable `AuditCommand`, and exact `CurrentAdminProvider#requireAdminId()` interface. Authentication implements the provider in Task 11; all later content/media/publishing plans consume both contracts without changing signatures.

- [ ] **Step 1: Write an integration test for append-only audit behavior**

Create `JdbcAuditServiceTest.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class JdbcAuditServiceTest extends PostgresIntegrationTestBase {
    @Autowired AuditService auditService;
    @Autowired JdbcClient jdbc;

    @Test
    void recordsSafeMetadataAndDatabaseRejectsMutation() {
        auditService.record(new AuditCommand(
                null, "AUTH_REJECTED", "ADMIN", null, AuditOutcome.FAILURE,
                "trace-test", Map.of("stage", "PASSWORD")));

        Map<String, Object> row = jdbc.sql("""
                select action, outcome, trace_id, metadata::text as metadata
                from audit_log where action='AUTH_REJECTED'
                """).query().singleRow();
        assertThat(row.get("action")).isEqualTo("AUTH_REJECTED");
        assertThat(row.get("metadata").toString()).contains("PASSWORD");

        assertThatThrownBy(() -> jdbc.sql(
                "update audit_log set action='MUTATED' where action='AUTH_REJECTED'").update())
                .hasMessageContaining("permission denied");
    }
}
```

- [ ] **Step 2: Run the test and observe the missing AuditService failure**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=JdbcAuditServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because audit contracts do not exist.

- [ ] **Step 3: Create the exact audit contract and command types**

```java
// AuditOutcome.java
package xyz.yychainsaw.portfolio.audit;

public enum AuditOutcome { SUCCESS, FAILURE }
```

```java
// AuditCommand.java
package xyz.yychainsaw.portfolio.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

public record AuditCommand(
        UUID actorAdminId,
        String action,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        String traceId,
        Map<String, String> metadata) {
    public AuditCommand {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(outcome, "outcome");
        traceId = traceId == null || traceId.isBlank() ? TraceIds.current() : traceId;
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
```

```java
// AuditService.java
package xyz.yychainsaw.portfolio.audit;

public interface AuditService {
    void record(AuditCommand command);
}
```

Create the exact shared authentication lookup contract:

```java
package xyz.yychainsaw.portfolio.auth;

import java.util.UUID;

public interface CurrentAdminProvider {
    UUID requireAdminId();
}
```

- [ ] **Step 4: Implement JDBC audit insertion with Jackson and transaction participation**

Create `JdbcAuditService.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class JdbcAuditService implements AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(AuditCommand command) {
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(command.metadata());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audit metadata must be JSON serializable", exception);
        }
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into audit_log
                    (id, actor_admin_id, action, target_type, target_id, outcome, trace_id, metadata)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    """);
            statement.setObject(1, UUID.randomUUID());
            if (command.actorAdminId() == null) statement.setNull(2, Types.OTHER);
            else statement.setObject(2, command.actorAdminId());
            statement.setString(3, command.action());
            statement.setString(4, command.targetType());
            statement.setString(5, command.targetId());
            statement.setString(6, command.outcome().name());
            statement.setString(7, command.traceId());
            statement.setString(8, metadata);
            return statement;
        });
    }
}
```

- [ ] **Step 5: Run the audit integration test**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=JdbcAuditServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; insert succeeds and the runtime database role is denied direct audit updates. V2 also retains a trigger as defense in depth against mutation by a more privileged role.

- [ ] **Step 6: Commit the cross-plan audit/admin contracts**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit `
        backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/CurrentAdminProvider.java `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/audit
git diff --cached --check
git commit -m "feat: add immutable audit service contract"
```

---

### Task 7: Implement Argon2id, versioned AES-GCM TOTP secrets, and recovery-code primitives

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/TotpProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/EncryptedTotpSecret.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/TotpEnvelopeCrypto.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/TotpService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/PasswordPolicy.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/RecoveryCodeGenerator.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/SecurityCryptoConfiguration.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/crypto/SecurityCryptoTest.java`

**Interfaces:**
- Consumes: Environment-backed `portfolio.security.totp.*` properties and UTC clock.
- Produces: `PasswordEncoder` backed by Argon2id; `TotpService.beginEnrollment/verifyEnrollment/verify`; AES-GCM value `EncryptedTotpSecret`; ten one-time printable recovery codes. Persistence and CLIs consume these exact APIs.

- [ ] **Step 1: Write cryptography behavior tests first**

Create `SecurityCryptoTest.java`:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class SecurityCryptoTest {
    private static final String KEY_RING =
            "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void passwordEncoderUsesArgon2id() {
        var encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        String encoded = encoder.encode("Correct-Horse-7!Battery");
        assertThat(encoded).startsWith("$argon2id$");
        assertThat(encoder.matches("Correct-Horse-7!Battery", encoded)).isTrue();
        assertThat(encoder.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    void encryptedTotpSecretIsBoundToAdminAndDetectsTampering() {
        TotpProperties properties = new TotpProperties(
                1, KEY_RING, "yychainsaw.xyz", Duration.ofMinutes(5), 5);
        TotpEnvelopeCrypto crypto = new TotpEnvelopeCrypto(properties);
        UUID adminId = UUID.randomUUID();
        EncryptedTotpSecret encrypted = crypto.encrypt(adminId, "BASE32SECRET");

        assertThat(crypto.decrypt(adminId, encrypted)).isEqualTo("BASE32SECRET");
        assertThatThrownBy(() -> crypto.decrypt(UUID.randomUUID(), encrypted))
                .isInstanceOf(SecurityException.class);

        byte[] tampered = encrypted.ciphertext();
        tampered[0] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(adminId,
                new EncryptedTotpSecret(encrypted.keyVersion(), encrypted.nonce(), tampered)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void enrollmentAcceptsOnlyTheCurrentTotp() throws Exception {
        TotpProperties properties = new TotpProperties(
                1, KEY_RING, "yychainsaw.xyz", Duration.ofMinutes(5), 5);
        TotpEnvelopeCrypto crypto = new TotpEnvelopeCrypto(properties);
        TimeProvider fixedTime = () -> 1_784_048_400L;
        TotpService service = new TotpService(
                properties, crypto, new DefaultSecretGenerator(), new DefaultCodeGenerator(), fixedTime);
        UUID adminId = UUID.randomUUID();
        TotpService.Enrollment enrollment = service.beginEnrollment(adminId, "admin");
        String code = new DefaultCodeGenerator().generate(
                enrollment.plaintextSecret(), fixedTime.getTime() / 30);

        assertThat(service.verifyEnrollment(enrollment.plaintextSecret(), code)).isTrue();
        assertThat(service.verify(adminId, enrollment.encryptedSecret(), code)).isTrue();
        assertThat(enrollment.provisioningUri()).startsWith("otpauth://totp/");
    }

    @Test
    void recoveryCodesAreUniqueAndHumanReadable() {
        List<String> codes = new RecoveryCodeGenerator().generate(10);
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(code -> code.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"));
    }
}
```

- [ ] **Step 2: Run the test and observe missing-type failures**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=SecurityCryptoTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because the crypto services do not exist.

- [ ] **Step 3: Bind and validate the versioned TOTP key ring**

Create `TotpProperties.java`:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.security.totp")
public record TotpProperties(
        int activeKeyVersion,
        String keyRing,
        String issuer,
        Duration pendingLifetime,
        int maxSecondFactorAttempts) {
    public TotpProperties {
        if (activeKeyVersion < 1 || keyRing == null || keyRing.isBlank()) {
            throw new IllegalArgumentException("a versioned TOTP key ring is required");
        }
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("TOTP issuer is required");
        if (pendingLifetime.isNegative() || pendingLifetime.isZero() || maxSecondFactorAttempts < 1) {
            throw new IllegalArgumentException("invalid second-factor limits");
        }
    }
}
```

Create `EncryptedTotpSecret.java` with defensive array copies:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import java.util.Arrays;

public record EncryptedTotpSecret(int keyVersion, byte[] nonce, byte[] ciphertext) {
    public EncryptedTotpSecret {
        if (keyVersion < 1 || nonce.length != 12 || ciphertext.length < 17) {
            throw new IllegalArgumentException("invalid encrypted TOTP secret");
        }
        nonce = Arrays.copyOf(nonce, nonce.length);
        ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
    }
    @Override public byte[] nonce() { return Arrays.copyOf(nonce, nonce.length); }
    @Override public byte[] ciphertext() { return Arrays.copyOf(ciphertext, ciphertext.length); }
}
```

- [ ] **Step 4: Implement AES-256-GCM encryption with admin-bound AAD**

Create `TotpEnvelopeCrypto.java`:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class TotpEnvelopeCrypto {
    private static final int TAG_BITS = 128;
    private final int activeVersion;
    private final Map<Integer, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();

    public TotpEnvelopeCrypto(TotpProperties properties) {
        this.activeVersion = properties.activeKeyVersion();
        this.keys = parse(properties.keyRing());
        if (!keys.containsKey(activeVersion)) {
            throw new IllegalArgumentException("active TOTP key version is absent from key ring");
        }
    }

    public int activeKeyVersion() { return activeVersion; }

    public EncryptedTotpSecret encrypt(UUID adminId, String plaintext) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(adminId));
            return new EncryptedTotpSecret(activeVersion, nonce,
                    cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP encryption failed", exception);
        }
    }

    public String decrypt(UUID adminId, EncryptedTotpSecret encrypted) {
        SecretKeySpec key = keys.get(encrypted.keyVersion());
        if (key == null) throw new SecurityException("unknown TOTP key version");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(aad(adminId));
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new SecurityException("TOTP ciphertext authentication failed", exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP decryption failed", exception);
        }
    }

    private static byte[] aad(UUID adminId) {
        return ("portfolio-admin-totp:" + adminId).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<Integer, SecretKeySpec> parse(String keyRing) {
        Map<Integer, SecretKeySpec> parsed = new HashMap<>();
        for (String entry : keyRing.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2) throw new IllegalArgumentException("invalid TOTP key-ring entry");
            int version = Integer.parseInt(parts[0]);
            byte[] key = Base64.getDecoder().decode(parts[1]);
            if (key.length != 32) throw new IllegalArgumentException("TOTP AES key must be 32 bytes");
            if (parsed.put(version, new SecretKeySpec(key, "AES")) != null) {
                throw new IllegalArgumentException("duplicate TOTP key version");
            }
        }
        return Map.copyOf(parsed);
    }
}
```

Also expose `EncryptedTotpSecret reencrypt(UUID adminId, EncryptedTotpSecret encrypted)`. Implement it through private byte-array encrypt/decrypt helpers: decrypt the authenticated ciphertext, encrypt those same bytes under `activeVersion` with a fresh nonce, and wipe the temporary plaintext array with `Arrays.fill` in `finally`. Existing string-facing `encrypt`/`decrypt` methods delegate to the same helpers for login/enrollment behavior; the re-encryption path must never construct a plaintext `String`. Add a crypto test proving old-version ciphertext becomes active-version ciphertext, the seed is unchanged, and an unknown/modified old envelope fails without producing output.

- [ ] **Step 5: Implement TOTP enrollment and verification**

Create `TotpService.java`:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import dev.samstevens.totp.code.CodeGenerationException;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class TotpService {
    private final TotpProperties properties;
    private final TotpEnvelopeCrypto crypto;
    private final SecretGenerator secrets;
    private final CodeGenerator codes;
    private final TimeProvider time;

    public TotpService(TotpProperties properties, TotpEnvelopeCrypto crypto,
                       SecretGenerator secrets, CodeGenerator codes, TimeProvider time) {
        this.properties = properties;
        this.crypto = crypto;
        this.secrets = secrets;
        this.codes = codes;
        this.time = time;
    }

    public Enrollment beginEnrollment(UUID adminId, String username) {
        String secret = secrets.generate();
        String label = URLEncoder.encode(properties.issuer() + ":" + username, StandardCharsets.UTF_8);
        String issuer = URLEncoder.encode(properties.issuer(), StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, crypto.encrypt(adminId, secret), uri);
    }

    public boolean verifyEnrollment(String plaintextSecret, String code) {
        return verifySecret(plaintextSecret, code);
    }

    public boolean verify(UUID adminId, EncryptedTotpSecret encrypted, String code) {
        return verifySecret(crypto.decrypt(adminId, encrypted), code);
    }

    private boolean verifySecret(String secret, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long counter = time.getTime() / 30;
        try {
            for (long candidate = counter - 1; candidate <= counter + 1; candidate++) {
                if (codes.generate(secret, candidate).equals(code)) return true;
            }
            return false;
        } catch (CodeGenerationException exception) {
            throw new IllegalStateException("TOTP generation failed", exception);
        }
    }

    public record Enrollment(
            String plaintextSecret,
            EncryptedTotpSecret encryptedSecret,
            String provisioningUri) {}
}
```

- [ ] **Step 6: Implement password policy and one-time recovery-code generation**

Create `PasswordPolicy.java`:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
public final class PasswordPolicy {
    public void requireStrong(CharSequence password) {
        boolean valid = password != null && password.length() >= 14 && password.length() <= 128
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (!valid) {
            throw new DomainException("PASSWORD_POLICY_VIOLATION", HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("password", "密码须为 14–128 位，并包含大小写字母、数字和符号"));
        }
    }
}
```

Create `RecoveryCodeGenerator.java`:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class RecoveryCodeGenerator {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public List<String> generate(int count) {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < count) codes.add(generateOne());
        return List.copyOf(new ArrayList<>(codes));
    }

    private String generateOne() {
        StringBuilder value = new StringBuilder(14);
        for (int i = 0; i < 12; i++) {
            if (i == 4 || i == 8) value.append('-');
            value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }
}
```

- [ ] **Step 7: Wire the Argon2id and TOTP library beans**

Create `SecurityCryptoConfiguration.java`:

```java
package xyz.yychainsaw.portfolio.auth.crypto;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityCryptoConfiguration {
    @Bean PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
    @Bean SecretGenerator totpSecretGenerator() { return new DefaultSecretGenerator(); }
    @Bean CodeGenerator totpCodeGenerator() { return new DefaultCodeGenerator(); }
    @Bean TimeProvider totpTimeProvider() { return new SystemTimeProvider(); }
}
```

- [ ] **Step 8: Run crypto tests and inspect the dependency tree**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=SecurityCryptoTest -Dsurefire.failIfNoSpecifiedTests=false test
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server dependency:tree `
  -Dincludes=org.bouncycastle:bcprov-jdk18on,dev.samstevens.totp:totp
```

Expected: tests PASS; Argon2id and TOTP dependencies each resolve once.

- [ ] **Step 9: Commit credential primitives**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/crypto
git diff --cached --check
git commit -m "feat: add Argon2id TOTP and recovery primitives"
```

---

### Task 8: Add admin and recovery-code persistence with one-time consumption

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/model/AdminStatus.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/model/AdminUser.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/persistence/AdminUserRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/persistence/RecoveryCodeRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/RecoveryCodeService.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/AuthPersistenceTest.java`

**Interfaces:**
- Consumes: V2 `admin_user`/`totp_recovery_code`, `PasswordEncoder`, `EncryptedTotpSecret`, and `RecoveryCodeGenerator`.
- Produces: Case-insensitive single-admin lookup, credential replacement, last-login update, recovery-code replacement, and atomic one-time recovery-code consumption.

- [ ] **Step 1: Write persistence and one-time-use tests**

Create `AuthPersistenceTest.java`:

```java
package xyz.yychainsaw.portfolio.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Transactional
class AuthPersistenceTest extends PostgresIntegrationTestBase {
    @Autowired AdminUserRepository admins;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired RecoveryCodeService recoveryCodeService;
    @Autowired PasswordEncoder encoder;

    @Test
    void usernameLookupIsCaseInsensitiveAndRecoveryCodeIsSingleUse() {
        UUID adminId = UUID.randomUUID();
        AdminUser admin = new AdminUser(adminId, "PortfolioAdmin", encoder.encode("Correct-Horse-7!Battery"),
                AdminStatus.ACTIVE, new EncryptedTotpSecret(1, new byte[12], new byte[17]),
                null, 0, Instant.now(), Instant.now());
        admins.insert(admin);
        recoveryCodes.replace(adminId, List.of(encoder.encode("ABCD-EFGH-JKLM")));

        AdminUser fetched = admins.findByUsername("portfolioadmin").orElseThrow();
        assertThat(fetched.id()).isEqualTo(admin.id());
        assertThat(fetched.username()).isEqualTo(admin.username());
        assertThat(fetched.passwordHash()).isEqualTo(admin.passwordHash());
        assertThat(recoveryCodeService.consume(adminId, "ABCD-EFGH-JKLM")).isTrue();
        assertThat(recoveryCodeService.consume(adminId, "ABCD-EFGH-JKLM")).isFalse();
    }
}
```

- [ ] **Step 2: Run the test and observe missing repository failures**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AuthPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because auth model/repository/service types do not exist.

- [ ] **Step 3: Create the admin model**

```java
// AdminStatus.java
package xyz.yychainsaw.portfolio.auth.model;

public enum AdminStatus { ACTIVE, DISABLED }
```

```java
// AdminUser.java
package xyz.yychainsaw.portfolio.auth.model;

import java.time.Instant;
import java.util.UUID;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;

public record AdminUser(
        UUID id,
        String username,
        String passwordHash,
        AdminStatus status,
        EncryptedTotpSecret totpSecret,
        Instant lastLoginAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
```

- [ ] **Step 4: Implement the admin JDBC repository**

Create `AdminUserRepository.java`:

```java
package xyz.yychainsaw.portfolio.auth.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;

@Repository
public final class AdminUserRepository {
    private final JdbcClient jdbc;
    private static final RowMapper<AdminUser> MAPPER = AdminUserRepository::map;

    public AdminUserRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long count() {
        return jdbc.sql("select count(*) from admin_user").query(Long.class).single();
    }

    public Optional<AdminUser> findByUsername(String username) {
        return jdbc.sql("""
                select id, username, password_hash, status, totp_key_version, totp_nonce,
                       totp_ciphertext, last_login_at, version, created_at, updated_at
                from admin_user where lower(username)=lower(:username)
                """).param("username", username).query(MAPPER).optional();
    }

    public Optional<AdminUser> findById(UUID id) {
        return jdbc.sql("""
                select id, username, password_hash, status, totp_key_version, totp_nonce,
                       totp_ciphertext, last_login_at, version, created_at, updated_at
                from admin_user where id=:id
                """).param("id", id).query(MAPPER).optional();
    }

    public void insert(AdminUser admin) {
        jdbc.sql("""
                insert into admin_user
                (id, singleton_key, username, password_hash, status, totp_key_version,
                 totp_nonce, totp_ciphertext, last_login_at, version, created_at, updated_at)
                values (:id, true, :username, :passwordHash, :status, :keyVersion,
                        :nonce, :ciphertext, :lastLoginAt, :version, :createdAt, :updatedAt)
                """)
                .param("id", admin.id()).param("username", admin.username())
                .param("passwordHash", admin.passwordHash()).param("status", admin.status().name())
                .param("keyVersion", admin.totpSecret().keyVersion())
                .param("nonce", admin.totpSecret().nonce()).param("ciphertext", admin.totpSecret().ciphertext())
                .param("lastLoginAt", admin.lastLoginAt()).param("version", admin.version())
                .param("createdAt", admin.createdAt()).param("updatedAt", admin.updatedAt()).update();
    }

    public void updateLastLogin(UUID id, Instant instant) {
        jdbc.sql("update admin_user set last_login_at=:instant, version=version+1 where id=:id")
                .param("instant", instant).param("id", id).update();
    }

    public void replaceCredentials(UUID id, String passwordHash, EncryptedTotpSecret secret) {
        int changed = jdbc.sql("""
                update admin_user set password_hash=:passwordHash,
                    totp_key_version=:keyVersion, totp_nonce=:nonce, totp_ciphertext=:ciphertext,
                    status='ACTIVE', version=version+1 where id=:id
                """).param("passwordHash", passwordHash).param("keyVersion", secret.keyVersion())
                .param("nonce", secret.nonce()).param("ciphertext", secret.ciphertext())
                .param("id", id).update();
        if (changed != 1) throw new IllegalStateException("admin credential update affected " + changed + " rows");
    }

    private static AdminUser map(ResultSet rs, int row) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new AdminUser(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                AdminStatus.valueOf(rs.getString("status")),
                new EncryptedTotpSecret(rs.getInt("totp_key_version"), rs.getBytes("totp_nonce"),
                        rs.getBytes("totp_ciphertext")),
                lastLogin == null ? null : lastLogin.toInstant(), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
```

- [ ] **Step 5: Implement hashed recovery-code replacement and atomic consumption**

Create `RecoveryCodeRepository.java`:

```java
package xyz.yychainsaw.portfolio.auth.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class RecoveryCodeRepository {
    private final JdbcClient jdbc;
    public RecoveryCodeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void replace(UUID adminId, List<String> hashes) {
        jdbc.sql("delete from totp_recovery_code where admin_id=:adminId")
                .param("adminId", adminId).update();
        for (String hash : hashes) {
            jdbc.sql("""
                    insert into totp_recovery_code(id, admin_id, code_hash)
                    values (:id, :adminId, :hash)
                    """).param("id", UUID.randomUUID()).param("adminId", adminId)
                    .param("hash", hash).update();
        }
    }

    public List<StoredCode> findUnused(UUID adminId) {
        return jdbc.sql("""
                select id, code_hash from totp_recovery_code
                where admin_id=:adminId and used_at is null order by created_at, id
                """).param("adminId", adminId)
                .query((rs, row) -> new StoredCode(rs.getObject("id", UUID.class), rs.getString("code_hash")))
                .list();
    }

    public boolean markUsed(UUID codeId) {
        return jdbc.sql("""
                update totp_recovery_code set used_at=current_timestamp
                where id=:id and used_at is null
                """).param("id", codeId).update() == 1;
    }

    public record StoredCode(UUID id, String hash) {}
}
```

Create `RecoveryCodeService.java`:

```java
package xyz.yychainsaw.portfolio.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;

@Service
public final class RecoveryCodeService {
    private final RecoveryCodeRepository repository;
    private final PasswordEncoder encoder;

    public RecoveryCodeService(RecoveryCodeRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    public List<String> hashAll(List<String> plaintextCodes) {
        List<String> hashes = new ArrayList<>(plaintextCodes.size());
        for (String code : plaintextCodes) hashes.add(encoder.encode(normalize(code)));
        return List.copyOf(hashes);
    }

    @Transactional
    public boolean consume(UUID adminId, String plaintextCode) {
        String normalized = normalize(plaintextCode);
        RecoveryCodeRepository.StoredCode matched = null;
        for (RecoveryCodeRepository.StoredCode stored : repository.findUnused(adminId)) {
            boolean current = encoder.matches(normalized, stored.hash());
            if (current && matched == null) matched = stored;
        }
        return matched != null && repository.markUsed(matched.id());
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
```

- [ ] **Step 6: Run persistence tests**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AuthPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; case-insensitive lookup works and the second recovery-code use returns `false`.

- [ ] **Step 7: Commit auth persistence**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/AuthPersistenceTest.java
git diff --cached --check
git commit -m "feat: persist administrator and recovery codes"
```

---

### Task 9: Implement durable Spring Session metadata, revocation, and custom expiry cleanup

**Files:**
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionStatus.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/SessionProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/TrustedClientAddressResolver.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/ClientSummaryFactory.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionCleanupJob.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionServiceTest.java`

**Interfaces:**
- Consumes: Official `spring_session` rows, V2 `admin_session_metadata`, UTC `Clock`, and `AuditService`.
- Produces: Stable-primary-ID lookup before session rotation, ACTIVE-only validation, durable revoke/expire reasons, masked client summaries, `SessionView.current` derived from the requesting public session ID, manual revoke-before-delete, and a one-minute cleanup job replacing Spring Session's disabled cleanup task.

- [ ] **Step 1: Write the session lifecycle integration test**

Create `AdminSessionServiceTest.java`:

```java
package xyz.yychainsaw.portfolio.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Transactional
class AdminSessionServiceTest extends PostgresIntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired AdminSessionService sessions;
    @Autowired AdminSessionCleanupJob cleanup;
    private UUID adminId;

    @BeforeEach
    void createAdmin() {
        adminId = UUID.randomUUID();
        jdbc.sql("""
                insert into admin_user(id, singleton_key, username, password_hash, status,
                    totp_key_version, totp_nonce, totp_ciphertext)
                values (:id, true, 'admin', '$argon2id$test', 'ACTIVE', 1, :nonce, :ciphertext)
                """).param("id", adminId).param("nonce", new byte[12])
                .param("ciphertext", new byte[17]).update();
    }

    @Test
    void publicSessionIdRotationKeepsMetadataLinkedToStablePrimaryId() {
        insertSpringSession("00000000-0000-0000-0000-000000000011", "before-rotation", Instant.now());
        UUID metadataId = sessions.start(adminId,
                "00000000-0000-0000-0000-000000000011", "Chrome/Windows @ 203.0.113.x");

        jdbc.sql("update spring_session set session_id='after-rotation' where session_id='before-rotation'").update();

        AdminSessionService.ActiveSession active = sessions.requireActive("after-rotation");
        assertThat(active.metadataId()).isEqualTo(metadataId);
        assertThat(active.springSessionPrimaryId())
                .isEqualTo("00000000-0000-0000-0000-000000000011");
        assertThat(sessions.list(adminId, "after-rotation").get(0).current()).isTrue();
    }

    @Test
    void revokePersistsReasonThenDeletesSpringSession() {
        insertSpringSession("00000000-0000-0000-0000-000000000012", "revoke-me", Instant.now());
        UUID metadataId = sessions.start(adminId,
                "00000000-0000-0000-0000-000000000012", "Firefox/Linux @ 2001:db8:1:2::");

        sessions.revoke(metadataId, adminId, "ADMIN_REQUEST");

        MapRow row = jdbc.sql("""
                select status, revocation_reason, session_primary_id
                from admin_session_metadata where id=:id
                """).param("id", metadataId)
                .query((rs, n) -> new MapRow(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        assertThat(row.status()).isEqualTo("REVOKED");
        assertThat(row.reason()).isEqualTo("ADMIN_REQUEST");
        assertThat(row.primaryId()).isNull();
    }

    @Test
    void cleanupExpiresAbsoluteLifetimeAndRetainsMetadata() {
        Instant created = Instant.now().minus(9, ChronoUnit.HOURS);
        insertSpringSession("00000000-0000-0000-0000-000000000013", "too-old", created);
        UUID metadataId = sessions.startAtForTest(adminId,
                "00000000-0000-0000-0000-000000000013", "Other/Other @ local", created);

        cleanup.runOnce();

        String status = jdbc.sql("select status from admin_session_metadata where id=:id")
                .param("id", metadataId).query(String.class).single();
        assertThat(status).isEqualTo("EXPIRED");
        String linkedPrimaryId = jdbc.sql("select session_primary_id from admin_session_metadata where id=:id")
                .param("id", metadataId).query(String.class).optional().orElse(null);
        assertThat(linkedPrimaryId).isNull();
        assertThat(jdbc.sql("select count(*) from spring_session where session_id='too-old'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void cleanupDeletesExpiredPasswordStageSessionWithoutMetadata() {
        insertSpringSession("00000000-0000-0000-0000-000000000014",
                "expired-pending", Instant.now());
        jdbc.sql("update spring_session set expiry_time=:expired where session_id='expired-pending'")
                .param("expired", Instant.now().minusSeconds(1).toEpochMilli()).update();
        cleanup.runOnce();
        assertThat(jdbc.sql("select count(*) from spring_session where session_id='expired-pending'")
                .query(Long.class).single()).isZero();
    }

    private void insertSpringSession(String primaryId, String sessionId, Instant created) {
        long now = created.toEpochMilli();
        jdbc.sql("""
                insert into spring_session(primary_id, session_id, creation_time, last_access_time,
                    max_inactive_interval, expiry_time, principal_name)
                values (:primaryId, :sessionId, :created, :accessed, 1800, :expiry, 'admin')
                """).param("primaryId", primaryId).param("sessionId", sessionId)
                .param("created", now).param("accessed", now)
                .param("expiry", Instant.now().plus(30, ChronoUnit.MINUTES).toEpochMilli()).update();
    }

    private record MapRow(String status, String reason, String primaryId) {}
}
```

- [ ] **Step 2: Run the test and observe missing session types**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminSessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because the session service and cleanup job do not exist.

- [ ] **Step 3: Add session state and configuration types**

```java
// AdminSessionStatus.java
package xyz.yychainsaw.portfolio.auth.session;

public enum AdminSessionStatus { ACTIVE, REVOKED, EXPIRED }
```

```java
// SessionProperties.java
package xyz.yychainsaw.portfolio.auth.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.security.session")
public record SessionProperties(Duration absoluteLifetime, Duration cleanupInterval) {
    public SessionProperties {
        if (absoluteLifetime.isNegative() || absoluteLifetime.isZero()
                || cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("session durations must be positive");
        }
    }
}
```

- [ ] **Step 4: Implement JDBC operations around stable Spring Session primary IDs**

Create `AdminSessionRepository.java`:

```java
package xyz.yychainsaw.portfolio.auth.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class AdminSessionRepository {
    private final JdbcClient jdbc;
    public AdminSessionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public String requirePrimaryId(String publicSessionId) {
        return jdbc.sql("select primary_id from spring_session where session_id=:sessionId")
                .param("sessionId", publicSessionId).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("Spring Session row is missing"));
    }

    public UUID insertActive(UUID adminId, String primaryId, String summary, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into admin_session_metadata
                (id, admin_id, session_primary_id, status, created_at, client_summary)
                values (:id, :adminId, :primaryId, 'ACTIVE', :createdAt, :summary)
                """).param("id", id).param("adminId", adminId).param("primaryId", primaryId)
                .param("createdAt", createdAt).param("summary", summary).update();
        return id;
    }

    public Optional<SessionRow> findByPublicSessionId(String publicSessionId) {
        return jdbc.sql("""
                select m.id, m.admin_id, m.session_primary_id, m.status, m.created_at,
                       s.last_access_time, s.expiry_time
                from spring_session s
                join admin_session_metadata m on m.session_primary_id=s.primary_id
                where s.session_id=:sessionId
                """).param("sessionId", publicSessionId).query((rs, row) -> new SessionRow(
                        rs.getObject("id", UUID.class), rs.getObject("admin_id", UUID.class),
                        rs.getString("session_primary_id"), AdminSessionStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("last_access_time"),
                        rs.getLong("expiry_time"))).optional();
    }

    public Optional<SessionRow> findByMetadataId(UUID id, UUID adminId) {
        return jdbc.sql("""
                select m.id, m.admin_id, m.session_primary_id, m.status, m.created_at,
                       coalesce(s.last_access_time, extract(epoch from m.last_activity_at)*1000, 0) last_access_time,
                       coalesce(s.expiry_time, 0) expiry_time
                from admin_session_metadata m left join spring_session s on s.primary_id=m.session_primary_id
                where m.id=:id and m.admin_id=:adminId
                """).param("id", id).param("adminId", adminId)
                .query((rs, row) -> new SessionRow(
                        rs.getObject("id", UUID.class), rs.getObject("admin_id", UUID.class),
                        rs.getString("session_primary_id"), AdminSessionStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("last_access_time"),
                        rs.getLong("expiry_time"))).optional();
    }

    public List<SessionView> list(UUID adminId, String currentPublicSessionId) {
        return jdbc.sql("""
                select m.id, m.status, m.created_at, m.ended_at, m.client_summary,
                       m.revocation_reason, coalesce(s.last_access_time,
                           extract(epoch from m.last_activity_at)*1000, 0) last_access_time,
                       coalesce(s.session_id=:currentSessionId, false) is_current
                from admin_session_metadata m left join spring_session s on s.primary_id=m.session_primary_id
                where m.admin_id=:adminId order by m.created_at desc
                """).param("adminId", adminId).param("currentSessionId", currentPublicSessionId)
                .query((rs, row) -> new SessionView(
                        rs.getObject("id", UUID.class), AdminSessionStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(), nullableInstant(rs.getTimestamp("ended_at")),
                        rs.getLong("last_access_time"), rs.getString("client_summary"),
                        rs.getString("revocation_reason"), rs.getBoolean("is_current"))).list();
    }

    public boolean markRevoked(UUID id, UUID adminId, String reason, Instant now) {
        return jdbc.sql("""
                update admin_session_metadata m set status='REVOKED', ended_at=:now,
                    last_activity_at=coalesce((select to_timestamp(s.last_access_time / 1000.0)
                        from spring_session s where s.primary_id=m.session_primary_id), :now),
                    revocation_reason=:reason, version=version+1
                where m.id=:id and m.admin_id=:adminId and m.status='ACTIVE'
                """).param("now", now).param("reason", reason).param("id", id)
                .param("adminId", adminId).update() == 1;
    }

    public List<TerminalSession> expireDue(Instant now, Instant absoluteCutoff) {
        return jdbc.sql("""
                update admin_session_metadata m set status='EXPIRED', ended_at=:now,
                    last_activity_at=to_timestamp(s.last_access_time / 1000.0),
                    revocation_reason=case when s.expiry_time <= :nowMillis
                        then 'IDLE_TIMEOUT' else 'ABSOLUTE_TIMEOUT' end,
                    version=version+1
                from spring_session s
                where m.status='ACTIVE' and m.session_primary_id=s.primary_id
                  and (s.expiry_time <= :nowMillis or m.created_at <= :absoluteCutoff)
                returning m.id, m.admin_id, m.session_primary_id, m.revocation_reason
                """).param("now", now).param("nowMillis", now.toEpochMilli())
                .param("absoluteCutoff", absoluteCutoff)
                .query((rs, row) -> new TerminalSession(rs.getObject("id", UUID.class),
                        rs.getObject("admin_id", UUID.class), rs.getString("session_primary_id"),
                        rs.getString("revocation_reason"))).list();
    }

    public List<TerminalSession> terminalSessionsStillLinked() {
        return jdbc.sql("""
                select id, admin_id, session_primary_id, revocation_reason
                from admin_session_metadata
                where status in ('REVOKED','EXPIRED') and session_primary_id is not null
                """).query((rs, row) -> new TerminalSession(rs.getObject("id", UUID.class),
                        rs.getObject("admin_id", UUID.class), rs.getString("session_primary_id"),
                        rs.getString("revocation_reason"))).list();
    }

    public void deleteSpringSession(String primaryId) {
        jdbc.sql("delete from spring_session where primary_id=:primaryId")
                .param("primaryId", primaryId).update();
    }

    public int deleteExpiredUnmanagedSpringSessions(long nowMillis) {
        return jdbc.sql("""
                delete from spring_session s
                where s.expiry_time<=:nowMillis
                  and not exists (select 1 from admin_session_metadata m
                                  where m.session_primary_id=s.primary_id)
                """).param("nowMillis", nowMillis).update();
    }

    private static Instant nullableInstant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record SessionRow(UUID metadataId, UUID adminId, String springSessionPrimaryId,
            AdminSessionStatus status, Instant createdAt, long lastAccessMillis, long expiryMillis) {}
    public record TerminalSession(UUID metadataId, UUID adminId, String primaryId, String reason) {}
    public record SessionView(UUID id, AdminSessionStatus status, Instant createdAt, Instant endedAt,
            long lastAccessMillis, String clientSummary, String reason, boolean current) {}
}
```

- [ ] **Step 5: Add trusted-proxy address resolution and a non-identifying client summary**

Create `TrustedClientAddressResolver.java`:

```java
package xyz.yychainsaw.portfolio.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public final class TrustedClientAddressResolver {
    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (isLoopback(remote)) {
            String real = request.getHeader("X-Real-IP");
            if (real != null && real.matches("[0-9A-Fa-f:.]{3,45}")) return real;
        }
        return remote == null ? "unknown" : remote;
    }

    private static boolean isLoopback(String value) {
        return "127.0.0.1".equals(value) || "0:0:0:0:0:0:0:1".equals(value) || "::1".equals(value);
    }
}
```

Create `ClientSummaryFactory.java`:

```java
package xyz.yychainsaw.portfolio.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public final class ClientSummaryFactory {
    private final TrustedClientAddressResolver addresses;
    public ClientSummaryFactory(TrustedClientAddressResolver addresses) { this.addresses = addresses; }

    public String create(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return browser(userAgent) + "/" + os(userAgent) + " @ " + mask(addresses.resolve(request));
    }

    private static String browser(String ua) {
        if (ua == null) return "Other";
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("Chrome/")) return "Chrome";
        if (ua.contains("Firefox/")) return "Firefox";
        if (ua.contains("Safari/")) return "Safari";
        return "Other";
    }
    private static String os(String ua) {
        if (ua == null) return "Other";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Mac OS")) return "macOS";
        if (ua.contains("Linux")) return "Linux";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
        return "Other";
    }
    private static String mask(String address) {
        if (address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return address.substring(0, address.lastIndexOf('.') + 1) + "x";
        }
        String[] parts = address.split(":");
        if (parts.length > 1) {
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < Math.min(4, parts.length); i++) {
                if (i > 0) masked.append(':');
                masked.append(parts[i]);
            }
            return masked.append("::").toString();
        }
        return "local";
    }
}
```

- [ ] **Step 6: Implement revoke-before-delete, ACTIVE validation, listing, and expiry**

Create `AdminSessionService.java`:

```java
package xyz.yychainsaw.portfolio.auth.session;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminSessionService {
    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);
    private final AdminSessionRepository repository;
    private final SessionProperties properties;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AdminSessionService(AdminSessionRepository repository, SessionProperties properties,
            AuditService audit, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    public String requireSpringPrimaryId(String publicSessionId) {
        return repository.requirePrimaryId(publicSessionId);
    }

    public UUID start(UUID adminId, String primaryId, String clientSummary) {
        return startAtForTest(adminId, primaryId, clientSummary, clock.instant());
    }

    UUID startAtForTest(UUID adminId, String primaryId, String summary, Instant createdAt) {
        return repository.insertActive(adminId, primaryId, summary, createdAt);
    }

    public ActiveSession requireActive(String publicSessionId) {
        Instant now = clock.instant();
        AdminSessionRepository.SessionRow row = repository.findByPublicSessionId(publicSessionId)
                .orElseThrow(AdminSessionService::unauthorized);
        if (row.status() != AdminSessionStatus.ACTIVE || row.expiryMillis() <= now.toEpochMilli()
                || !row.createdAt().plus(properties.absoluteLifetime()).isAfter(now)) {
            throw unauthorized();
        }
        return new ActiveSession(row.metadataId(), row.adminId(), row.springSessionPrimaryId(),
                row.createdAt(), Instant.ofEpochMilli(row.lastAccessMillis()));
    }

    public List<AdminSessionRepository.SessionView> list(UUID adminId, String currentPublicSessionId) {
        return repository.list(adminId, currentPublicSessionId);
    }

    public void revoke(UUID metadataId, UUID actorAdminId, String reason) {
        AdminSessionRepository.SessionRow row = repository.findByMetadataId(metadataId, actorAdminId)
                .orElseThrow(() -> new DomainException("SESSION_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of()));
        boolean changed = Boolean.TRUE.equals(transactions.execute(status -> {
            boolean marked = repository.markRevoked(metadataId, actorAdminId, reason, clock.instant());
            if (marked) {
                audit.record(new AuditCommand(actorAdminId, "SESSION_REVOKED", "ADMIN_SESSION",
                        metadataId.toString(), AuditOutcome.SUCCESS, null, Map.of("reason", reason)));
            }
            return marked;
        }));
        if (!changed) throw new DomainException("SESSION_NOT_ACTIVE", HttpStatus.CONFLICT, Map.of());

        deleteBestEffort(row.springSessionPrimaryId());
    }

    void deleteBestEffort(String primaryId) {
        if (primaryId == null) return;
        try {
            repository.deleteSpringSession(primaryId);
        } catch (DataAccessException exception) {
            log.warn("Session row deletion deferred for retry type={}", exception.getClass().getName());
        }
    }

    private static DomainException unauthorized() {
        return new DomainException("AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    public record ActiveSession(UUID metadataId, UUID adminId, String springSessionPrimaryId,
            Instant createdAt, Instant lastAccessAt) {}
}
```

Create `AdminSessionCleanupJob.java`:

```java
package xyz.yychainsaw.portfolio.auth.session;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class AdminSessionCleanupJob {
    private final AdminSessionRepository repository;
    private final AdminSessionService service;
    private final SessionProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public AdminSessionCleanupJob(AdminSessionRepository repository, AdminSessionService service,
            SessionProperties properties, AuditService audit, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${portfolio.security.session.cleanup-interval:PT1M}")
    public void scheduledRun() { runOnce(); }

    public void runOnce() {
        Instant now = clock.instant();
        for (AdminSessionRepository.TerminalSession expired :
                repository.expireDue(now, now.minus(properties.absoluteLifetime()))) {
            audit.record(new AuditCommand(expired.adminId(), "SESSION_EXPIRED", "ADMIN_SESSION",
                    expired.metadataId().toString(), AuditOutcome.SUCCESS, null,
                    Map.of("reason", expired.reason())));
        }
        for (AdminSessionRepository.TerminalSession terminal : repository.terminalSessionsStillLinked()) {
            service.deleteBestEffort(terminal.primaryId());
        }
        repository.deleteExpiredUnmanagedSpringSessions(now.toEpochMilli());
    }
}
```

- [ ] **Step 7: Run session lifecycle tests**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminSessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; simulated `session_id` rotation keeps the metadata FK; revoke and expiry retain terminal metadata and clear the FK after session deletion.

- [ ] **Step 8: Commit session metadata and cleanup**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/session
git diff --cached --check
git commit -m "feat: add durable administrator session metadata"
```

---

### Task 10: Add the one-time interactive `admin-bootstrap` CLI

**Files:**
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/persistence/AdminUserRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/SecretConsole.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/SystemSecretConsole.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/AdminBootstrapService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/AdminCliRunner.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/cli/AdminBootstrapServiceTest.java`

**Interfaces:**
- Consumes: Admin/recovery repositories, Argon2id, TOTP enrollment/encryption, recovery generation, and `AuditService`.
- Produces: A local-only `--portfolio.cli.command=admin-bootstrap` workflow that reads password/TOTP from a real terminal, inserts exactly one admin only after TOTP proof, prints recovery codes once, and refuses all subsequent bootstrap attempts.

- [ ] **Step 1: Write the bootstrap service integration test**

Create `AdminBootstrapServiceTest.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Transactional
class AdminBootstrapServiceTest extends PostgresIntegrationTestBase {
    @Autowired AdminBootstrapService bootstrap;
    @Autowired CodeGenerator codes;
    @Autowired TimeProvider time;
    @Autowired JdbcClient jdbc;

    @Test
    void createsExactlyOneAdminAfterTotpProofAndStoresOnlyHashes() throws Exception {
        char[] password = "Correct-Horse-7!Battery".toCharArray();
        AdminBootstrapService.Enrollment enrollment = bootstrap.prepare("portfolio-admin", password);
        Arrays.fill(password, '\0');
        String totp = codes.generate(enrollment.plaintextTotpSecret(), time.getTime() / 30);

        bootstrap.complete(enrollment, totp);

        assertThat(jdbc.sql("select count(*) from admin_user").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from totp_recovery_code where used_at is null")
                .query(Long.class).single()).isEqualTo(10);
        String hashes = jdbc.sql("select string_agg(code_hash, ',') from totp_recovery_code")
                .query(String.class).single();
        assertThat(enrollment.plaintextRecoveryCodes()).allSatisfy(code ->
                assertThat(hashes).doesNotContain(code));
        assertThat(jdbc.sql("select count(*) from audit_log where action='ADMIN_BOOTSTRAPPED'")
                .query(Long.class).single()).isEqualTo(1);

        assertThatThrownBy(() -> bootstrap.prepare("another-admin", "Another-Strong-8!Pass".toCharArray()))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("ADMIN_ALREADY_INITIALIZED");
    }
}
```

- [ ] **Step 2: Run the test and observe the missing bootstrap service**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminBootstrapServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because `AdminBootstrapService` does not exist.

- [ ] **Step 3: Make admin insertion null-safe before the CLI uses it**

In `AdminUserRepository.insert`, omit `last_login_at` from the insert rather than binding a null with an unknown JDBC type:

```java
public void insert(AdminUser admin) {
    jdbc.sql("""
            insert into admin_user
            (id, singleton_key, username, password_hash, status, totp_key_version,
             totp_nonce, totp_ciphertext, version, created_at, updated_at)
            values (:id, true, :username, :passwordHash, :status, :keyVersion,
                    :nonce, :ciphertext, :version, :createdAt, :updatedAt)
            """)
            .param("id", admin.id()).param("username", admin.username())
            .param("passwordHash", admin.passwordHash()).param("status", admin.status().name())
            .param("keyVersion", admin.totpSecret().keyVersion())
            .param("nonce", admin.totpSecret().nonce()).param("ciphertext", admin.totpSecret().ciphertext())
            .param("version", admin.version()).param("createdAt", admin.createdAt())
            .param("updatedAt", admin.updatedAt()).update();
}
```

- [ ] **Step 4: Implement a terminal-only secret console**

Create `SecretConsole.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

public interface SecretConsole {
    String readLine(String prompt);
    char[] readSecret(String prompt);
    void println(String value);
}
```

Create `SystemSecretConsole.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.io.Console;
import org.springframework.stereotype.Component;

@Component
public final class SystemSecretConsole implements SecretConsole {
    private final Console console;
    public SystemSecretConsole() {
        this.console = System.console();
    }
    private Console requireConsole() {
        if (console == null) {
            throw new IllegalStateException("administrator CLI requires an interactive system console");
        }
        return console;
    }
    @Override public String readLine(String prompt) { return requireConsole().readLine("%s", prompt); }
    @Override public char[] readSecret(String prompt) { return requireConsole().readPassword("%s", prompt); }
    @Override public void println(String value) { requireConsole().writer().println(value); }
}
```

There is deliberately no stdin fallback: redirected input could expose secrets in shell history or automation logs.

- [ ] **Step 5: Implement prepare/verify/commit bootstrap semantics**

Create `AdminBootstrapService.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.PasswordPolicy;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminBootstrapService {
    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final PasswordEncoder passwords;
    private final PasswordPolicy passwordPolicy;
    private final TotpService totp;
    private final RecoveryCodeGenerator recoveryGenerator;
    private final RecoveryCodeService recoveryService;
    private final AuditService audit;
    private final Clock clock;

    public AdminBootstrapService(AdminUserRepository admins, RecoveryCodeRepository recoveryCodes,
            PasswordEncoder passwords, PasswordPolicy passwordPolicy, TotpService totp,
            RecoveryCodeGenerator recoveryGenerator, RecoveryCodeService recoveryService,
            AuditService audit, Clock clock) {
        this.admins = admins; this.recoveryCodes = recoveryCodes; this.passwords = passwords;
        this.passwordPolicy = passwordPolicy; this.totp = totp; this.recoveryGenerator = recoveryGenerator;
        this.recoveryService = recoveryService; this.audit = audit; this.clock = clock;
    }

    public Enrollment prepare(String username, char[] password) {
        if (admins.count() != 0) {
            throw new DomainException("ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());
        }
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,64}")) {
            throw new DomainException("ADMIN_USERNAME_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("username", "账号须为 3–64 位 ASCII 字母、数字、点、下划线或连字符"));
        }
        UUID adminId = UUID.randomUUID();
        String hash;
        try {
            passwordPolicy.requireStrong(new String(password));
            hash = passwords.encode(new String(password));
        } finally {
            Arrays.fill(password, '\0');
        }
        TotpService.Enrollment totpEnrollment = totp.beginEnrollment(adminId, username);
        List<String> plaintextRecovery = recoveryGenerator.generate(10);
        return new Enrollment(adminId, username, hash, totpEnrollment.plaintextSecret(),
                totpEnrollment.encryptedSecret(), totpEnrollment.provisioningUri(),
                plaintextRecovery, recoveryService.hashAll(plaintextRecovery));
    }

    @Transactional
    public void complete(Enrollment enrollment, String totpCode) {
        if (!totp.verifyEnrollment(enrollment.plaintextTotpSecret(), totpCode)) {
            throw new DomainException("INVALID_BOOTSTRAP_TOTP", HttpStatus.UNAUTHORIZED, Map.of());
        }
        Instant now = clock.instant();
        try {
            admins.insert(new AdminUser(enrollment.adminId(), enrollment.username(), enrollment.passwordHash(),
                    AdminStatus.ACTIVE, enrollment.encryptedTotpSecret(), null, 0, now, now));
            recoveryCodes.replace(enrollment.adminId(), enrollment.recoveryCodeHashes());
        } catch (DuplicateKeyException exception) {
            throw new DomainException("ADMIN_ALREADY_INITIALIZED", HttpStatus.CONFLICT, Map.of());
        }
        audit.record(new AuditCommand(enrollment.adminId(), "ADMIN_BOOTSTRAPPED", "ADMIN",
                enrollment.adminId().toString(), AuditOutcome.SUCCESS, null, Map.of("channel", "LOCAL_CLI")));
    }

    public record Enrollment(UUID adminId, String username, String passwordHash,
            String plaintextTotpSecret, EncryptedTotpSecret encryptedTotpSecret,
            String provisioningUri, List<String> plaintextRecoveryCodes,
            List<String> recoveryCodeHashes) {
        public Enrollment {
            plaintextRecoveryCodes = List.copyOf(plaintextRecoveryCodes);
            recoveryCodeHashes = List.copyOf(recoveryCodeHashes);
        }
    }
}
```

- [ ] **Step 6: Wire the `admin-bootstrap` command without accepting secrets as arguments**

Create `AdminCliRunner.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AdminCliRunner implements ApplicationRunner {
    private final String command;
    private final SecretConsole console;
    private final AdminBootstrapService bootstrap;

    public AdminCliRunner(@Value("${portfolio.cli.command:}") String command,
                          SecretConsole console, AdminBootstrapService bootstrap) {
        this.command = command; this.console = console; this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (command.isBlank()) return;
        if (!"admin-bootstrap".equals(command)) {
            throw new IllegalArgumentException("unknown portfolio CLI command: " + command);
        }
        String username = console.readLine("Administrator username: ").trim();
        char[] password = console.readSecret("New password: ");
        char[] confirmation = console.readSecret("Repeat password: ");
        try {
            if (!Arrays.equals(password, confirmation)) throw new IllegalArgumentException("passwords differ");
            AdminBootstrapService.Enrollment enrollment = bootstrap.prepare(username, password);
            console.println("Add this provisioning URI to the authenticator:");
            console.println(enrollment.provisioningUri());
            char[] totp = console.readSecret("Current six-digit TOTP: ");
            try {
                bootstrap.complete(enrollment, new String(totp));
            } finally {
                Arrays.fill(totp, '\0');
            }
            console.println("Store these one-time recovery codes offline; they will not be shown again:");
            enrollment.plaintextRecoveryCodes().forEach(console::println);
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }
}
```

- [ ] **Step 7: Run bootstrap tests**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminBootstrapServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; the database contains one admin, ten unused hashes, no plaintext code, and one bootstrap audit record.

- [ ] **Step 8: Smoke-test the real interactive command against the development database**

Load `.env.local` into the current PowerShell process without echoing values, package, and run from a real terminal:

```powershell
Get-Content .env.local | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am package
java -jar backend-parent\portfolio-server\target\portfolio-server-1.0.0-SNAPSHOT.jar `
  --portfolio.cli.command=admin-bootstrap
```

Expected: password input is not echoed; an `otpauth://` URI appears only in the terminal; a valid current TOTP creates the admin and prints exactly ten recovery codes. Running the command a second time exits non-zero with `ADMIN_ALREADY_INITIALIZED` and creates no second row.

- [ ] **Step 9: Commit bootstrap CLI**

```powershell
git add backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/cli
git diff --cached --check
git commit -m "feat: add one-time administrator bootstrap CLI"
```

---

### Task 11: Implement Spring Security password-plus-second-factor login, CSRF, and ACTIVE-session enforcement

**Files:**
- Modify: `.env.example`
- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/AdminPrincipal.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/PendingSecondFactor.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/SecondFactorMethod.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/LoginSubjectHasher.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/AdminAuthenticationService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/AdminAuthController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/AdminSecurityController.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/SecurityCurrentAdminProvider.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/SessionMetadataEnforcementFilter.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/SecurityProblemWriter.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/config/SecurityConfiguration.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/web/AdminAuthenticationFlowTest.java`

**Interfaces:**
- Consumes: `RateLimiter`, admin/TOTP/recovery persistence, stable Spring Session primary-ID lookup, metadata service, audit, and ProblemDetail tracing.
- Produces: `GET /api/admin/auth/csrf`, password stage, TOTP/recovery stage, `GET /me`, CSRF-protected logout, session list/revoke APIs whose rows identify the current session, a Spring Security context containing `AdminPrincipal`, and concrete `CurrentAdminProvider#requireAdminId()`.

- [ ] **Step 1: Write one full security-flow integration test before configuring Spring Security**

Create `AdminAuthenticationFlowTest.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminAuthenticationFlowTest extends PostgresIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AdminUserRepository admins;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired RecoveryCodeService recoveryCodeService;
    @Autowired RecoveryCodeGenerator recoveryGenerator;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TotpService totp;
    @Autowired CodeGenerator codeGenerator;
    @Autowired TimeProvider timeProvider;
    private String plaintextTotp;

    @BeforeEach
    void createAdmin() {
        UUID id = UUID.randomUUID();
        TotpService.Enrollment enrollment = totp.beginEnrollment(id, "portfolio-admin");
        plaintextTotp = enrollment.plaintextSecret();
        Instant now = Instant.now();
        admins.insert(new AdminUser(id, "portfolio-admin", passwordEncoder.encode("Correct-Horse-7!Battery"),
                AdminStatus.ACTIVE, enrollment.encryptedSecret(), null, 0, now, now));
        recoveryCodes.replace(id, recoveryCodeService.hashAll(List.of("ABCD-EFGH-JKLM")));
    }

    @Test
    void csrfPasswordTotpRotationMetadataAndRevokeFormOneFlow() throws Exception {
        mvc.perform(post("/api/admin/auth/password")
                        .contentType("application/json")
                        .content("{\"username\":\"portfolio-admin\",\"password\":\"Correct-Horse-7!Battery\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        MvcResult password = mvc.perform(post("/api/admin/auth/password").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"portfolio-admin\",\"password\":\"Correct-Horse-7!Battery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("SECOND_FACTOR"))
                .andReturn();
        Cookie pendingCookie = password.getResponse().getCookie("PORTFOLIO_SESSION");
        assertThat(pendingCookie).isNotNull();
        String beforeRotation = pendingCookie.getValue();
        String code = codeGenerator.generate(plaintextTotp, timeProvider.getTime() / 30);

        MvcResult authenticated = mvc.perform(post("/api/admin/auth/second-factor")
                        .cookie(pendingCookie).with(csrf()).contentType("application/json")
                        .content(json.writeValueAsBytes(new AdminAuthController.SecondFactorRequest(
                                SecondFactorMethod.TOTP, code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("portfolio-admin"))
                .andReturn();
        Cookie authenticatedCookie = authenticated.getResponse().getCookie("PORTFOLIO_SESSION");
        assertThat(authenticatedCookie).isNotNull();
        assertThat(authenticatedCookie.getValue()).isNotEqualTo(beforeRotation);

        MvcResult listed = mvc.perform(get("/api/admin/security/sessions").cookie(authenticatedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].current").value(true))
                .andReturn();
        JsonNode sessions = json.readTree(listed.getResponse().getContentAsByteArray());
        String metadataId = sessions.get(0).get("id").asText();

        mvc.perform(post("/api/admin/security/sessions/" + metadataId + "/revoke")
                        .cookie(authenticatedCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/auth/me").cookie(authenticatedCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsGenericAndEventuallyRateLimited() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            mvc.perform(post("/api/admin/auth/password").with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"portfolio-admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        }
        mvc.perform(post("/api/admin/auth/password").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"portfolio-admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
```

- [ ] **Step 2: Run the security flow and observe the expected 404/missing-type failures**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminAuthenticationFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because auth web types/endpoints do not exist.

- [ ] **Step 3: Add serializable principal, pending challenge, and second-factor method**

```java
// AdminPrincipal.java
package xyz.yychainsaw.portfolio.auth.web;

import java.io.Serializable;
import java.util.UUID;

public record AdminPrincipal(UUID adminId, String username) implements Serializable {}
```

```java
// PendingSecondFactor.java
package xyz.yychainsaw.portfolio.auth.web;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record PendingSecondFactor(UUID adminId, String username, Instant issuedAt, int failures)
        implements Serializable {
    public static final String SESSION_KEY = PendingSecondFactor.class.getName();
    public PendingSecondFactor failedAgain() {
        return new PendingSecondFactor(adminId, username, issuedAt, failures + 1);
    }
}
```

```java
// SecondFactorMethod.java
package xyz.yychainsaw.portfolio.auth.web;

public enum SecondFactorMethod { TOTP, RECOVERY_CODE }
```

- [ ] **Step 4: Hash rate-limit subjects without retaining username or address**

Create `LoginSubjectHasher.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.auth.session.TrustedClientAddressResolver;

@Component
public final class LoginSubjectHasher {
    private final TrustedClientAddressResolver addresses;
    public LoginSubjectHasher(TrustedClientAddressResolver addresses) { this.addresses = addresses; }

    public String hash(HttpServletRequest request, String username) {
        String material = (username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                + "\n" + addresses.resolve(request);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
```

- [ ] **Step 5: Implement password stage and final second-factor authentication**

Create `AdminAuthenticationService.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.TotpProperties;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.session.ClientSummaryFactory;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;

@Service
public final class AdminAuthenticationService {
    private final AdminUserRepository admins;
    private final PasswordEncoder passwords;
    private final TotpService totp;
    private final RecoveryCodeService recoveryCodes;
    private final TotpProperties totpProperties;
    private final RateLimiter rateLimiter;
    private final LoginSubjectHasher subjects;
    private final AdminSessionService sessions;
    private final ClientSummaryFactory summaries;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contexts;
    private final AuditService audit;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminAuthenticationService(AdminUserRepository admins, PasswordEncoder passwords,
            TotpService totp, RecoveryCodeService recoveryCodes, TotpProperties totpProperties,
            RateLimiter rateLimiter, LoginSubjectHasher subjects, AdminSessionService sessions,
            ClientSummaryFactory summaries, SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contexts, AuditService audit, Clock clock) {
        this.admins = admins; this.passwords = passwords; this.totp = totp;
        this.recoveryCodes = recoveryCodes; this.totpProperties = totpProperties;
        this.rateLimiter = rateLimiter; this.subjects = subjects; this.sessions = sessions;
        this.summaries = summaries; this.sessionStrategy = sessionStrategy; this.contexts = contexts;
        this.audit = audit; this.clock = clock;
        this.dummyPasswordHash = passwords.encode("portfolio-dummy-password-not-used-for-login");
    }

    public Instant passwordStage(String username, String password, HttpServletRequest request) {
        RateLimitDecision decision = rateLimiter.consume("admin-login", subjects.hash(request, username));
        if (!decision.allowed()) {
            throw new DomainException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                    Map.of("retryAfterSeconds", Long.toString(decision.retryAfterSeconds())));
        }
        AdminUser admin = admins.findByUsername(username).orElse(null);
        boolean passwordMatches = passwords.matches(password,
                admin == null ? dummyPasswordHash : admin.passwordHash());
        boolean accepted = admin != null && admin.status() == AdminStatus.ACTIVE && passwordMatches;
        if (!accepted) {
            audit.record(new AuditCommand(null, "AUTH_PASSWORD_REJECTED", "ADMIN", null,
                    AuditOutcome.FAILURE, null, Map.of("stage", "PASSWORD")));
            throw authenticationFailed();
        }
        Instant expiresAt = clock.instant().plus(totpProperties.pendingLifetime());
        request.getSession(true).setAttribute(PendingSecondFactor.SESSION_KEY,
                new PendingSecondFactor(admin.id(), admin.username(), clock.instant(), 0));
        audit.record(new AuditCommand(admin.id(), "AUTH_PASSWORD_ACCEPTED", "ADMIN",
                admin.id().toString(), AuditOutcome.SUCCESS, null, Map.of("next", "SECOND_FACTOR")));
        return expiresAt;
    }

    @Transactional
    public AdminPrincipal secondFactor(SecondFactorMethod method, String code,
            HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        PendingSecondFactor pending = session == null ? null
                : (PendingSecondFactor) session.getAttribute(PendingSecondFactor.SESSION_KEY);
        Instant now = clock.instant();
        if (pending == null || !pending.issuedAt().plus(totpProperties.pendingLifetime()).isAfter(now)
                || pending.failures() >= totpProperties.maxSecondFactorAttempts()) {
            if (session != null) session.removeAttribute(PendingSecondFactor.SESSION_KEY);
            throw authenticationFailed();
        }
        AdminUser admin = admins.findById(pending.adminId()).orElseThrow(AdminAuthenticationService::authenticationFailed);
        boolean accepted = admin.status() == AdminStatus.ACTIVE && switch (method) {
            case TOTP -> totp.verify(admin.id(), admin.totpSecret(), code);
            case RECOVERY_CODE -> recoveryCodes.consume(admin.id(), code);
        };
        if (!accepted) {
            PendingSecondFactor failed = pending.failedAgain();
            if (failed.failures() >= totpProperties.maxSecondFactorAttempts()) {
                session.removeAttribute(PendingSecondFactor.SESSION_KEY);
            } else {
                session.setAttribute(PendingSecondFactor.SESSION_KEY, failed);
            }
            audit.record(new AuditCommand(admin.id(), "AUTH_SECOND_FACTOR_REJECTED", "ADMIN",
                    admin.id().toString(), AuditOutcome.FAILURE, null, Map.of("method", method.name())));
            throw authenticationFailed();
        }

        String stablePrimaryId = sessions.requireSpringPrimaryId(session.getId());
        sessions.start(admin.id(), stablePrimaryId, summaries.create(request));
        AdminPrincipal principal = new AdminPrincipal(admin.id(), admin.username());
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        session.removeAttribute(PendingSecondFactor.SESSION_KEY);
        admins.updateLastLogin(admin.id(), now);
        audit.record(new AuditCommand(admin.id(), "AUTH_LOGIN_SUCCEEDED", "ADMIN",
                admin.id().toString(), AuditOutcome.SUCCESS, null, Map.of("method", method.name())));
        return principal;
    }

    private static DomainException authenticationFailed() {
        return new DomainException("AUTHENTICATION_FAILED", HttpStatus.UNAUTHORIZED, Map.of());
    }
}
```

- [ ] **Step 6: Implement controllers and the exact CurrentAdminProvider**

Create `AdminAuthController.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final AdminAuthenticationService authentication;
    private final SecurityCurrentAdminProvider currentAdmin;

    public AdminAuthController(AdminAuthenticationService authentication,
                               SecurityCurrentAdminProvider currentAdmin) {
        this.authentication = authentication; this.currentAdmin = currentAdmin;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @PostMapping("/password")
    public PasswordStageResponse password(@Valid @RequestBody PasswordStageRequest body,
                                           HttpServletRequest request) {
        return new PasswordStageResponse("SECOND_FACTOR",
                authentication.passwordStage(body.username(), body.password(), request));
    }

    @PostMapping("/second-factor")
    public MeResponse secondFactor(@Valid @RequestBody SecondFactorRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        AdminPrincipal principal = authentication.secondFactor(body.method(), body.code(), request, response);
        return new MeResponse(principal.adminId(), principal.username());
    }

    @GetMapping("/me")
    public MeResponse me() {
        AdminPrincipal principal = currentAdmin.requirePrincipal();
        return new MeResponse(principal.adminId(), principal.username());
    }

    public record PasswordStageRequest(@NotBlank String username, @NotBlank String password) {}
    public record SecondFactorRequest(@NotNull SecondFactorMethod method, @NotBlank String code) {}
    public record PasswordStageResponse(String next, Instant expiresAt) {}
    public record CsrfResponse(String headerName, String parameterName, String token) {}
    public record MeResponse(java.util.UUID id, String username) {}
}
```

The CSRF endpoint remains anonymous so the public contact and analytics clients can protect their POSTs without creating an authenticated session. Its token response is always `no-store`; all unsafe `/api/admin/**` and `/api/public/**` requests still pass through the same `CsrfFilter`.

The deliberately restrictive `default-src 'none'` CSP writer is request-matched to `/api/**` only. It must never be applied globally: plan 03's Spring-rendered public HTML needs its release scripts, styles, responsive media, and allowlisted video frames, and plan 07 renders that complete page CSP at the sole public Nginx boundary. Referrer policy is identical to plan 07 so duplicate security headers do not conflict.

Create `SecurityCurrentAdminProvider.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
public final class SecurityCurrentAdminProvider implements CurrentAdminProvider {
    @Override public UUID requireAdminId() { return requirePrincipal().adminId(); }

    public AdminPrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new DomainException("AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
        }
        return principal;
    }
}
```

Create `AdminSecurityController.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;

@RestController
@RequestMapping("/api/admin/security/sessions")
public class AdminSecurityController {
    private final SecurityCurrentAdminProvider current;
    private final AdminSessionService sessions;
    public AdminSecurityController(SecurityCurrentAdminProvider current, AdminSessionService sessions) {
        this.current = current; this.sessions = sessions;
    }
    @GetMapping
    public List<AdminSessionRepository.SessionView> list(HttpServletRequest request) {
        return sessions.list(current.requireAdminId(), request.getSession(false).getId());
    }
    @PostMapping("/{metadataId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID metadataId) {
        UUID adminId = current.requireAdminId();
        sessions.revoke(metadataId, adminId, "ADMIN_REQUEST");
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 7: Write security-filter ProblemDetail responses and ACTIVE metadata enforcement**

Create `SecurityProblemWriter.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

@Component
public final class SecurityProblemWriter {
    private final ObjectMapper json;
    public SecurityProblemWriter(ObjectMapper json) { this.json = json; }
    public void write(HttpServletResponse response, HttpStatus status, String code, String title)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, title);
        problem.setTitle(title);
        problem.setType(URI.create("urn:portfolio:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
        problem.setProperty("code", code);
        problem.setProperty("traceId", TraceIds.current());
        problem.setProperty("fieldErrors", Map.of());
        json.writeValue(response.getOutputStream(), problem);
    }
}
```

Create `SessionMetadataEnforcementFilter.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
public final class SessionMetadataEnforcementFilter extends OncePerRequestFilter {
    private final AdminSessionService sessions;
    private final SecurityProblemWriter problems;
    public SessionMetadataEnforcementFilter(AdminSessionService sessions, SecurityProblemWriter problems) {
        this.sessions = sessions; this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            HttpSession session = request.getSession(false);
            try {
                AdminSessionService.ActiveSession active = sessions.requireActive(session == null ? "" : session.getId());
                if (!active.adminId().equals(principal.adminId())) throw new IllegalStateException("session owner mismatch");
            } catch (DomainException | IllegalStateException exception) {
                SecurityContextHolder.clearContext();
                response.setHeader("Clear-Site-Data", "\"cookies\"");
                problems.write(response, HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "需要重新登录");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 8: Configure Spring Security, CSRF cookie/header, explicit development CORS, and headers**

Add to `.env.example`:

```dotenv
PORTFOLIO_ALLOW_DEVELOPMENT_CORS=true
```

Add under `portfolio.web` in `application.yml`:

```yaml
allow-development-cors: ${PORTFOLIO_ALLOW_DEVELOPMENT_CORS:false}
```

Create `SecurityConfiguration.java`:

```java
package xyz.yychainsaw.portfolio.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import xyz.yychainsaw.portfolio.auth.web.SecurityProblemWriter;
import xyz.yychainsaw.portfolio.auth.web.SessionMetadataEnforcementFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            SessionMetadataEnforcementFilter sessionFilter,
            SecurityProblemWriter problems, CorsConfigurationSource cors,
            SecurityContextRepository contexts) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("XSRF-TOKEN");
        csrf.setHeaderName("X-XSRF-TOKEN");
        csrf.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName("_csrf");
        ContentSecurityPolicyHeaderWriter apiCsp = new ContentSecurityPolicyHeaderWriter(
                "default-src 'none'; frame-ancestors 'none'");
        DelegatingRequestMatcherHeaderWriter apiOnlyCsp =
                new DelegatingRequestMatcherHeaderWriter(
                        AntPathRequestMatcher.antMatcher("/api/**"), apiCsp);

        return http
                .httpBasic(config -> config.disable())
                .formLogin(config -> config.disable())
                .logout(config -> config.disable())
                .requestCache(config -> config.disable())
                .securityContext(config -> config
                        .securityContextRepository(contexts).requireExplicitSave(true))
                .cors(config -> config.configurationSource(cors))
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrfHandler))
                .sessionManagement(config -> config
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/admin/auth/csrf", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/password",
                                "/api/admin/auth/second-factor").permitAll()
                        .requestMatchers("/api/admin/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, exception) -> problems.write(
                                response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "需要登录"))
                        .accessDeniedHandler(accessDeniedHandler(problems)))
                .headers(headers -> headers
                        .addHeaderWriter(apiOnlyCsp)
                        .contentTypeOptions(options -> {})
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(policy -> policy.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=()"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .addFilterAfter(sessionFilter, SecurityContextHolderFilter.class)
                .build();
    }

    private AccessDeniedHandler accessDeniedHandler(SecurityProblemWriter problems) {
        return (request, response, exception) -> {
            boolean csrf = exception instanceof MissingCsrfTokenException
                    || exception instanceof InvalidCsrfTokenException;
            problems.write(response, HttpStatus.FORBIDDEN, csrf ? "CSRF_INVALID" : "ACCESS_DENIED",
                    csrf ? "CSRF 校验失败" : "禁止访问");
        };
    }

    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${portfolio.web.allow-development-cors:false}") boolean developmentCors,
            @Value("${portfolio.web.development-origin:http://localhost:5174}") String origin) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(developmentCors ? List.of(origin) : List.of());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
```

- [ ] **Step 9: Add CSRF-protected logout before deleting its Spring Session row**

Add this method and dependencies to `AdminAuthController`:

```java
private final xyz.yychainsaw.portfolio.auth.session.AdminSessionService sessions;
private final org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler logout =
        new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler();

@PostMapping("/logout")
public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    AdminPrincipal principal = currentAdmin.requirePrincipal();
    var active = sessions.requireActive(request.getSession(false).getId());
    sessions.revoke(active.metadataId(), principal.adminId(), "LOGOUT");
    logout.logout(request, response, org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication());
    return ResponseEntity.noContent().build();
}
```

Update the constructor so `AdminSessionService sessions` is assigned. This is a complete constructor after the change:

```java
public AdminAuthController(AdminAuthenticationService authentication,
        SecurityCurrentAdminProvider currentAdmin,
        xyz.yychainsaw.portfolio.auth.session.AdminSessionService sessions) {
    this.authentication = authentication;
    this.currentAdmin = currentAdmin;
    this.sessions = sessions;
}
```

- [ ] **Step 10: Run the complete security integration flow**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminAuthenticationFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. Missing CSRF returns `403 CSRF_INVALID`; password alone is not authenticated; TOTP rotates the public session ID while metadata remains linked; the list marks that row `current=true`; five failed password attempts are generic and the sixth returns 429; revoking that current row succeeds and makes the session immediately unusable.

- [ ] **Step 11: Add a recovery-code test to the same flow**

Add this test method to `AdminAuthenticationFlowTest`:

```java
@Test
void recoveryCodeCanAuthenticateOnlyOnce() throws Exception {
    MvcResult password = mvc.perform(post("/api/admin/auth/password").with(csrf())
                    .contentType("application/json")
                    .content("{\"username\":\"portfolio-admin\",\"password\":\"Correct-Horse-7!Battery\"}"))
            .andExpect(status().isOk()).andReturn();
    Cookie pendingCookie = password.getResponse().getCookie("PORTFOLIO_SESSION");
    assertThat(pendingCookie).isNotNull();
    mvc.perform(post("/api/admin/auth/second-factor").cookie(pendingCookie).with(csrf())
                    .contentType("application/json")
                    .content("{\"method\":\"RECOVERY_CODE\",\"code\":\"ABCD-EFGH-JKLM\"}"))
            .andExpect(status().isOk());

    assertThat(recoveryCodes.findUnused(admins.findByUsername("portfolio-admin").orElseThrow().id()))
            .isEmpty();

    MvcResult retryPassword = mvc.perform(post("/api/admin/auth/password").with(csrf())
                    .contentType("application/json")
                    .content("{\"username\":\"portfolio-admin\",\"password\":\"Correct-Horse-7!Battery\"}"))
            .andExpect(status().isOk()).andReturn();
    Cookie retryCookie = retryPassword.getResponse().getCookie("PORTFOLIO_SESSION");
    assertThat(retryCookie).isNotNull();
    mvc.perform(post("/api/admin/auth/second-factor").cookie(retryCookie).with(csrf())
                    .contentType("application/json")
                    .content("{\"method\":\"RECOVERY_CODE\",\"code\":\"ABCD-EFGH-JKLM\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
}
```

Run the same test command. Expected: PASS and the used code has `used_at` populated.

- [ ] **Step 12: Commit the complete web authentication boundary**

```powershell
git add .env.example backend-parent/portfolio-server/src/main/resources/application.yml `
        backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web `
        backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/config/SecurityConfiguration.java `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/web
git diff --cached --check
git commit -m "feat: add two-stage administrator authentication"
```

---

### Task 12: Add authenticated password, TOTP, and recovery-code security settings

**Files:**

- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/crypto/EncryptedTotpSecret.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/persistence/AdminUserRepository.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionRepository.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionService.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/LoginSubjectHasher.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/PendingTotpEnrollment.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/AdminSecuritySettingsService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/web/AdminSecuritySettingsController.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/web/AdminSecuritySettingsTest.java`

**Interfaces:**

- Consumes: Authenticated `AdminPrincipal`, current ACTIVE session metadata, Argon2id, current TOTP, versioned TOTP encryption, recovery-code hashing, `RateLimiter`, and `AuditService`.
- Produces: `POST /api/admin/security/password`, `POST /api/admin/security/totp/enrollment`, `POST /api/admin/security/totp/confirm`, and `POST /api/admin/security/recovery-codes/regenerate`. Every endpoint is authenticated, CSRF-protected, no-store, subject to policy `admin-security`, auditable, and preserves only the current session after success.
- Preserves: TOTP enrollment plaintext appears only in the one enrollment response. The current Spring Session stores a ten-minute `PendingTotpEnrollment` containing only the encrypted candidate secret, enrollment UUID, timestamps, and failure count.

- [ ] **Step 1: Write the end-to-end security-settings tests first**

Create `AdminSecuritySettingsTest.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminSecuritySettingsTest extends PostgresIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AdminUserRepository admins;
    @Autowired RecoveryCodeRepository recoveryCodes;
    @Autowired RecoveryCodeService recoveryCodeService;
    @Autowired PasswordEncoder passwords;
    @Autowired TotpService totp;
    @Autowired CodeGenerator codes;
    @Autowired TimeProvider time;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private UUID adminId;
    private String oldTotpSecret;

    @BeforeEach
    void createAdmin() {
        adminId = UUID.randomUUID();
        TotpService.Enrollment enrollment = totp.beginEnrollment(adminId, "portfolio-admin");
        oldTotpSecret = enrollment.plaintextSecret();
        Instant now = Instant.now();
        admins.insert(new AdminUser(adminId, "portfolio-admin",
                passwords.encode("Correct-Horse-7!Battery"), AdminStatus.ACTIVE,
                enrollment.encryptedSecret(), null, 0, now, now));
        recoveryCodes.replace(adminId,
                recoveryCodeService.hashAll(List.of("ABCD-EFGH-JKLM")));
    }

    @Test
    void passwordChangeRequiresCsrfAndReauthThenKeepsOnlyCurrentSession() throws Exception {
        Cookie current = login("Correct-Horse-7!Battery", oldTotpSecret);
        Cookie other = login("Correct-Horse-7!Battery", oldTotpSecret);
        String request = """
                {"currentPassword":"Correct-Horse-7!Battery","currentTotp":"%s",
                 "newPassword":"Changed-Horse-8!Battery"}
                """.formatted(currentCode(oldTotpSecret));

        mvc.perform(post("/api/admin/security/password").cookie(current)
                        .contentType("application/json").content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        mvc.perform(post("/api/admin/security/password").cookie(current).with(csrf())
                        .contentType("application/json").content(request))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        assertThat(passwords.matches("Changed-Horse-8!Battery",
                admins.findById(adminId).orElseThrow().passwordHash())).isTrue();
        mvc.perform(get("/api/admin/auth/me").cookie(current)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/auth/me").cookie(other)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("select count(*) from audit_log where action='ADMIN_PASSWORD_CHANGED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void totpEnrollmentStoresOnlyEncryptedPendingStateAndConfirmReturnsCodesOnce() throws Exception {
        Cookie current = login("Correct-Horse-7!Battery", oldTotpSecret);
        Cookie other = login("Correct-Horse-7!Battery", oldTotpSecret);
        MvcResult started = mvc.perform(post("/api/admin/security/totp/enrollment")
                        .cookie(current).with(csrf()).contentType("application/json")
                        .content("""
                                {"currentPassword":"Correct-Horse-7!Battery","currentTotp":"%s"}
                                """.formatted(currentCode(oldTotpSecret))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.provisioningUri").value(containsString("otpauth://totp/")))
                .andReturn();

        mvc.perform(get("/api/admin/auth/me").cookie(other)).andExpect(status().isUnauthorized());
        JsonNode enrollment = json.readTree(started.getResponse().getContentAsByteArray());
        String enrollmentId = enrollment.path("enrollmentId").asText();
        String newSecret = queryValue(enrollment.path("provisioningUri").asText(), "secret");
        String newCode = currentCode(newSecret);

        MvcResult confirmed = mvc.perform(post("/api/admin/security/totp/confirm")
                        .cookie(current).with(csrf()).contentType("application/json")
                        .content("""
                                {"enrollmentId":"%s","newTotp":"%s"}
                                """.formatted(enrollmentId, newCode)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();

        assertThat(totp.verify(adminId, admins.findById(adminId).orElseThrow().totpSecret(), newCode)).isTrue();
        mvc.perform(post("/api/admin/security/totp/confirm")
                        .cookie(current).with(csrf()).contentType("application/json")
                        .content("""
                                {"enrollmentId":"%s","newTotp":"%s"}
                                """.formatted(enrollmentId, newCode)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOTP_ENROLLMENT_EXPIRED"));
        JsonNode returnedCodes = json.readTree(confirmed.getResponse().getContentAsByteArray())
                .path("recoveryCodes");
        String storedHashes = jdbc.sql("select string_agg(code_hash, ',') from totp_recovery_code")
                .query(String.class).single();
        returnedCodes.forEach(code -> assertThat(storedHashes).doesNotContain(code.asText()));
        assertThat(jdbc.sql("select count(*) from audit_log where action='ADMIN_TOTP_CHANGED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void invalidReauthenticationIsZeroWriteAndSensitiveOperationsAreLimited() throws Exception {
        Cookie current = login("Correct-Horse-7!Battery", oldTotpSecret);
        Cookie other = login("Correct-Horse-7!Battery", oldTotpSecret);
        String originalPasswordHash = admins.findById(adminId).orElseThrow().passwordHash();
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/admin/security/recovery-codes/regenerate")
                            .cookie(current).with(csrf()).contentType("application/json")
                            .content("""
                                    {"currentPassword":"Correct-Horse-7!Battery","currentTotp":"invalid"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/admin/security/recovery-codes/regenerate")
                        .cookie(current).with(csrf()).contentType("application/json")
                        .content("""
                                {"currentPassword":"Correct-Horse-7!Battery","currentTotp":"invalid"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(recoveryCodes.findUnused(adminId)).hasSize(1);
        assertThat(admins.findById(adminId).orElseThrow().passwordHash())
                .isEqualTo(originalPasswordHash);
        mvc.perform(get("/api/admin/auth/me").cookie(current)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/auth/me").cookie(other)).andExpect(status().isOk());
    }

    @Test
    void recoveryCodeRegenerationReturnsTenCodesOnceAndRevokesOtherSessions() throws Exception {
        Cookie current = login("Correct-Horse-7!Battery", oldTotpSecret);
        Cookie other = login("Correct-Horse-7!Battery", oldTotpSecret);
        MvcResult regenerated = mvc.perform(post("/api/admin/security/recovery-codes/regenerate")
                        .cookie(current).with(csrf()).contentType("application/json")
                        .content("""
                                {"currentPassword":"Correct-Horse-7!Battery","currentTotp":"%s"}
                                """.formatted(currentCode(oldTotpSecret))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();
        JsonNode returned = json.readTree(regenerated.getResponse().getContentAsByteArray())
                .path("recoveryCodes");
        assertThat(recoveryCodeService.consume(adminId, "ABCD-EFGH-JKLM")).isFalse();
        assertThat(recoveryCodeService.consume(adminId, returned.get(0).asText())).isTrue();
        mvc.perform(get("/api/admin/auth/me").cookie(current)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/auth/me").cookie(other)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("select count(*) from audit_log where action='ADMIN_RECOVERY_CODES_REGENERATED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    private Cookie login(String password, String secret) throws Exception {
        MvcResult first = mvc.perform(post("/api/admin/auth/password").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"portfolio-admin\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie pending = first.getResponse().getCookie("PORTFOLIO_SESSION");
        assertThat(pending).isNotNull();
        MvcResult second = mvc.perform(post("/api/admin/auth/second-factor")
                        .cookie(pending).with(csrf()).contentType("application/json")
                        .content("{\"method\":\"TOTP\",\"code\":\"" + currentCode(secret) + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie authenticated = second.getResponse().getCookie("PORTFOLIO_SESSION");
        assertThat(authenticated).isNotNull();
        return authenticated;
    }

    private String currentCode(String secret) throws Exception {
        return codes.generate(secret, time.getTime() / 30);
    }

    private static String queryValue(String uri, String name) {
        return Arrays.stream(URI.create(uri).getRawQuery().split("&"))
                .map(value -> value.split("=", 2))
                .filter(value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8).equals(name))
                .map(value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }
}
```

- [ ] **Step 2: Run the test and observe missing endpoint/service failures**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminSecuritySettingsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because the pending enrollment, settings service, and controller do not exist.

- [ ] **Step 3: Verify the security-settings rate policy and add the serializable encrypted pending value**

Confirm the Task 3 `application.yml` still contains this pre-registered entry under `portfolio.rate-limit.policies`:

```yaml
admin-security:
  limit: 10
  window: PT15M
```

Change the `EncryptedTotpSecret` declaration without changing its defensive-copy behavior:

```java
public record EncryptedTotpSecret(int keyVersion, byte[] nonce, byte[] ciphertext)
        implements java.io.Serializable {
```

Create `PendingTotpEnrollment.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;

public record PendingTotpEnrollment(UUID enrollmentId, UUID adminId,
        EncryptedTotpSecret encryptedSecret, Instant issuedAt, Instant expiresAt, int failures)
        implements Serializable {
    public static final String SESSION_KEY = PendingTotpEnrollment.class.getName();
    public PendingTotpEnrollment failedAgain() {
        return new PendingTotpEnrollment(enrollmentId, adminId, encryptedSecret,
                issuedAt, expiresAt, failures + 1);
    }
}
```

The provisioning URI and plaintext TOTP secret are deliberately absent from the session value.

- [ ] **Step 4: Add credential-specific updates and mark-other-session persistence**

Add to `AdminUserRepository`:

```java
public void updatePassword(UUID adminId, String passwordHash) {
    int changed = jdbc.sql("""
            update admin_user set password_hash=:hash, version=version+1 where id=:id
            """).param("hash", passwordHash).param("id", adminId).update();
    if (changed != 1) throw new IllegalStateException("admin password update affected " + changed + " rows");
}

public void updateTotp(UUID adminId, EncryptedTotpSecret secret) {
    int changed = jdbc.sql("""
            update admin_user set totp_key_version=:version, totp_nonce=:nonce,
                totp_ciphertext=:ciphertext, version=admin_user.version+1 where id=:id
            """).param("version", secret.keyVersion()).param("nonce", secret.nonce())
            .param("ciphertext", secret.ciphertext()).param("id", adminId).update();
    if (changed != 1) throw new IllegalStateException("admin TOTP update affected " + changed + " rows");
}
```

Add to `AdminSessionRepository`:

```java
public List<TerminalSession> markOtherRevoked(
        UUID adminId, UUID currentMetadataId, String reason, Instant now) {
    return jdbc.sql("""
            update admin_session_metadata m set status='REVOKED', ended_at=:now,
                last_activity_at=coalesce((select to_timestamp(s.last_access_time / 1000.0)
                    from spring_session s where s.primary_id=m.session_primary_id), :now),
                revocation_reason=:reason, version=version+1
            where m.admin_id=:adminId and m.id<>:currentId and m.status='ACTIVE'
            returning m.id, m.admin_id, m.session_primary_id, m.revocation_reason
            """).param("now", now).param("reason", reason).param("adminId", adminId)
            .param("currentId", currentMetadataId)
            .query((rs, row) -> new TerminalSession(rs.getObject("id", UUID.class),
                    rs.getObject("admin_id", UUID.class), rs.getString("session_primary_id"),
                    rs.getString("revocation_reason"))).list();
}
```

Add these methods to `AdminSessionService`. The caller's transaction owns marking and audit; deletion happens only after commit and remains retryable:

```java
public List<AdminSessionRepository.TerminalSession> markOtherSessionsRevokedInCurrentTransaction(
        UUID adminId, UUID currentMetadataId, String reason) {
    List<AdminSessionRepository.TerminalSession> revoked =
            repository.markOtherRevoked(adminId, currentMetadataId, reason, clock.instant());
    for (AdminSessionRepository.TerminalSession session : revoked) {
        audit.record(new AuditCommand(adminId, "SESSION_REVOKED", "ADMIN_SESSION",
                session.metadataId().toString(), AuditOutcome.SUCCESS, null,
                Map.of("reason", reason)));
    }
    return List.copyOf(revoked);
}

public void deleteMarkedSessions(List<AdminSessionRepository.TerminalSession> sessions) {
    for (AdminSessionRepository.TerminalSession session : sessions) {
        deleteBestEffort(session.primaryId());
    }
}
```

- [ ] **Step 5: Extend the hashed subject helper for sensitive settings operations**

Replace the two public/hash helper methods in `LoginSubjectHasher` with:

```java
public String hash(HttpServletRequest request, String username) {
    return digest("admin-login\n" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
            + "\n" + addresses.resolve(request));
}

public String hashSecurity(HttpServletRequest request, UUID adminId) {
    return digest("admin-security\n" + adminId + "\n" + addresses.resolve(request));
}

private static String digest(String material) {
    try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException("SHA-256 unavailable", exception);
    }
}
```

Add `java.util.UUID` to the imports. No raw username, administrator UUID, or address enters the bounded cache.

- [ ] **Step 6: Implement atomic settings changes and ten-minute encrypted TOTP enrollment**

Create `AdminSecuritySettingsService.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.PasswordPolicy;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;

@Service
public final class AdminSecuritySettingsService {
    private static final Duration ENROLLMENT_LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_ENROLLMENT_FAILURES = 5;
    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final RecoveryCodeService recoveryCodeService;
    private final RecoveryCodeGenerator recoveryGenerator;
    private final PasswordEncoder passwords;
    private final PasswordPolicy passwordPolicy;
    private final TotpService totp;
    private final RateLimiter limiter;
    private final LoginSubjectHasher subjects;
    private final AdminSessionService sessions;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AdminSecuritySettingsService(AdminUserRepository admins,
            RecoveryCodeRepository recoveryCodes, RecoveryCodeService recoveryCodeService,
            RecoveryCodeGenerator recoveryGenerator, PasswordEncoder passwords,
            PasswordPolicy passwordPolicy, TotpService totp, RateLimiter limiter,
            LoginSubjectHasher subjects, AdminSessionService sessions, AuditService audit,
            TransactionTemplate transactions, Clock clock) {
        this.admins = admins; this.recoveryCodes = recoveryCodes;
        this.recoveryCodeService = recoveryCodeService; this.recoveryGenerator = recoveryGenerator;
        this.passwords = passwords; this.passwordPolicy = passwordPolicy; this.totp = totp;
        this.limiter = limiter; this.subjects = subjects; this.sessions = sessions;
        this.audit = audit; this.transactions = transactions; this.clock = clock;
    }

    public void changePassword(UUID adminId, String currentPassword, String currentTotp,
            String newPassword, HttpServletRequest request) {
        reauthenticate(adminId, currentPassword, currentTotp, request);
        passwordPolicy.requireStrong(newPassword);
        String hash = passwords.encode(newPassword);
        AdminSessionService.ActiveSession current = currentSession(request);
        List<AdminSessionRepository.TerminalSession> revoked = transactions.execute(status -> {
            admins.updatePassword(adminId, hash);
            List<AdminSessionRepository.TerminalSession> marked =
                    sessions.markOtherSessionsRevokedInCurrentTransaction(
                            adminId, current.metadataId(), "PASSWORD_CHANGED");
            audit.record(new AuditCommand(adminId, "ADMIN_PASSWORD_CHANGED", "ADMIN",
                    adminId.toString(), AuditOutcome.SUCCESS, null,
                    Map.of("revokedOtherSessions", Integer.toString(marked.size()))));
            return marked;
        });
        sessions.deleteMarkedSessions(requireResult(revoked));
    }

    public TotpEnrollment beginTotpEnrollment(UUID adminId, String currentPassword,
            String currentTotp, HttpServletRequest request) {
        AdminUser admin = reauthenticate(adminId, currentPassword, currentTotp, request);
        AdminSessionService.ActiveSession current = currentSession(request);
        TotpService.Enrollment generated = totp.beginEnrollment(adminId, admin.username());
        Instant issuedAt = clock.instant();
        PendingTotpEnrollment pending = new PendingTotpEnrollment(UUID.randomUUID(), adminId,
                generated.encryptedSecret(), issuedAt, issuedAt.plus(ENROLLMENT_LIFETIME), 0);
        List<AdminSessionRepository.TerminalSession> revoked = transactions.execute(status -> {
            List<AdminSessionRepository.TerminalSession> marked =
                    sessions.markOtherSessionsRevokedInCurrentTransaction(
                            adminId, current.metadataId(), "TOTP_ENROLLMENT_STARTED");
            audit.record(new AuditCommand(adminId, "ADMIN_TOTP_ENROLLMENT_STARTED", "ADMIN",
                    adminId.toString(), AuditOutcome.SUCCESS, null,
                    Map.of("revokedOtherSessions", Integer.toString(marked.size()))));
            return marked;
        });
        request.getSession(false).setAttribute(PendingTotpEnrollment.SESSION_KEY, pending);
        sessions.deleteMarkedSessions(requireResult(revoked));
        return new TotpEnrollment(pending.enrollmentId(), generated.provisioningUri(), pending.expiresAt());
    }

    public List<String> confirmTotp(UUID adminId, UUID enrollmentId, String newTotp,
            HttpServletRequest request) {
        consumeSensitive(adminId, request);
        HttpSession httpSession = requireHttpSession(request);
        Object value = httpSession.getAttribute(PendingTotpEnrollment.SESSION_KEY);
        PendingTotpEnrollment pending = value instanceof PendingTotpEnrollment candidate ? candidate : null;
        Instant now = clock.instant();
        if (pending == null || !pending.adminId().equals(adminId)
                || !pending.enrollmentId().equals(enrollmentId) || !pending.expiresAt().isAfter(now)) {
            httpSession.removeAttribute(PendingTotpEnrollment.SESSION_KEY);
            throw new DomainException("TOTP_ENROLLMENT_EXPIRED", HttpStatus.CONFLICT, Map.of());
        }
        if (!totp.verify(adminId, pending.encryptedSecret(), newTotp)) {
            PendingTotpEnrollment failed = pending.failedAgain();
            if (failed.failures() >= MAX_ENROLLMENT_FAILURES) {
                httpSession.removeAttribute(PendingTotpEnrollment.SESSION_KEY);
            } else {
                httpSession.setAttribute(PendingTotpEnrollment.SESSION_KEY, failed);
            }
            audit.record(new AuditCommand(adminId, "ADMIN_TOTP_CONFIRM_REJECTED", "ADMIN",
                    adminId.toString(), AuditOutcome.FAILURE, null, Map.of("stage", "CONFIRM")));
            throw authenticationFailed();
        }
        List<String> plaintextCodes = recoveryGenerator.generate(10);
        List<String> hashes = recoveryCodeService.hashAll(plaintextCodes);
        AdminSessionService.ActiveSession current = currentSession(request);
        httpSession.removeAttribute(PendingTotpEnrollment.SESSION_KEY);
        List<AdminSessionRepository.TerminalSession> revoked = transactions.execute(status -> {
            admins.updateTotp(adminId, pending.encryptedSecret());
            recoveryCodes.replace(adminId, hashes);
            List<AdminSessionRepository.TerminalSession> marked =
                    sessions.markOtherSessionsRevokedInCurrentTransaction(
                            adminId, current.metadataId(), "TOTP_CHANGED");
            audit.record(new AuditCommand(adminId, "ADMIN_TOTP_CHANGED", "ADMIN",
                    adminId.toString(), AuditOutcome.SUCCESS, null,
                    Map.of("recoveryCodeCount", "10",
                            "revokedOtherSessions", Integer.toString(marked.size()))));
            return marked;
        });
        sessions.deleteMarkedSessions(requireResult(revoked));
        return List.copyOf(plaintextCodes);
    }

    public List<String> regenerateRecoveryCodes(UUID adminId, String currentPassword,
            String currentTotp, HttpServletRequest request) {
        reauthenticate(adminId, currentPassword, currentTotp, request);
        List<String> plaintextCodes = recoveryGenerator.generate(10);
        List<String> hashes = recoveryCodeService.hashAll(plaintextCodes);
        AdminSessionService.ActiveSession current = currentSession(request);
        List<AdminSessionRepository.TerminalSession> revoked = transactions.execute(status -> {
            recoveryCodes.replace(adminId, hashes);
            List<AdminSessionRepository.TerminalSession> marked =
                    sessions.markOtherSessionsRevokedInCurrentTransaction(
                            adminId, current.metadataId(), "RECOVERY_CODES_REGENERATED");
            audit.record(new AuditCommand(adminId, "ADMIN_RECOVERY_CODES_REGENERATED", "ADMIN",
                    adminId.toString(), AuditOutcome.SUCCESS, null,
                    Map.of("recoveryCodeCount", "10",
                            "revokedOtherSessions", Integer.toString(marked.size()))));
            return marked;
        });
        sessions.deleteMarkedSessions(requireResult(revoked));
        return List.copyOf(plaintextCodes);
    }

    private AdminUser reauthenticate(UUID adminId, String password, String code,
            HttpServletRequest request) {
        consumeSensitive(adminId, request);
        AdminUser admin = admins.findById(adminId).orElseThrow(AdminSecuritySettingsService::authenticationFailed);
        boolean accepted = admin.status() == AdminStatus.ACTIVE
                && passwords.matches(password, admin.passwordHash())
                && totp.verify(admin.id(), admin.totpSecret(), code);
        if (!accepted) {
            audit.record(new AuditCommand(adminId, "ADMIN_SECURITY_REAUTH_REJECTED", "ADMIN",
                    adminId.toString(), AuditOutcome.FAILURE, null, Map.of("stage", "REAUTH")));
            throw authenticationFailed();
        }
        return admin;
    }

    private void consumeSensitive(UUID adminId, HttpServletRequest request) {
        RateLimitDecision decision = limiter.consume("admin-security", subjects.hashSecurity(request, adminId));
        if (!decision.allowed()) {
            throw new DomainException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                    Map.of("retryAfterSeconds", Long.toString(decision.retryAfterSeconds())));
        }
    }

    private AdminSessionService.ActiveSession currentSession(HttpServletRequest request) {
        return sessions.requireActive(requireHttpSession(request).getId());
    }

    private static HttpSession requireHttpSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw authenticationFailed();
        return session;
    }

    private static <T> List<T> requireResult(List<T> value) {
        if (value == null) throw new IllegalStateException("security transaction returned no result");
        return value;
    }

    private static DomainException authenticationFailed() {
        return new DomainException("AUTHENTICATION_FAILED", HttpStatus.UNAUTHORIZED, Map.of());
    }

    public record TotpEnrollment(UUID enrollmentId, String provisioningUri, Instant expiresAt) {}
}
```

- [ ] **Step 7: Expose the four authenticated no-store endpoints**

Create `AdminSecuritySettingsController.java`:

```java
package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/security")
public final class AdminSecuritySettingsController {
    private final SecurityCurrentAdminProvider current;
    private final AdminSecuritySettingsService settings;
    public AdminSecuritySettingsController(SecurityCurrentAdminProvider current,
            AdminSecuritySettingsService settings) {
        this.current = current; this.settings = settings;
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@Valid @RequestBody PasswordChangeRequest body,
            HttpServletRequest request) {
        settings.changePassword(current.requireAdminId(), body.currentPassword(),
                body.currentTotp(), body.newPassword(), request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/totp/enrollment")
    public ResponseEntity<TotpEnrollmentResponse> enrollment(
            @Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request) {
        AdminSecuritySettingsService.TotpEnrollment enrollment = settings.beginTotpEnrollment(
                current.requireAdminId(), body.currentPassword(), body.currentTotp(), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TotpEnrollmentResponse(
                enrollment.enrollmentId(), enrollment.provisioningUri(), enrollment.expiresAt()));
    }

    @PostMapping("/totp/confirm")
    public ResponseEntity<RecoveryCodesResponse> confirm(
            @Valid @RequestBody TotpConfirmRequest body, HttpServletRequest request) {
        List<String> codes = settings.confirmTotp(current.requireAdminId(),
                body.enrollmentId(), body.newTotp(), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RecoveryCodesResponse(codes));
    }

    @PostMapping("/recovery-codes/regenerate")
    public ResponseEntity<RecoveryCodesResponse> regenerate(
            @Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request) {
        List<String> codes = settings.regenerateRecoveryCodes(current.requireAdminId(),
                body.currentPassword(), body.currentTotp(), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RecoveryCodesResponse(codes));
    }

    public record PasswordChangeRequest(@NotBlank String currentPassword,
            @NotBlank String currentTotp, @NotBlank String newPassword) {}
    public record ReauthenticationRequest(@NotBlank String currentPassword,
            @NotBlank String currentTotp) {}
    public record TotpConfirmRequest(@NotNull UUID enrollmentId, @NotBlank String newTotp) {}
    public record TotpEnrollmentResponse(UUID enrollmentId, String provisioningUri, Instant expiresAt) {}
    public record RecoveryCodesResponse(List<String> recoveryCodes) {
        public RecoveryCodesResponse { recoveryCodes = List.copyOf(recoveryCodes); }
    }
}
```

The existing security chain already authenticates `/api/admin/security/**`; no permit rule is added. All four POSTs therefore require both the authenticated session and CSRF token.

- [ ] **Step 8: Run security-settings tests and inspect persisted session state**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminSecuritySettingsTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. Wrong reauthentication performs no credential/code/session mutation and the eleventh attempt returns 429; password/TOTP/recovery changes are atomic with marking every other metadata row REVOKED; the current session stays ACTIVE; post-commit deletion clears the other Spring Session rows; enrollment responses and recovery-code responses are `no-store` and reveal their one-time values only in that response.

- [ ] **Step 9: Commit administrator security settings**

```powershell
git add backend-parent/portfolio-server/src/main/resources/application.yml `
        backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/web/AdminSecuritySettingsTest.java
git diff --cached --check
git commit -m "feat: add administrator credential security settings"
```

---

### Task 13: Add the authenticated, redacted audit-log query API

**Files:**

- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/audit/AdminAuditItem.java`
- Create: `backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/audit/AdminAuditPage.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditCursor.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AuditMetadataRedactor.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AdminAuditQueryRepository.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AdminAuditQueryService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit/AdminAuditController.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/audit/AdminAuditQueryTest.java`

**Interfaces:**

- Consumes: Immutable V2 `audit_log`, authenticated `CurrentAdminProvider`, and the shared `DomainException`/ProblemDetail contract.
- Produces: `GET /api/admin/audit?cursor=&action=&outcome=&from=&to=&limit=` with an opaque `(createdAt,id)` cursor, descending stable order, optional filters, `limit` 1–100, and `Cache-Control: no-store`.
- Publishes for Plan 04: `xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditItem` and `AdminAuditPage`. The admin UI consumes only these allowlisted DTOs; it never receives raw `audit_log.metadata`.

- [ ] **Step 1: Write the authenticated pagination, redaction, and validation test**

Create `AdminAuditQueryTest.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminAuditQueryTest extends PostgresIntegrationTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder passwords;
    @Autowired TotpService totp;
    @Autowired CodeGenerator codes;
    @Autowired TimeProvider time;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private UUID adminId;
    private String totpSecret;

    @BeforeEach
    void createAdminAndAuditRows() {
        adminId = UUID.randomUUID();
        TotpService.Enrollment enrollment = totp.beginEnrollment(adminId, "portfolio-admin");
        totpSecret = enrollment.plaintextSecret();
        Instant now = Instant.now();
        admins.insert(new AdminUser(adminId, "portfolio-admin",
                passwords.encode("Correct-Horse-7!Battery"), AdminStatus.ACTIVE,
                enrollment.encryptedSecret(), null, 0, now, now));
        insertAudit(UUID.fromString("00000000-0000-0000-0000-000000000101"),
                "2026-07-14T10:01:00Z", "SUCCESS");
        insertAudit(UUID.fromString("00000000-0000-0000-0000-000000000102"),
                "2026-07-14T10:02:00Z", "FAILURE");
        insertAudit(UUID.fromString("00000000-0000-0000-0000-000000000103"),
                "2026-07-14T10:03:00Z", "SUCCESS");
    }

    @Test
    void usesStableOpaqueCursorAndAllowlistedMetadata() throws Exception {
        Cookie authenticated = login();
        MvcResult first = mvc.perform(get("/api/admin/audit").cookie(authenticated)
                        .param("action", "TEST_EVENT").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000103"))
                .andExpect(jsonPath("$.items[1].id").value("00000000-0000-0000-0000-000000000102"))
                .andExpect(jsonPath("$.items[0].metadata.method").value("TOTP"))
                .andExpect(jsonPath("$.items[0].metadata.password").doesNotExist())
                .andExpect(jsonPath("$.items[0].metadata.rawIp").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        JsonNode page = json.readTree(first.getResponse().getContentAsByteArray());

        mvc.perform(get("/api/admin/audit").cookie(authenticated)
                        .param("action", "TEST_EVENT").param("limit", "2")
                        .param("cursor", page.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000101"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mvc.perform(get("/api/admin/audit").cookie(authenticated)
                        .param("action", "TEST_EVENT").param("outcome", "SUCCESS")
                        .param("from", "2026-07-14T10:02:00Z")
                        .param("to", "2026-07-14T10:04:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000103"));
    }

    @Test
    void requiresAuthenticationAndRejectsInvalidCursorOrLimit() throws Exception {
        mvc.perform(get("/api/admin/audit")).andExpect(status().isUnauthorized());
        Cookie authenticated = login();
        mvc.perform(get("/api/admin/audit").cookie(authenticated).param("limit", "101"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AUDIT_QUERY_INVALID"));
        mvc.perform(get("/api/admin/audit").cookie(authenticated).param("cursor", "not-a-cursor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AUDIT_CURSOR_INVALID"));
    }

    private void insertAudit(UUID id, String timestamp, String outcome) {
        jdbc.sql("""
                insert into audit_log(id,actor_admin_id,action,target_type,target_id,outcome,
                    trace_id,metadata,created_at)
                values (:id,:adminId,'TEST_EVENT','ADMIN',:targetId,:outcome,
                    :traceId,cast(:metadata as jsonb),:createdAt)
                """).param("id", id).param("adminId", adminId).param("targetId", adminId.toString())
                .param("outcome", outcome).param("traceId", "trace-" + id)
                .param("metadata", "{\"method\":\"TOTP\",\"password\":\"never-return\",\"rawIp\":\"203.0.113.8\"}")
                .param("createdAt", Instant.parse(timestamp)).update();
    }

    private Cookie login() throws Exception {
        MvcResult password = mvc.perform(post("/api/admin/auth/password").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"portfolio-admin\",\"password\":\"Correct-Horse-7!Battery\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie pending = password.getResponse().getCookie("PORTFOLIO_SESSION");
        assertThat(pending).isNotNull();
        String code = codes.generate(totpSecret, time.getTime() / 30);
        MvcResult authenticated = mvc.perform(post("/api/admin/auth/second-factor")
                        .cookie(pending).with(csrf()).contentType("application/json")
                        .content("{\"method\":\"TOTP\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = authenticated.getResponse().getCookie("PORTFOLIO_SESSION");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
```

- [ ] **Step 2: Run the audit query test and observe missing DTO/query failures**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminAuditQueryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation or with 404 because the audit response DTOs and query endpoint do not exist.

- [ ] **Step 3: Create the Plan 04-facing immutable response DTOs**

Create `AdminAuditItem.java`:

```java
package xyz.yychainsaw.portfolio.api.admin.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AdminAuditItem(UUID id, UUID actorAdminId, String action,
        String targetType, String targetId, String outcome, String traceId,
        Map<String, String> metadata, Instant timestamp) {
    public AdminAuditItem { metadata = Map.copyOf(metadata); }
}
```

Create `AdminAuditPage.java`:

```java
package xyz.yychainsaw.portfolio.api.admin.audit;

import java.util.List;

public record AdminAuditPage(List<AdminAuditItem> items, String nextCursor) {
    public AdminAuditPage { items = List.copyOf(items); }
}
```

- [ ] **Step 4: Implement the opaque stable cursor and metadata allowlist**

Create `AuditCursor.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;

record AuditCursor(Instant createdAt, UUID id) {
    String encode() {
        String value = createdAt + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static AuditCursor decode(String value) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\n", -1);
            if (parts.length != 2) throw new IllegalArgumentException("cursor shape");
            return new AuditCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new DomainException("AUDIT_CURSOR_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("cursor", "invalid"));
        }
    }
}
```

Create `AuditMetadataRedactor.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class AuditMetadataRedactor {
    private static final Set<String> ALLOWED = Set.of(
            "stage", "next", "method", "reason", "channel", "backupSha256",
            "recoveryCodeCount", "revokedOtherSessions");
    private final ObjectMapper json;
    AuditMetadataRedactor(ObjectMapper json) { this.json = json; }

    Map<String, String> redact(String rawJson) {
        try {
            JsonNode source = json.readTree(rawJson);
            Map<String, String> safe = new LinkedHashMap<>();
            for (String key : ALLOWED) {
                JsonNode value = source.get(key);
                if (value != null && value.isTextual()) {
                    String text = value.textValue();
                    safe.put(key, text.substring(0, Math.min(text.length(), 128)));
                }
            }
            return Map.copyOf(safe);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored audit metadata is invalid JSON");
        }
    }
}
```

The allowlist intentionally excludes usernames, email, raw or hashed IP, complete User-Agent, request bodies, passwords, TOTP values/seeds, recovery codes, keys, session IDs, database details, and file paths.

- [ ] **Step 5: Implement a parameterized keyset query with a stable tie-breaker**

Create `AdminAuditQueryRepository.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditItem;

@Repository
final class AdminAuditQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditMetadataRedactor metadata;
    AdminAuditQueryRepository(NamedParameterJdbcTemplate jdbc, AuditMetadataRedactor metadata) {
        this.jdbc = jdbc; this.metadata = metadata;
    }

    List<AdminAuditItem> find(Query query, int fetchLimit) {
        StringBuilder sql = new StringBuilder("""
                select id,actor_admin_id,action,target_type,target_id,outcome,trace_id,
                       metadata::text metadata,created_at
                from audit_log where 1=1
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (query.action() != null) {
            sql.append(" and action=:action"); parameters.addValue("action", query.action());
        }
        if (query.outcome() != null) {
            sql.append(" and outcome=:outcome"); parameters.addValue("outcome", query.outcome());
        }
        if (query.from() != null) {
            sql.append(" and created_at>=:from"); parameters.addValue("from", query.from());
        }
        if (query.to() != null) {
            sql.append(" and created_at<:to"); parameters.addValue("to", query.to());
        }
        if (query.cursor() != null) {
            sql.append(" and (created_at,id)<(:cursorAt,:cursorId)");
            parameters.addValue("cursorAt", query.cursor().createdAt());
            parameters.addValue("cursorId", query.cursor().id());
        }
        sql.append(" order by created_at desc,id desc limit :fetchLimit");
        parameters.addValue("fetchLimit", fetchLimit);
        return jdbc.query(sql.toString(), parameters, this::map);
    }

    private AdminAuditItem map(ResultSet rs, int row) throws SQLException {
        return new AdminAuditItem(rs.getObject("id", UUID.class),
                rs.getObject("actor_admin_id", UUID.class), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("outcome"),
                rs.getString("trace_id"), metadata.redact(rs.getString("metadata")),
                rs.getTimestamp("created_at").toInstant());
    }

    record Query(String action, String outcome, Instant from, Instant to, AuditCursor cursor) {}
}
```

- [ ] **Step 6: Validate filters and build pages without offset pagination**

Create `AdminAuditQueryService.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditItem;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditPage;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminAuditQueryService {
    private final AdminAuditQueryRepository repository;
    public AdminAuditQueryService(AdminAuditQueryRepository repository) { this.repository = repository; }

    public AdminAuditPage find(String cursorValue, String actionValue, String outcomeValue,
            String fromValue, String toValue, String requestedLimit) {
        int limit;
        try {
            limit = blankToNull(requestedLimit) == null ? 50 : Integer.parseInt(requestedLimit);
        } catch (NumberFormatException exception) {
            throw invalid("limit", "must be an integer between 1 and 100");
        }
        if (limit < 1 || limit > 100) throw invalid("limit", "must be between 1 and 100");
        String action = blankToNull(actionValue);
        if (action != null && !action.matches("[A-Z0-9_]{1,96}")) {
            throw invalid("action", "invalid action");
        }
        String outcome = blankToNull(outcomeValue);
        if (outcome != null) {
            outcome = outcome.toUpperCase(Locale.ROOT);
            if (!outcome.equals("SUCCESS") && !outcome.equals("FAILURE")) {
                throw invalid("outcome", "must be SUCCESS or FAILURE");
            }
        }
        Instant from = parseInstant("from", fromValue);
        Instant to = parseInstant("to", toValue);
        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("to", "must be after from");
        }
        AuditCursor cursor = blankToNull(cursorValue) == null ? null : AuditCursor.decode(cursorValue);
        List<AdminAuditItem> rows = repository.find(
                new AdminAuditQueryRepository.Query(action, outcome, from, to, cursor), limit + 1);
        boolean more = rows.size() > limit;
        List<AdminAuditItem> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        AdminAuditItem last = more ? items.get(items.size() - 1) : null;
        String next = last == null ? null : new AuditCursor(last.timestamp(), last.id()).encode();
        return new AdminAuditPage(items, next);
    }

    private static Instant parseInstant(String field, String value) {
        if (blankToNull(value) == null) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException exception) { throw invalid(field, "must be an ISO-8601 instant"); }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DomainException invalid(String field, String message) {
        return new DomainException("AUDIT_QUERY_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(field, message));
    }
}
```

- [ ] **Step 7: Expose only the redacted no-store page**

Create `AdminAuditController.java`:

```java
package xyz.yychainsaw.portfolio.audit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.api.admin.audit.AdminAuditPage;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;

@RestController
@RequestMapping("/api/admin/audit")
public final class AdminAuditController {
    private final CurrentAdminProvider current;
    private final AdminAuditQueryService queries;
    public AdminAuditController(CurrentAdminProvider current, AdminAuditQueryService queries) {
        this.current = current; this.queries = queries;
    }

    @GetMapping
    public ResponseEntity<AdminAuditPage> find(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit) {
        current.requireAdminId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queries.find(cursor, action, outcome, from, to, limit));
    }
}
```

The existing `/api/admin/**` security rule authenticates this endpoint. It is read-only, so CSRF is not required for GET; no mutation endpoint exists for `audit_log`.

- [ ] **Step 8: Run the audit query tests and verify table immutability still holds**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminAuditQueryTest,JdbcAuditServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. Cursor pages are stable and non-overlapping, filters use keyset predicates, metadata is allowlisted, invalid inputs return non-leaking 422 ProblemDetails, unauthenticated requests return 401, responses are no-store, and runtime audit UPDATE remains denied.

- [ ] **Step 9: Commit the audit read model**

```powershell
git add backend-parent/portfolio-pojo/src/main/java/xyz/yychainsaw/portfolio/api/admin/audit `
        backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/audit `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/audit/AdminAuditQueryTest.java
git diff --cached --check
git commit -m "feat: add redacted administrator audit query"
```

---

### Task 14: Add backup-gated local recovery and TOTP key re-encryption CLIs

**Files:**
- Modify: `backend-parent/portfolio-server/src/main/resources/application.yml`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/persistence/AdminUserRepository.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionRepository.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/session/AdminSessionService.java`
- Modify: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/AdminCliRunner.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/RecoveryProperties.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/DatabaseRestorePointService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/PgDumpRestorePointService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/AdminRecoveryService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/TotpKeyReencryptionService.java`
- Create: `backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth/cli/TotpKeyReencryptionResult.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/cli/AdminRecoveryServiceTest.java`
- Test: `backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/cli/TotpKeyReencryptionServiceTest.java`

**Interfaces:**
- Consumes: Local interactive console, `pg_dump`, admin credential/TOTP/recovery services, the versioned TOTP key ring, session revocation, and audit.
- Produces: Local-only `--portfolio.cli.command=admin-recover` and `--portfolio.cli.command=totp-reencrypt`; a verified custom-format database dump before either mutation; new password/TOTP/recovery codes for recovery; in-place re-encryption of the unchanged TOTP seed under the active key version; immediate all-session revocation after recovery; and redacted `ADMIN_RECOVERED`/`TOTP_KEY_REENCRYPTED` audit events.

- [ ] **Step 1: Write recovery acceptance and backup-failure tests**

Create `AdminRecoveryServiceTest.java` with a primary in-memory restore-point bean:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminStatus;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@Import(AdminRecoveryServiceTest.RestorePointConfiguration.class)
@Transactional
class AdminRecoveryServiceTest extends PostgresIntegrationTestBase {
    @Autowired AdminRecoveryService recovery;
    @Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder passwords;
    @Autowired TotpService totp;
    @Autowired CodeGenerator codes;
    @Autowired TimeProvider time;
    @Autowired JdbcClient jdbc;
    @Autowired FakeRestorePointService restorePoint;
    private UUID adminId;

    @BeforeEach
    void createAdminAndSession() {
        restorePoint.reset();
        adminId = UUID.randomUUID();
        var oldTotp = totp.beginEnrollment(adminId, "portfolio-admin");
        Instant now = Instant.now();
        admins.insert(new AdminUser(adminId, "portfolio-admin", passwords.encode("Old-Password-7!Secure"),
                AdminStatus.ACTIVE, oldTotp.encryptedSecret(), null, 0, now, now));
        jdbc.sql("""
                insert into spring_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time,principal_name)
                values ('00000000-0000-0000-0000-000000000099','active-session',:now,:now,1800,:expiry,'portfolio-admin')
                """).param("now", now.toEpochMilli()).param("expiry", now.plusSeconds(1800).toEpochMilli()).update();
        jdbc.sql("""
                insert into admin_session_metadata(id,admin_id,session_primary_id,status,client_summary)
                values (:id,:adminId,'00000000-0000-0000-0000-000000000099','ACTIVE','Other/Other @ local')
                """).param("id", UUID.randomUUID()).param("adminId", adminId).update();
        jdbc.sql("""
                insert into spring_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time,principal_name)
                values ('00000000-0000-0000-0000-000000000098','pending-second-factor',
                    :now,:now,1800,:expiry,null)
                """).param("now", now.toEpochMilli())
                .param("expiry", now.plusSeconds(1800).toEpochMilli()).update();
    }

    @Test
    void createsRestorePointThenReplacesCredentialsAndRevokesAllSessions() throws Exception {
        char[] newPassword = "New-Password-8!Secure".toCharArray();
        AdminRecoveryService.Enrollment enrollment = recovery.prepare(newPassword);
        String code = codes.generate(enrollment.plaintextTotpSecret(), time.getTime() / 30);
        recovery.complete(enrollment, code);

        assertThat(restorePoint.called()).isTrue();
        AdminUser changed = admins.findById(adminId).orElseThrow();
        assertThat(passwords.matches("New-Password-8!Secure", changed.passwordHash())).isTrue();
        assertThat(jdbc.sql("select count(*) from admin_session_metadata where status='REVOKED'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from spring_session").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from audit_log where action='ADMIN_RECOVERED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void restorePointFailureLeavesCredentialsAndSessionsUntouched() {
        restorePoint.failNext();
        assertThatThrownBy(() -> recovery.prepare("New-Password-8!Secure".toCharArray()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(passwords.matches("Old-Password-7!Secure",
                admins.findById(adminId).orElseThrow().passwordHash())).isTrue();
        assertThat(jdbc.sql("select count(*) from admin_session_metadata where status='ACTIVE'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from spring_session").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void invalidNewTotpAfterBackupLeavesCredentialsAndSessionsUntouched() {
        AdminRecoveryService.Enrollment enrollment =
                recovery.prepare("New-Password-8!Secure".toCharArray());
        assertThatThrownBy(() -> recovery.complete(enrollment, "invalid"))
                .isInstanceOf(xyz.yychainsaw.portfolio.common.error.DomainException.class);
        assertThat(passwords.matches("Old-Password-7!Secure",
                admins.findById(adminId).orElseThrow().passwordHash())).isTrue();
        assertThat(jdbc.sql("select count(*) from admin_session_metadata where status='ACTIVE'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from spring_session").query(Long.class).single())
                .isEqualTo(2);
    }

    @TestConfiguration
    static class RestorePointConfiguration {
        @Bean @Primary FakeRestorePointService fakeRestorePointService() {
            return new FakeRestorePointService();
        }
    }

    static final class FakeRestorePointService implements DatabaseRestorePointService {
        private final AtomicBoolean called = new AtomicBoolean();
        private boolean fail;
        @Override public RestorePoint create() {
            called.set(true);
            if (fail) throw new IllegalStateException("simulated pg_dump failure");
            return new RestorePoint(Path.of("runtime/recovery/test.dump"), "a".repeat(64));
        }
        void failNext() { fail = true; }
        void reset() { called.set(false); fail = false; }
        boolean called() { return called.get(); }
    }
}
```

- [ ] **Step 2: Run the tests and observe missing recovery types**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminRecoveryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL at test compilation because restore-point and recovery services do not exist.

- [ ] **Step 3: Configure the recovery dump without putting its password in arguments**

Replace the existing `portfolio.recovery` block in `application.yml` with:

```yaml
recovery:
  directory: ${PORTFOLIO_RECOVERY_DIRECTORY:runtime/recovery}
  pg-dump-bin: ${PORTFOLIO_PG_DUMP_BIN:pg_dump}
  host: ${PORTFOLIO_RECOVERY_DB_HOST:localhost}
  port: ${PORTFOLIO_RECOVERY_DB_PORT:5432}
  database: ${POSTGRES_DB:portfolio}
  username: ${PORTFOLIO_DB_MIGRATOR_USER}
  password: ${PORTFOLIO_DB_MIGRATOR_PASSWORD}
```

Create `RecoveryProperties.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.recovery")
public record RecoveryProperties(Path directory, String pgDumpBin, String host, int port,
        String database, String username, String password) {
    public RecoveryProperties {
        if (directory == null || pgDumpBin.isBlank() || host.isBlank() || port < 1
                || database.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("complete pg_dump recovery configuration is required");
        }
    }
}
```

- [ ] **Step 4: Implement a verified custom-format `pg_dump` restore point**

Create `DatabaseRestorePointService.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.nio.file.Path;

public interface DatabaseRestorePointService {
    RestorePoint create();
    record RestorePoint(Path path, String sha256) {}
}
```

Create `PgDumpRestorePointService.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class PgDumpRestorePointService implements DatabaseRestorePointService {
    private static final DateTimeFormatter NAME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final RecoveryProperties properties;
    private final Clock clock;

    public PgDumpRestorePointService(RecoveryProperties properties, Clock clock) {
        this.properties = properties; this.clock = clock;
    }

    @Override
    public RestorePoint create() {
        Path output = properties.directory().resolve("admin-recover-" + NAME.format(clock.instant()) + ".dump")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(output.getParent());
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    properties.pgDumpBin(), "--host=" + properties.host(),
                    "--port=" + properties.port(), "--username=" + properties.username(),
                    "--dbname=" + properties.database(), "--format=custom", "--no-owner", "--no-acl",
                    "--file=" + output));
            builder.environment().put("PGPASSWORD", properties.password());
            builder.redirectError(ProcessBuilder.Redirect.PIPE);
            Process process = builder.start();
            String error;
            try (InputStream stream = process.getErrorStream()) {
                error = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            int exit = process.waitFor();
            if (exit != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                Files.deleteIfExists(output);
                throw new IllegalStateException("pg_dump restore point failed with exit " + exit
                        + (error.isBlank() ? "" : ": " + error.strip()));
            }
            return new RestorePoint(output, sha256(output));
        } catch (IOException exception) {
            throw new IllegalStateException("pg_dump restore point could not be created", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("pg_dump restore point was interrupted", exception);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
```

- [ ] **Step 5: Add repository operations for the only admin and all-session revocation**

Add to `AdminUserRepository`:

```java
public AdminUser requireOnlyAdmin() {
    java.util.List<AdminUser> rows = jdbc.sql("""
            select id, username, password_hash, status, totp_key_version, totp_nonce,
                   totp_ciphertext, last_login_at, version, created_at, updated_at
            from admin_user order by created_at
            """).query(MAPPER).list();
    if (rows.size() != 1) throw new IllegalStateException("admin recovery requires exactly one admin");
    return rows.get(0);
}
```

Add to `AdminSessionRepository`:

```java
public List<TerminalSession> markAllRevoked(UUID adminId, String reason, Instant now) {
    return jdbc.sql("""
            update admin_session_metadata m set status='REVOKED', ended_at=:now,
                last_activity_at=coalesce((select to_timestamp(s.last_access_time / 1000.0)
                    from spring_session s where s.primary_id=m.session_primary_id), :now),
                revocation_reason=:reason, version=version+1
            where m.admin_id=:adminId and m.status='ACTIVE'
            returning m.id, m.admin_id, m.session_primary_id, m.revocation_reason
            """).param("now", now).param("reason", reason).param("adminId", adminId)
            .query((rs, row) -> new TerminalSession(rs.getObject("id", UUID.class),
                    rs.getObject("admin_id", UUID.class), rs.getString("session_primary_id"),
                    rs.getString("revocation_reason"))).list();
}

public void deleteAllSpringSessions() {
    jdbc.sql("delete from spring_session").update();
}
```

Add to `AdminSessionService`:

```java
public List<AdminSessionRepository.TerminalSession> markAllSessionsRevokedInCurrentTransaction(
        UUID adminId, String reason) {
    List<AdminSessionRepository.TerminalSession> revoked =
            repository.markAllRevoked(adminId, reason, clock.instant());
    for (AdminSessionRepository.TerminalSession session : revoked) {
        audit.record(new AuditCommand(adminId, "SESSION_REVOKED", "ADMIN_SESSION",
                session.metadataId().toString(), AuditOutcome.SUCCESS, null, Map.of("reason", reason)));
    }
    return List.copyOf(revoked);
}

public void deleteAllSpringSessionsBestEffort() {
    try {
        repository.deleteAllSpringSessions();
    } catch (DataAccessException exception) {
        log.warn("All-session row deletion deferred for retry type={}", exception.getClass().getName());
    }
}
```

Recovery calls `markAllSessionsRevokedInCurrentTransaction` inside the same transaction as credential replacement. Only after commit does it call `deleteAllSpringSessionsBestEffort`; terminal linked rows and expired password-stage rows remain covered by the cleanup behavior implemented in Task 9.

- [ ] **Step 6: Implement recovery prepare/verify/reset**

Create `AdminRecoveryService.java`:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;
import xyz.yychainsaw.portfolio.auth.crypto.PasswordPolicy;
import xyz.yychainsaw.portfolio.auth.crypto.RecoveryCodeGenerator;
import xyz.yychainsaw.portfolio.auth.crypto.TotpService;
import xyz.yychainsaw.portfolio.auth.model.AdminUser;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
public final class AdminRecoveryService {
    private final DatabaseRestorePointService restorePoints;
    private final AdminUserRepository admins;
    private final RecoveryCodeRepository recoveryCodes;
    private final RecoveryCodeService recoveryCodeService;
    private final RecoveryCodeGenerator recoveryGenerator;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwords;
    private final TotpService totp;
    private final AdminSessionService sessions;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public AdminRecoveryService(DatabaseRestorePointService restorePoints, AdminUserRepository admins,
            RecoveryCodeRepository recoveryCodes, RecoveryCodeService recoveryCodeService,
            RecoveryCodeGenerator recoveryGenerator, PasswordPolicy passwordPolicy,
            PasswordEncoder passwords, TotpService totp, AdminSessionService sessions,
            AuditService audit, TransactionTemplate transactions) {
        this.restorePoints = restorePoints; this.admins = admins; this.recoveryCodes = recoveryCodes;
        this.recoveryCodeService = recoveryCodeService; this.recoveryGenerator = recoveryGenerator;
        this.passwordPolicy = passwordPolicy; this.passwords = passwords; this.totp = totp;
        this.sessions = sessions; this.audit = audit; this.transactions = transactions;
    }

    public Enrollment prepare(char[] password) {
        try {
            passwordPolicy.requireStrong(new String(password));
            DatabaseRestorePointService.RestorePoint restorePoint = restorePoints.create();
            AdminUser admin = admins.requireOnlyAdmin();
            String passwordHash = passwords.encode(new String(password));
            TotpService.Enrollment enrollment = totp.beginEnrollment(admin.id(), admin.username());
            List<String> plaintextRecovery = recoveryGenerator.generate(10);
            return new Enrollment(admin, passwordHash, enrollment.plaintextSecret(),
                    enrollment.encryptedSecret(), enrollment.provisioningUri(), plaintextRecovery,
                    recoveryCodeService.hashAll(plaintextRecovery), restorePoint.sha256());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public void complete(Enrollment enrollment, String totpCode) {
        if (!totp.verifyEnrollment(enrollment.plaintextTotpSecret(), totpCode)) {
            throw new DomainException("INVALID_RECOVERY_TOTP", HttpStatus.UNAUTHORIZED, Map.of());
        }
        List<AdminSessionRepository.TerminalSession> revoked = transactions.execute(status -> {
            admins.replaceCredentials(enrollment.admin().id(), enrollment.passwordHash(),
                    enrollment.encryptedTotpSecret());
            recoveryCodes.replace(enrollment.admin().id(), enrollment.recoveryCodeHashes());
            List<AdminSessionRepository.TerminalSession> marked =
                    sessions.markAllSessionsRevokedInCurrentTransaction(
                            enrollment.admin().id(), "ADMIN_RECOVERY");
            audit.record(new AuditCommand(enrollment.admin().id(), "ADMIN_RECOVERED", "ADMIN",
                    enrollment.admin().id().toString(), AuditOutcome.SUCCESS, null,
                    Map.of("channel", "LOCAL_CLI", "backupSha256", enrollment.backupSha256())));
            return marked;
        });
        if (revoked == null) throw new IllegalStateException("admin recovery transaction returned no result");
        sessions.deleteAllSpringSessionsBestEffort();
    }

    public record Enrollment(AdminUser admin, String passwordHash, String plaintextTotpSecret,
            EncryptedTotpSecret encryptedTotpSecret, String provisioningUri,
            List<String> plaintextRecoveryCodes, List<String> recoveryCodeHashes,
            String backupSha256) {
        public Enrollment {
            plaintextRecoveryCodes = List.copyOf(plaintextRecoveryCodes);
            recoveryCodeHashes = List.copyOf(recoveryCodeHashes);
        }
    }
}
```

- [ ] **Step 7: Extend the CLI runner with explicit confirmation and `admin-recover`**

Replace `AdminCliRunner` with this complete dispatcher:

```java
package xyz.yychainsaw.portfolio.auth.cli;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AdminCliRunner implements ApplicationRunner {
    private final String command;
    private final SecretConsole console;
    private final AdminBootstrapService bootstrap;
    private final AdminRecoveryService recovery;

    public AdminCliRunner(@Value("${portfolio.cli.command:}") String command, SecretConsole console,
                          AdminBootstrapService bootstrap, AdminRecoveryService recovery) {
        this.command = command; this.console = console; this.bootstrap = bootstrap; this.recovery = recovery;
    }

    @Override public void run(ApplicationArguments arguments) {
        switch (command) {
            case "" -> { return; }
            case "admin-bootstrap" -> bootstrap();
            case "admin-recover" -> recover();
            default -> throw new IllegalArgumentException("unknown portfolio CLI command: " + command);
        }
    }

    private void bootstrap() {
        String username = console.readLine("Administrator username: ").trim();
        char[] password = confirmedPassword();
        try {
            AdminBootstrapService.Enrollment enrollment = bootstrap.prepare(username, password);
            console.println("Add this provisioning URI to the authenticator:");
            console.println(enrollment.provisioningUri());
            char[] totp = console.readSecret("Current six-digit TOTP: ");
            try {
                bootstrap.complete(enrollment, new String(totp));
            } finally {
                Arrays.fill(totp, '\0');
            }
            console.println("Store these one-time recovery codes offline; they will not be shown again:");
            enrollment.plaintextRecoveryCodes().forEach(console::println);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void recover() {
        String confirmation = console.readLine("Type RECOVER ADMIN to create a dump and reset access: ");
        if (!"RECOVER ADMIN".equals(confirmation)) throw new IllegalArgumentException("recovery not confirmed");
        char[] password = confirmedPassword();
        try {
            AdminRecoveryService.Enrollment enrollment = recovery.prepare(password);
            console.println("Database restore point SHA-256: " + enrollment.backupSha256());
            console.println("Add this new provisioning URI to the authenticator:");
            console.println(enrollment.provisioningUri());
            char[] totp = console.readSecret("Current six-digit TOTP: ");
            try {
                recovery.complete(enrollment, new String(totp));
            } finally {
                Arrays.fill(totp, '\0');
            }
            console.println("All prior sessions are revoked. Store these new recovery codes offline:");
            enrollment.plaintextRecoveryCodes().forEach(console::println);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private char[] confirmedPassword() {
        char[] first = console.readSecret("New password: ");
        char[] second = console.readSecret("Repeat password: ");
        if (!Arrays.equals(first, second)) {
            Arrays.fill(first, '\0'); Arrays.fill(second, '\0');
            throw new IllegalArgumentException("passwords differ");
        }
        Arrays.fill(second, '\0');
        return first;
    }
}
```

- [ ] **Step 8: Run recovery tests**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=AdminRecoveryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS; backup failure and invalid new-TOTP proof are zero credential/session mutation; successful recovery changes password/TOTP/codes, atomically marks every active metadata row revoked before deleting all Spring Session rows, and puts only the local channel plus backup checksum in recovery audit metadata.

- [ ] **Step 9: Smoke-test `admin-recover` from a real terminal**

```powershell
java -jar backend-parent\portfolio-server\target\portfolio-server-1.0.0-SNAPSHOT.jar `
  --portfolio.cli.command=admin-recover
```

Expected: any confirmation other than exact `RECOVER ADMIN` exits without a dump or database mutation; confirmed recovery creates a non-empty `.dump` under ignored `runtime/recovery`, verifies new TOTP, prints ten new codes once, and invalidates every previous browser session.

- [ ] **Step 10: Write failing TOTP key re-encryption tests**

Create `TotpKeyReencryptionServiceTest` with a key ring containing old version `1` and active version `2`. Insert the only administrator with a seed encrypted under version `1`, retain the plaintext seed only inside a closable test fixture, and assert:

1. a restore-point failure leaves version, nonce, ciphertext, admin row version, sessions, and audit count unchanged;
2. a successful call stores key version `2` with a fresh 12-byte nonce and different ciphertext, while decrypting to the exact same seed and producing the same valid TOTP code at a fixed instant;
3. a new cipher configured with only version `2` can decrypt the persisted value after the transaction commits;
4. concurrent/stale admin-row CAS returns `409 AUTH_VERSION_CONFLICT` without a partial update;
5. running again when the row already uses the active version is an audited-free no-op and does not create a second dump;
6. the successful audit metadata contains only `fromKeyVersion`, `toKeyVersion`, `channel=LOCAL_CLI`, and `backupSha256`—never a nonce, ciphertext, seed, URI, code, path, or key material.

Run:

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am `
  -Dtest=TotpKeyReencryptionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation FAIL because `TotpKeyReencryptionService` does not exist.

- [ ] **Step 11: Implement backup-gated in-place re-encryption**

Use this application contract:

```java
package xyz.yychainsaw.portfolio.auth.cli;

public record TotpKeyReencryptionResult(
        boolean changed,
        int previousKeyVersion,
        int activeKeyVersion,
        String backupSha256) {}
```

```java
package xyz.yychainsaw.portfolio.auth.cli;

public interface TotpKeyReencryptionService {
    TotpKeyReencryptionResult reencryptToActiveKey();
}
```

The implementation first reads the singleton administrator without exposing ciphertext in logs. If `totp_key_version` already equals `TotpEnvelopeCrypto.activeKeyVersion()`, return `changed=false` before creating a restore point. Otherwise create and verify the same custom-format restore point used by recovery, call `TotpEnvelopeCrypto.reencrypt(adminId, existingEnvelope)` so plaintext bytes are wiped inside the crypto boundary, and execute one short transaction that CAS-updates `(totp_key_version, totp_nonce, totp_ciphertext, version)` and records `TOTP_KEY_REENCRYPTED`. Do not change the TOTP factor, password, recovery codes, publication data, or sessions. A backup, decrypt, encrypt, or CAS failure creates no credential/audit mutation.

- [ ] **Step 12: Add the exact local CLI and prove old-key removal**

Extend `AdminCliRunner` with `totp-reencrypt`. Require an interactive console and the exact phrase `REENCRYPT TOTP KEY`; print only changed/no-op state, safe key version numbers, and restore-point SHA-256. Never print the seed, provisioning URI, ciphertext, nonce, or recovery codes. Run the focused test again, then from a stopped local web process run:

```powershell
java -jar backend-parent\portfolio-server\target\portfolio-server-1.0.0-SNAPSHOT.jar `
  --portfolio.cli.command=totp-reencrypt
```

Expected: the first confirmed run creates a verified dump and changes version `1 -> 2`; a second run is a no-op with no new dump. Restart once with only key version `2`, authenticate with the unchanged authenticator code, and only then remove version `1` from protected configuration.

- [ ] **Step 13: Commit backup-gated recovery and key rotation**

```powershell
git add backend-parent/portfolio-server/src/main/resources/application.yml `
        backend-parent/portfolio-server/src/main/java/xyz/yychainsaw/portfolio/auth `
        backend-parent/portfolio-server/src/test/java/xyz/yychainsaw/portfolio/auth/cli
git diff --cached --check
git commit -m "feat: add backup-gated administrator maintenance CLIs"
```

---

### Task 15: Document operator procedures and run the complete first-slice gate

**Files:**

- Create: `docs/runbooks/admin-access.md`
- Verify: every file added by Tasks 1–14

- [ ] **Step 1: Prove the operator procedure is not yet documented**

```powershell
Test-Path docs/runbooks/admin-access.md
```

Expected: `False`.

- [ ] **Step 2: Create the administrator access and recovery runbook**

Create `docs/runbooks/admin-access.md`:

```markdown
Administrator Access and Recovery Runbook
=========================================

This runbook covers the single-administrator authentication boundary. There is no public registration, browser password reset, public TOTP reset, or public recovery-code regeneration endpoint.

## Local prerequisites

- Docker Engine 26 or a compatible current engine
- Java 17
- PowerShell 7
- PostgreSQL 17 client tools when running `admin-recover`

Create a local environment file that remains untracked:

```powershell
Copy-Item .env.example .env.local
$totp = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($totp)
$env:PORTFOLIO_TOTP_KEY_RING = "1=$([Convert]::ToBase64String($totp))"
$env:PORTFOLIO_TOTP_ACTIVE_KEY_VERSION = "1"
```

Fill every blank value in `.env.local` with a distinct generated local secret. Never commit `.env.local`, database dumps, recovery output, password values, TOTP seeds, recovery codes, or encryption keys.

## Start the local database

Import `.env.local` into the current process, then run:

```powershell
docker compose --env-file .env.local -f deploy/compose.dev.yml up -d
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am spring-boot:run
```

PostgreSQL is published only on loopback. Flyway connects as the migration role; the application datasource connects as the restricted runtime role. Spring Session schema initialization remains disabled because Flyway owns its tables.

## Bootstrap the administrator

Stop the web process, keep PostgreSQL running, and execute the local CLI from an interactive terminal:

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml -pl portfolio-server -am package
java -jar backend-parent\portfolio-server\target\portfolio-server-1.0.0-SNAPSHOT.jar `
  --portfolio.cli.command=admin-bootstrap
```

Enter the username and password only at hidden console prompts. The command prints a TOTP enrollment URI and ten recovery codes once. Store the recovery codes offline before clearing the terminal. Start the web process again after bootstrap finishes. Bootstrap refuses to create a second administrator.

## Browser authentication flow

1. `GET /api/admin/auth/csrf` obtains a CSRF token.
2. `POST /api/admin/auth/password` accepts the username and password.
3. `POST /api/admin/auth/second-factor` accepts method `TOTP` and a six-digit code, then rotates the session identifier.
4. `GET /api/admin/security/sessions` lists active and terminal metadata and marks the requesting row with `current=true`.
5. `POST /api/admin/security/sessions/{metadataId}/revoke` revokes a selected session. Revoking the row marked current is allowed, requires explicit UI confirmation, and immediately signs out that session.
6. `POST /api/admin/auth/logout` revokes and deletes the current session.

A recovery code uses the same second-factor endpoint with method `RECOVERY_CODE`. Each recovery code is single-use. The server stores only Argon2id hashes of recovery codes.

Session idle timeout is 30 minutes and absolute lifetime is 8 hours. Revoked and expired session metadata and reasons remain as security history while the live Spring Session row is deleted.

## Authenticated security settings

All settings calls require the authenticated session and CSRF token. Sensitive reauthentication uses policy `admin-security` and failures return a generic error.

- `POST /api/admin/security/password` requires current password, current TOTP, and a new policy-compliant password.
- `POST /api/admin/security/totp/enrollment` requires current password and TOTP, returns one enrollment URI, and stores only an encrypted ten-minute pending secret in the current server session.
- `POST /api/admin/security/totp/confirm` requires the enrollment UUID and a code from the new secret; success returns ten replacement recovery codes once.
- `POST /api/admin/security/recovery-codes/regenerate` requires current password and TOTP and returns ten replacement codes once.

Every successful settings operation is audited and marks every other session revoked before attempting physical session deletion. Store newly returned recovery codes offline and clear the browser view after confirming storage.

`GET /api/admin/audit` provides the authenticated audit page with opaque cursor plus optional action, outcome, from, to, and limit filters. The response is no-store and contains only allowlisted redacted metadata.

## Recover administrator access

Recovery is a local console operation. Confirm that PostgreSQL 17 `pg_dump` is on `PATH`, set `PORTFOLIO_RECOVERY_DIRECTORY` to a protected directory outside the source tree, and stop the web process. Run:

```powershell
java -jar backend-parent\portfolio-server\target\portfolio-server-1.0.0-SNAPSHOT.jar `
  --portfolio.cli.command=admin-recover
```

The command performs a custom-format `pg_dump` and SHA-256 checksum before changing credentials. It aborts when backup creation fails. At the hidden prompt, type the exact confirmation phrase `RECOVER ADMIN`, then enter and confirm the new password. The command creates a new TOTP seed, replaces all recovery-code hashes, records an immutable audit event, marks all sessions revoked, and deletes all live server-side sessions.

Record the backup checksum in the protected operator log. Store the new recovery codes offline. Restart the web process and verify password plus TOTP login before expiring backups according to the backup-retention policy.

## Rotate the TOTP encryption key

Generate a new 256-bit key and add it to `PORTFOLIO_TOTP_KEY_RING` without removing the old key version. Set `PORTFOLIO_TOTP_ACTIVE_KEY_VERSION` to the new version and perform a controlled restart while both versions remain present.

Stop the web process and run local `--portfolio.cli.command=totp-reencrypt`. Confirm exact phrase `REENCRYPT TOTP KEY`; the command first creates a verified database restore point, then re-encrypts the unchanged seed with a fresh nonce under the active version and records a redacted audit event. Restart with both versions, verify login with the existing authenticator code, inspect the stored key version/audit, then restart once with only the new key. Remove the old key from protected storage only after that proof. If the old key is unavailable before re-encryption or all login factors are lost, use the backup-gated `admin-recover` procedure instead.

## Production controls

- Set `PORTFOLIO_SESSION_COOKIE_SECURE=true` behind HTTPS.
- Keep `PORTFOLIO_ALLOW_DEVELOPMENT_CORS=false`; browser API calls remain same-origin.
- Keep migration and runtime database credentials separate. The runtime role has no DDL privileges.
- Keep every TOTP encryption key in the production secret manager, never in Git or container images.
- Accept `X-Real-IP` only from a loopback reverse proxy and store only a masked client address summary.
- Monitor failed authentication, recovery-code use, session revocation, bootstrap, and recovery audit events without logging credentials or secrets.
- Do not add JWTs, refresh tokens, public password recovery, or a second administrator through an operational shortcut.
```

- [ ] **Step 3: Run the green documentation check**

```powershell
Test-Path docs/runbooks/admin-access.md
Select-String -Path docs/runbooks/admin-access.md -Pattern 'pg_dump','RECOVER ADMIN','REENCRYPT TOTP KEY','all sessions revoked','same-origin'
```

Expected: `True` and matches for all five controls.

- [ ] **Step 4: Run the complete backend verification suite against PostgreSQL 17**

```powershell
.\backend-parent\mvnw.cmd -f backend-parent\pom.xml --no-transfer-progress clean verify
```

Expected: all unit, Spring integration, Testcontainers PostgreSQL 17, Flyway, authentication, security-settings, CSRF, session, redacted audit query, bootstrap, recovery, and TOTP key re-encryption tests pass; all three modules finish with `BUILD SUCCESS`.

- [ ] **Step 5: Prove the migration and Spring Session ownership boundary**

```powershell
$migrations = Get-ChildItem backend-parent\portfolio-server\src\main\resources\db\migration -File | Sort-Object Name
$migrations.Name
if (($migrations.Name -join ',') -ne 'V1__extensions_and_core.sql,V2__authentication_and_audit.sql') { throw 'Unexpected migration set' }
Select-String -Path backend-parent\portfolio-server\src\main\resources\application.yml -Pattern 'initialize-schema: never','cleanup-cron: "-"'
```

Expected: only `V1__extensions_and_core.sql` and `V2__authentication_and_audit.sql` are printed, and both Spring Session settings match. Plan 02 owns `V3`.

- [ ] **Step 6: Prove the exact reusable cross-plan contracts**

```powershell
Select-String -Path backend-parent\portfolio-server\src\main\java\xyz\yychainsaw\portfolio\audit\AuditService.java -Pattern 'void record\(AuditCommand command\)'
Select-String -Path backend-parent\portfolio-server\src\main\java\xyz\yychainsaw\portfolio\auth\CurrentAdminProvider.java -Pattern 'UUID requireAdminId\(\)'
Select-String -Path backend-parent\portfolio-common\src\main\java\xyz\yychainsaw\portfolio\common\error\DomainException.java -Pattern 'String code','HttpStatus status','Map<String, String> fieldErrors'
Select-String -Path backend-parent\portfolio-common\src\main\java\xyz\yychainsaw\portfolio\common\ratelimit\RateLimiter.java -Pattern 'RateLimitDecision consume\(String policy, String subject\)'
Select-String -Path backend-parent\portfolio-common\src\main\java\xyz\yychainsaw\portfolio\common\ratelimit\RateLimitDecision.java -Pattern 'boolean allowed','long retryAfterSeconds'
$policyConfig = Get-Content backend-parent\portfolio-server\src\main\resources\application.yml -Raw
@('admin-login','admin-security','public-contact','public-events') | ForEach-Object {
  if ($policyConfig -notmatch "(?m)^\s{6}$([regex]::Escape($_)):\s*$") { throw "Missing rate-limit policy: $_" }
}
Test-Path backend-parent\portfolio-pojo\src\main\java\xyz\yychainsaw\portfolio\api\admin\audit\AdminAuditItem.java
Test-Path backend-parent\portfolio-pojo\src\main\java\xyz\yychainsaw\portfolio\api\admin\audit\AdminAuditPage.java
```

Expected: every exact contract matches, all four policy checks pass, and both Plan 04 audit DTO checks return `True`. Administrator login uses `admin-login`, authenticated credential maintenance uses `admin-security`, contact intake uses `public-contact`, and consented analytics batches use `public-events` through the bounded shared limiter.

- [ ] **Step 7: Scan tracked changes for security shortcuts and accidentally tracked secrets**

```powershell
$forbidden = git grep -n -I -i -E 'md5|java-jwt|jjwt|fastjson|allowedOriginPatterns|SessionCreationPolicy\.STATELESS|csrf.*disable' -- ':(glob)backend-parent/**/src/main/**/*.java'
if ($LASTEXITCODE -eq 0) { $forbidden; throw 'Forbidden security shortcut found' }
$trackedSecrets = git ls-files | Select-String -Pattern '(^|/)(\.env\.local|\.env\.production|runtime-secrets|recovery-backups)(/|$)|\.(dump|backup)$'
if ($trackedSecrets) { $trackedSecrets; throw 'A local secret or backup path is tracked' }
git status --short
```

Expected: both scans are empty and status contains only intended changes.

- [ ] **Step 8: Re-run the preserved frontend baseline**

```powershell
Set-Location frontend
npm ci
npm run type-check
npm run build
Set-Location ..
git status --short
```

Expected: dependency installation, type checking, and production build pass. Only ignored build output is regenerated; no tracked frontend source changes.

- [ ] **Step 9: Commit the operator handoff**

```powershell
git add docs/runbooks/admin-access.md
git diff --cached --check
git commit -m "docs: document administrator access recovery"
```

- [ ] **Step 10: Capture final review evidence**

```powershell
git log --oneline --decorate -18
git diff --check main HEAD
git diff --stat main HEAD
git status --short
```

Expected: small commits cover baseline, scaffold, database, migrations, error/rate-limit infrastructure, audit writes, crypto, persistence, sessions, bootstrap, web authentication, security settings, audit queries, recovery/key re-encryption, and the runbook. The diff check is clean and the worktree has no unintended changes.

## Final acceptance checklist

- [ ] The existing frontend was inventoried, tested, preserved, and committed as a baseline before backend work began.
- [ ] Maven Wrapper 3.9.11 builds exactly three modules under `backend-parent/` with Java 17 and Spring Boot 3.5.7.
- [ ] Local PostgreSQL uses `postgres:17-bookworm`, loopback-only publication, and separate migration/runtime roles.
- [ ] Flyway owns only `V1__extensions_and_core.sql` and `V2__authentication_and_audit.sql`; Spring Session runtime initialization and built-in cleanup are disabled.
- [ ] The server returns non-leaking `ProblemDetail` responses with trace IDs and field errors.
- [ ] The exact audit, current-administrator, domain-error, and bounded reusable rate-limiter contracts compile.
- [ ] Passwords and recovery codes use Argon2id; TOTP seeds use versioned AES-256-GCM with environment-only keys.
- [ ] Password and TOTP are separate stages, successful completion rotates the session identifier, and recovery codes are single-use.
- [ ] Authenticated password/TOTP/recovery-code settings require CSRF plus current-factor reauthentication, are rate-limited and audited, expose one-time values only in no-store responses, and revoke every session except the current one atomically with each change.
- [ ] The audit GET API uses authenticated keyset pagination and stable filters, returns only shared allowlisted DTOs with redacted metadata, is no-store, and provides no audit mutation route.
- [ ] CSRF protects every state change; browser access is same-origin by default; headers and production cookie settings are explicit.
- [ ] Spring Session JDBC is the only browser authentication state; nullable metadata references stable primary IDs and retains terminal reasons/history.
- [ ] Bootstrap is local, single-use, interactive, auditable, and reveals enrollment material once.
- [ ] Recovery creates and checksums a PostgreSQL backup before mutation, requires exact confirmation, revokes every session, and records immutable audit.
- [ ] TOTP key rotation keeps old/new keys only for the migration window, creates a verified backup, re-encrypts the unchanged seed under the active version without a plaintext string, proves login with only the new key, and records redacted audit.
- [ ] Backend tests, PostgreSQL 17 integration tests, migration tests, frontend baseline checks, security scans, and Git checks all pass.
