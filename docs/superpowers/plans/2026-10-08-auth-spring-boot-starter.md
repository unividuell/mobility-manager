# Anmeldung über auth-spring-boot-starter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** mobility-manager meldet über `org.unividuell:auth-spring-boot-starter:0.2.0-SNAPSHOT` an: lokal über den Test-User-Picker der Lib, in production über die GitHub App der Organisation.

**Architecture:** Erst das Plattform-Upgrade (Boot 4.1.1, Kotlin 2.4.10), dann die Accounts auf `(provider, subject)` plus `account_id` (UUID) umstellen, solange noch der alte Login läuft. Danach die Lib einbinden: Sie übernimmt `oauth2Login`, CSRF und Logout. Die App-Chain dreht nur die drei SPA-Vorgaben der Lib zurück (401 → Redirect, Logout 204 → Redirect, kein Request-Cache → `CookieRequestCache`). Zum Schluss bekommen die Templates das CSRF-Token für htmx und das Logout-Formular, und die Doku wird nachgezogen.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.1 (MVC, Security, Spring Session JDBC), Spring Data JDBC, SQLite + Flyway, Thymeleaf + htmx 2.0.4, Tests: JUnit 5 + kotest-Matcher + MockMvc (Kotlin-DSL).

**Spec:** `docs/superpowers/specs/2026-10-08-auth-spring-boot-starter-design.md`

**Vorab geprüft:** Jeder Task wurde in einer Wegwerf-Kopie des Repos genau so umgesetzt, wie er hier steht, und die Testsuite lief nach jedem Task grün (154 → 160 → 173 → 176 Tests). V7/V8 liefen zusätzlich auf einer Kopie der prod-nahen DB aus dem Hauptcheckout: 2 User, 3 Fahrzeuge, 82k Sessions, Ergebnis korrekt. Auch Picker-Login, htmx-POST mit Header, Formular-POST mit `_csrf`, 403 ohne Token, Logout und Health-Probe ohne Session wurden per curl gefahren.

## Global Constraints

- Branch `feature/auth-spring-boot-starter-76a36e`, dort committen. Commit-Stil: Conventional Commits (`feat(auth): …`), mit Trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Java 25, Spring Boot `4.1.1`, Kotlin `2.4.10`, Spring Modulith `2.1.1`. Lib `org.unividuell:auth-spring-boot-starter:0.2.0-SNAPSHOT` aus dem Checkout `/opt/unividuell/projects/auth-spring-boot-starter` (HEAD `6557d29`, sauber).
- Die Lib kommt über `maven-repo/` im Projektroot (committet), Repository-ID `unividuell-local`, `<snapshots><updatePolicy>always</updatePolicy></snapshots>`.
- GitHub App Client-ID `Iv23liJTgm6EeJ6XshRh`. Secret `${MOBILITY_MANAGER_GITHUB_CLIENT_SECRET}` ohne Default. Callback `https://mobility.unividuell.org/login/oauth2/code/github`.
- `users.id` bleibt INTEGER. `account_id` ist eine kanonische UUID als TEXT (kleingeschrieben, `8-4-4-4-12`). Spring Data JDBC schreibt und liest `java.util.UUID` in dieser Form ohne Converter (geprüft).
- Tests: `@SpringBootTest` + `@ActiveProfiles("test")`. Einzige Ausnahme ist `TestLoginIntegrationTest`, denn ein aktives Profil würde einen Test-Login-Key verlangen. `./mvnw test -Dtest='<Klasse>'` für einzelne Klassen, `./mvnw test` für alle.
- **Nie** `SecurityMockMvcRequestPostProcessors.csrf()` verwenden. Es ersetzt das Token-Repository im geteilten `CsrfFilter` dauerhaft durch eine Session-Variante, danach legt jeder Request in diesem Kontext eine Session an (`AnonymousSessionIntegrationTest` bricht reihenfolgeabhängig). Stattdessen `withCsrfToken()` / `signedInAs(user)` aus `TestUsers.kt` (Task 3).
- Anonyme Requests dürfen keine Session anlegen. Kein `HttpSession`-Parameter und kein `getSession()` in Code, der für anonyme Requests läuft.
- Kommentare im Code sind englisch, UI-Texte deutsch.

## Review Focus

1. **Ein GitHub-User, der sich vor V7 angemeldet hatte, meldet sich wieder an:** Er muss auf demselben Account mit seinen Fahrzeugen landen, nicht auf einem neuen. → Task 2, `a user V7 migrated signs in to the same account`.
2. **Lokaler Login über den Picker:** Er muss über `AppUserService` einen `test`-Account anlegen und die App öffnen. In den Tests ist der Picker sonst aus. → Task 3, `TestLoginIntegrationTest`.
3. **Ein Header-Token, das nicht zum Cookie passt:** Es muss abgewiesen werden. Ein CSRF-Schutz, der nur auf „Header vorhanden“ prüft, wäre keiner. → Task 3, `a token in the header that differs from the cookie is refused`.
4. **htmx-POST nach abgelaufener Session:** Das `XSRF-TOKEN`-Cookie ist noch da. Erwartet wird ein 302 auf `/login` wie bei Seitenaufrufen, kein 401 und kein 403. → Task 3, `an htmx request after the session has gone is sent to the login page`.
5. **„Abmelden“ im Browser:** Das Formular muss das Token tragen, sonst antwortet `/logout` mit 403. → Task 4, `the logout form carries the CSRF token`.

## Abweichungen von der Spec (beim Vorab-Prüfen gefunden)

- `spring-modulith-observability` ist in 2.1 nur noch ein POM-Aggregator ohne Jar, die Implementierung liegt in `spring-modulith-observability-core` (Task 1).
- `AppUserService.upsert(provider, subject, login, name)` statt `upsert(identity: ExternalIdentity)`. Damit ist die Account-Umstellung (Task 2) lib-unabhängig und als eigener grüner Commit möglich. `provision(identity, roles)` übersetzt in Task 3.
- Tests senden das CSRF-Token wie ein Browser (Cookie `XSRF-TOKEN` plus Header `X-XSRF-TOKEN`) über den Helper und nicht per `.with(csrf())`, siehe Global Constraints.
- Kein UUID-Converter nötig.
- Der Name im Header wandert mit dem Principal-Wechsel in Task 3, weil der `AuthPrincipal` kein `displayName` trägt.

---

### Task 1: Plattform-Upgrade auf Boot 4.1.1 / Kotlin 2.4.10 / Modulith 2.1.1

**Files:**
- Modify: `pom.xml` (Parent-Version, `kotlin.version`, `spring-modulith.version`, Observability-Artefakt)

**Interfaces:**
- Consumes: —
- Produces: Build auf Boot 4.1.1 / Kotlin 2.4.10, die Voraussetzung der Lib.

- [ ] **Step 1: Ausgangslage messen**

Run: `./mvnw -B --no-transfer-progress test`
Expected: `Tests run: 154, Failures: 0, Errors: 0`

- [ ] **Step 2: Versionen anheben**

In `pom.xml`:

```xml
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
```

```xml
		<!-- 2.3.0 was the first release with JVM target 25 support; 2.4.10 matches the auth lib -->
		<kotlin.version>2.4.10</kotlin.version>
		<spring-modulith.version>2.1.1</spring-modulith.version>
```

und im Dependency-Block (Runtime-Scope bleibt):

```xml
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<!-- since 2.1 the plain artifact is a pom aggregator; the code lives in -core -->
			<artifactId>spring-modulith-observability-core</artifactId>
			<scope>runtime</scope>
		</dependency>
```

- [ ] **Step 3: Alle Tests laufen lassen**

Run: `./mvnw -B --no-transfer-progress test`
Expected: `Tests run: 154, Failures: 0, Errors: 0`. Ohne den Wechsel auf `spring-modulith-observability-core` aus Step 2 schlägt der Build vorher mit `Could not find artifact org.springframework.modulith:spring-modulith-observability:jar:2.1.1` fehl.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "chore: upgrade to Spring Boot 4.1.1, Kotlin 2.4.10 and Modulith 2.1.1" -m "The auth lib is built on this pairing. Modulith 2.1 turned
spring-modulith-observability into a pom aggregator; its code now
lives in spring-modulith-observability-core." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Accounts nach (provider, subject) mit account_id (V7)

Noch ohne Lib: Der alte GitHub-Login läuft weiter und findet seine User über `("github", <id>)`.

**Files:**
- Create: `src/main/resources/db/migration/V7__key_users_by_provider_and_subject.sql`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/user/AppUser.kt` (ganze Datei)
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/user/AppUserRepository.kt` (ganze Datei)
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/user/AppUserService.kt` (ganze Datei)
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/user/CurrentUser.kt:19-20`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/user/GithubOAuth2UserService.kt:28`
- Create: `src/test/kotlin/org/unividuell/mobility/manager/MigrationWithDataTest.kt`
- Create: `src/test/kotlin/org/unividuell/mobility/manager/user/AppUserRepositoryIntegrationTest.kt`
- Modify: `src/test/kotlin/org/unividuell/mobility/manager/user/AppUserServiceIntegrationTest.kt` (ganze Datei)
- Modify (Aufrufe von `users.upsert`): `fuel/FuelControllerIntegrationTest.kt`, `vehicle/VehicleControllerIntegrationTest.kt`, `vehicle/VehicleServiceIntegrationTest.kt`, `parts/PartControllerIntegrationTest.kt`, `parts/PartServiceIntegrationTest.kt`, `parts/PartRepositoryIntegrationTest.kt` (alle unter `src/test/kotlin/org/unividuell/mobility/manager/`)

**Interfaces:**
- Consumes: —
- Produces:
  - Tabelle `users(id INTEGER PK, account_id TEXT UNIQUE NOT NULL, provider TEXT NOT NULL, subject TEXT NOT NULL, login, display_name, created_at, UNIQUE(provider, subject))`
  - `data class AppUser(id: Long?, accountId: UUID, provider: String, subject: String, login: String, displayName: String)`
  - `AppUserRepository.findByProviderAndSubject(provider: String, subject: String): AppUser?`, `AppUserRepository.findByAccountId(accountId: UUID): AppUser?`
  - `AppUserService.upsert(provider: String, subject: String, login: String, name: String?): AppUser`

- [ ] **Step 1: Failing Test für die Migration auf befüllter DB**

`src/test/kotlin/org/unividuell/mobility/manager/MigrationWithDataTest.kt`:

```kotlin
package org.unividuell.mobility.manager

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Path
import java.util.UUID

/**
 * Migrations that rebuild a table run against a populated database here: the test
 * suite's own database is empty when Flyway runs, so it would never see a row move.
 */
class MigrationWithDataTest {

    @Test
    fun `V7 keys existing users by provider and subject and keeps every row that points at them`(@TempDir dir: Path) {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite:${dir.resolve("mm.db")}?foreign_keys=true", true)
        try {
            val jdbc = JdbcTemplate(dataSource)
            flyway(dataSource, target = "6").migrate()
            jdbc.update("INSERT INTO users (id, github_id, login, display_name) VALUES (1, 4711, 'octocat', 'The Octocat'), (2, 1234, 'stranger', 'Stranger')")
            jdbc.update("INSERT INTO vehicles (id, name, color) VALUES (10, 'Kombi', '#06b6d4')")
            jdbc.update("INSERT INTO vehicle_managers (vehicle_id, user_id) VALUES (10, 1), (10, 2)")
            jdbc.update("INSERT INTO tags (user_id, name) VALUES (1, 'kupplung')")

            flyway(dataSource, target = "7").migrate()

            val rows = jdbc.queryForList("SELECT id, provider, subject, login, display_name, account_id FROM users ORDER BY id")
            rows.map { listOf(it["id"], it["provider"], it["subject"], it["login"], it["display_name"]) } shouldBe listOf(
                listOf(1, "github", "4711", "octocat", "The Octocat"),
                listOf(2, "github", "1234", "stranger", "Stranger"),
            )
            rows.map { UUID.fromString(it["account_id"] as String).version() } shouldBe listOf(4, 4)
            jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_managers", Int::class.java) shouldBe 2
            jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE user_id = 1", Int::class.java) shouldBe 1
            jdbc.queryForList("PRAGMA foreign_key_check").shouldBeEmpty()
        } finally {
            dataSource.destroy()
        }
    }

    private fun flyway(dataSource: SingleConnectionDataSource, target: String): Flyway =
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load()
}
```

- [ ] **Step 2: Test laufen lassen, er muss scheitern**

Run: `./mvnw -B --no-transfer-progress test -Dtest=MigrationWithDataTest`
Expected: FAIL. Flyway meldet, dass Zielversion 7 nicht existiert (`Cannot find target version 7` o. ä.).

- [ ] **Step 3: Migration V7 schreiben**

`src/main/resources/db/migration/V7__key_users_by_provider_and_subject.sql`:

```sql
-- Accounts are keyed by (provider, subject), the way the auth lib hands every sign-in over, and
-- carry a UUID account_id for its session principal. The integer id stays, so vehicle_managers and
-- tags keep their foreign keys. SQLite can neither drop the UNIQUE github_id nor relax its NOT NULL,
-- so the table is rebuilt: deferring the foreign keys to the commit lets DROP TABLE orphan the
-- child rows for a moment, and re-inserting the same ids into the new table resolves them again.
PRAGMA defer_foreign_keys = ON;

CREATE TABLE users_old AS SELECT * FROM users;
DROP TABLE users;

CREATE TABLE users (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id   TEXT    NOT NULL UNIQUE,
    provider     TEXT    NOT NULL,
    subject      TEXT    NOT NULL,
    login        TEXT    NOT NULL,
    display_name TEXT    NOT NULL,
    created_at   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, subject)
);

-- account_id: a random version-4 UUID in canonical form, evaluated per row
INSERT INTO users (id, account_id, provider, subject, login, display_name, created_at)
SELECT id,
       lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' ||
       substr(lower(hex(randomblob(2))), 2) || '-' ||
       substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' ||
       lower(hex(randomblob(6))),
       'github', CAST(github_id AS TEXT), login, display_name, created_at
FROM users_old;

DROP TABLE users_old;
```

- [ ] **Step 4: Migrationstest grün**

Run: `./mvnw -B --no-transfer-progress test -Dtest=MigrationWithDataTest`
Expected: PASS (1 Test)

- [ ] **Step 5: Failing Tests für Repository und Service**

`src/test/kotlin/org/unividuell/mobility/manager/user/AppUserRepositoryIntegrationTest.kt`:

```kotlin
package org.unividuell.mobility.manager.user

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.unividuell.mobility.manager.DatabaseCleaner
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class AppUserRepositoryIntegrationTest @Autowired constructor(
    private val repository: AppUserRepository,
    private val jdbc: JdbcTemplate,
    private val db: DatabaseCleaner,
) {

    @BeforeEach
    fun cleanDb() {
        db.clean()
    }

    @Test
    fun `account_id is stored as canonical UUID text, the form V7 writes`() {
        val accountId = UUID.randomUUID()
        repository.save(
            AppUser(accountId = accountId, provider = "github", subject = "4711", login = "octocat", displayName = "The Octocat"),
        )

        val row = jdbc.queryForMap("SELECT typeof(account_id) AS type, account_id FROM users")
        row["type"] shouldBe "text"
        row["account_id"] shouldBe accountId.toString()
    }

    @Test
    fun `a row V7 migrated is found by its account id`() {
        val accountId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (account_id, provider, subject, login, display_name) VALUES (?, 'github', '4711', 'octocat', 'The Octocat')",
            accountId.toString(),
        )

        repository.findByAccountId(accountId)?.login shouldBe "octocat"
    }
}
```

`src/test/kotlin/org/unividuell/mobility/manager/user/AppUserServiceIntegrationTest.kt` (ganze Datei ersetzen):

```kotlin
package org.unividuell.mobility.manager.user

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.unividuell.mobility.manager.DatabaseCleaner
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class AppUserServiceIntegrationTest @Autowired constructor(
    private val service: AppUserService,
    private val repository: AppUserRepository,
    private val jdbc: JdbcTemplate,
    private val db: DatabaseCleaner,
) {

    @BeforeEach
    fun cleanDb() {
        db.clean()
    }

    @Test
    fun `first sign-in creates the user`() {
        val user = service.upsert(provider = "github", subject = "4711", login = "octocat", name = "The Octocat")

        user.id.shouldNotBeNull()
        user.provider shouldBe "github"
        user.subject shouldBe "4711"
        user.login shouldBe "octocat"
        user.displayName shouldBe "The Octocat"
        repository.count() shouldBe 1
    }

    @Test
    fun `repeat sign-in updates mirrored fields and keeps the account id`() {
        val first = service.upsert(provider = "github", subject = "4711", login = "octocat", name = "The Octocat")
        val second = service.upsert(provider = "github", subject = "4711", login = "octocat-renamed", name = "Mona Lisa")

        repository.count() shouldBe 1
        second.id shouldBe first.id
        second.accountId shouldBe first.accountId
        second.login shouldBe "octocat-renamed"
        second.displayName shouldBe "Mona Lisa"
    }

    @Test
    fun `the same login from two providers is two accounts`() {
        val github = service.upsert(provider = "github", subject = "4711", login = "prof", name = null)
        val test = service.upsert(provider = "test", subject = "prof", login = "prof", name = null)

        test.id shouldNotBe github.id
        test.accountId shouldNotBe github.accountId
    }

    @Test
    fun `a missing or blank name falls back to the login`() {
        service.upsert(provider = "github", subject = "1", login = "noname", name = null).displayName shouldBe "noname"
        service.upsert(provider = "github", subject = "2", login = "blank", name = " ").displayName shouldBe "blank"
    }

    @Test
    fun `a user V7 migrated signs in to the same account`() {
        val migrated = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (account_id, provider, subject, login, display_name) VALUES (?, 'github', '868171', 'cleemansen', 'Clemens')",
            migrated.toString(),
        )

        val user = service.upsert(provider = "github", subject = "868171", login = "cleemansen", name = "Clemens")

        repository.count() shouldBe 1
        user.accountId shouldBe migrated
    }
}
```

- [ ] **Step 6: Tests laufen lassen, sie müssen scheitern**

Run: `./mvnw -B --no-transfer-progress test -Dtest='AppUser*IntegrationTest'`
Expected: FAIL beim Kompilieren (`Unresolved reference 'accountId'`, `No parameter with name 'provider'` u. ä.)

- [ ] **Step 7: Entity, Repository, Service**

`src/main/kotlin/org/unividuell/mobility/manager/user/AppUser.kt` (ganze Datei):

```kotlin
package org.unividuell.mobility.manager.user

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("users")
data class AppUser(
    @Id val id: Long? = null,
    // The id the auth lib's session principal carries (AuthPrincipal.id).
    val accountId: UUID,
    // Who the sign-in door says this is: provider ("github", "test") and its
    // stable id, as text. Login and display name are refreshed at every sign-in.
    val provider: String,
    val subject: String,
    val login: String,
    val displayName: String,
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP; not mapped
    // here to avoid Instant ↔ SQLite typing friction (same as FuelEntry).
)
```

`src/main/kotlin/org/unividuell/mobility/manager/user/AppUserRepository.kt` (ganze Datei):

```kotlin
package org.unividuell.mobility.manager.user

import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface AppUserRepository : CrudRepository<AppUser, Long> {
    fun findByProviderAndSubject(provider: String, subject: String): AppUser?
    fun findByAccountId(accountId: UUID): AppUser?
}
```

`src/main/kotlin/org/unividuell/mobility/manager/user/AppUserService.kt` (ganze Datei):

```kotlin
package org.unividuell.mobility.manager.user

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppUserService(
    private val repository: AppUserRepository,
) {

    /**
     * Creates the user on first sign-in, or refreshes the mirrored fields (login,
     * display name) on later ones. Keyed on the provider's stable [subject]; the
     * display name falls back to the login when the provider has no name.
     */
    fun upsert(provider: String, subject: String, login: String, name: String?): AppUser {
        val displayName = name?.takeIf { it.isNotBlank() } ?: login
        val existing = repository.findByProviderAndSubject(provider, subject)
        val toSave = existing
            ?.copy(login = login, displayName = displayName)
            ?: AppUser(
                accountId = UUID.randomUUID(),
                provider = provider,
                subject = subject,
                login = login,
                displayName = displayName,
            )
        return repository.save(toSave)
    }
}
```

- [ ] **Step 8: Den alten GitHub-Login auf die neuen Schlüssel umstellen** (übergangsweise, Task 3 löscht beides)

In `src/main/kotlin/org/unividuell/mobility/manager/user/CurrentUser.kt` die Suche ersetzen:

```kotlin
        return repository.findByProviderAndSubject("github", githubId.toString())
            ?: error("no persisted user for GitHub id $githubId")
```

In `src/main/kotlin/org/unividuell/mobility/manager/user/GithubOAuth2UserService.kt` die Zeile `users.upsert(githubId, login, displayName)` ersetzen durch:

```kotlin
        users.upsert(provider = "github", subject = githubId.toString(), login = login, name = attributes["name"] as String?)
```

(`displayName` bleibt als Variable für das Principal-Attribut darunter erhalten.)

- [ ] **Step 9: Bestehende Test-Aufrufe auf die neue Signatur umstellen**

```bash
perl -pi -e 's/users\.upsert\(githubId, login = /users.upsert(provider = "github", subject = githubId.toString(), login = /; s/users\.upsert\((?:githubId = )?(\d+)L, login = /users.upsert(provider = "github", subject = "$1", login = /; s/(users\.upsert\(provider = .*?), displayName = /$1, name = /' \
  src/test/kotlin/org/unividuell/mobility/manager/{fuel/FuelControllerIntegrationTest,vehicle/VehicleControllerIntegrationTest,vehicle/VehicleServiceIntegrationTest,parts/PartControllerIntegrationTest,parts/PartServiceIntegrationTest,parts/PartRepositoryIntegrationTest}.kt
grep -rn "users.upsert" src/test/kotlin | grep -v 'provider = "github"'
```

Expected: Das `grep` gibt nichts aus. Ein Beispiel für das Ergebnis: `users.upsert(provider = "github", subject = "1234", login = "stranger", name = "Stranger")`.

- [ ] **Step 10: Alle Tests**

Run: `./mvnw -B --no-transfer-progress test`
Expected: `Tests run: 160, Failures: 0, Errors: 0`

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration/V7__key_users_by_provider_and_subject.sql src/main/kotlin/org/unividuell/mobility/manager/user src/test/kotlin
git commit -m "feat(user): key accounts by provider and subject" -m "The auth lib hands every sign-in over as (provider, subject) and keeps
a UUID account id in its session principal. V7 rebuilds users with
both, keeping the integer id so vehicle_managers and tags keep their
foreign keys; existing rows become github/<id>. The GitHub login keeps
working on the new keys until the lib takes over." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Anmeldung über die Lib (inklusive V8, Principal-Wechsel, Konfiguration)

**Files:**
- Create: `maven-repo/org/unividuell/auth-spring-boot-starter/**` (per `deploy`)
- Modify: `pom.xml` (Repository, Property, Lib statt `spring-boot-starter-oauth2-client`, ohne `thymeleaf-extras-springsecurity6`)
- Create: `src/main/resources/db/migration/V8__drop_all_sessions.sql`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/SecurityConfig.kt` (ganze Datei)
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/GlobalModelAdvice.kt` (ganze Datei)
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/user/AppUserService.kt` (implementiert `AccountProvisioner`)
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/user/CurrentUser.kt` (ganze Datei)
- Delete: `src/main/kotlin/org/unividuell/mobility/manager/user/GithubOAuth2UserService.kt`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/{fuel/FuelController,parts/PartController,vehicle/VehicleController}.kt` (Principal-Typ)
- Modify: `src/main/resources/templates/fragments/header.html:2-4,32`, `src/main/resources/templates/vehicles/index.html:4`
- Modify: `src/main/resources/application.yaml`, `src/main/resources/application-production.yaml`, `src/test/resources/application-test.yaml`
- Create: `src/test/kotlin/org/unividuell/mobility/manager/user/TestUsers.kt`
- Create: `src/test/kotlin/org/unividuell/mobility/manager/SecurityIntegrationTest.kt`
- Create: `src/test/kotlin/org/unividuell/mobility/manager/user/TestLoginIntegrationTest.kt`
- Modify: `src/test/kotlin/org/unividuell/mobility/manager/MigrationWithDataTest.kt` (V8-Test)
- Modify: `src/test/kotlin/org/unividuell/mobility/manager/user/AppUserServiceIntegrationTest.kt` (provision-Test)
- Modify: `src/test/kotlin/org/unividuell/mobility/manager/{fuel/FuelControllerIntegrationTest,vehicle/VehicleControllerIntegrationTest,parts/PartControllerIntegrationTest}.kt` (Login-Helper)

**Interfaces:**
- Consumes: `AppUser`, `AppUserRepository.findByAccountId(UUID)`, `AppUserService.upsert(provider, subject, login, name)` aus Task 2.
- Produces:
  - `AppUserService : AccountProvisioner` mit `provision(identity: ExternalIdentity, roles: Set<String>): UUID`
  - `CurrentUser.require(principal: AuthPrincipal): AppUser`
  - Model-Attribut `currentUserName: String?` (aus `GlobalModelAdvice`)
  - Test-Helper (`src/test/kotlin/.../user/TestUsers.kt`): `fun AppUser.principal(): AuthPrincipal`, `fun signedInAs(user: AppUser): RequestPostProcessor`, `fun withCsrfToken(): RequestPostProcessor`, `const val TEST_CSRF_TOKEN = "test-csrf-token"`

- [ ] **Step 1: Lib-Snapshot ins Projekt deployen**

```bash
REPO=$(git rev-parse --show-toplevel)
git -C /opt/unividuell/projects/auth-spring-boot-starter status --short   # must print nothing
git -C /opt/unividuell/projects/auth-spring-boot-starter log -1 --format=%h  # 6557d29
(cd /opt/unividuell/projects/auth-spring-boot-starter && ./mvnw -B --no-transfer-progress deploy -DskipTests -Dmaven.install.skip=true -DaltDeploymentRepository=app::file://$REPO/maven-repo)
find maven-repo -type f | sort
```

Expected: `BUILD SUCCESS`. Unter `maven-repo/org/unividuell/auth-spring-boot-starter/` liegen `maven-metadata.xml` (+ md5/sha1) und `0.2.0-SNAPSHOT/` mit `auth-spring-boot-starter-0.2.0-<timestamp>-1.jar`/`.pom` (+ md5/sha1) und `maven-metadata.xml` (+ md5/sha1). `-Dmaven.install.skip=true` ist wichtig: Ohne den Schalter landet die Lib auch in `~/.m2` und verdeckt ein fehlendes Repository bis zum CI.

- [ ] **Step 2: pom.xml**

Unter `<properties>` ergänzen:

```xml
		<!-- Ours, from maven-repo/: not on Maven Central (see README, "Auth lib"). -->
		<unividuell-auth.version>0.2.0-SNAPSHOT</unividuell-auth.version>
```

Direkt nach `</properties>`:

```xml
	<repositories>
		<repository>
			<id>unividuell-local</id>
			<url>file://${project.basedir}/maven-repo</url>
			<!-- A redeployed snapshot must win over the copy in ~/.m2 and in CI's Maven cache. -->
			<snapshots>
				<updatePolicy>always</updatePolicy>
			</snapshots>
		</repository>
	</repositories>
```

Die Dependency `org.springframework.boot:spring-boot-starter-oauth2-client` ersetzen durch:

```xml
		<dependency>
			<groupId>org.unividuell</groupId>
			<artifactId>auth-spring-boot-starter</artifactId>
			<version>${unividuell-auth.version}</version>
		</dependency>
```

Die Dependency `org.thymeleaf.extras:thymeleaf-extras-springsecurity6` samt Leerzeile darunter löschen. Der Header nutzt `sec:` ab Step 9 nicht mehr.

- [ ] **Step 3: Test-Helper anlegen**

`src/test/kotlin/org/unividuell/mobility/manager/user/TestUsers.kt`:

```kotlin
package org.unividuell.mobility.manager.user

import jakarta.servlet.http.Cookie
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.auth.AuthPrincipal

/** The session principal the auth lib hands out when [user] signs in. */
fun AppUser.principal() = AuthPrincipal(id = accountId, provider = provider, login = login, roles = emptySet())

/** A browser signed in as [user] the way the auth lib signs one in, holding a CSRF token ([withCsrfToken]). */
fun signedInAs(user: AppUser): RequestPostProcessor {
    val login = oauth2Login().oauth2User(user.principal())
    return RequestPostProcessor { request -> withCsrfToken().postProcessRequest(login.postProcessRequest(request)) }
}

/**
 * The CSRF token the way a browser holds it: the auth lib's `XSRF-TOKEN` cookie, echoed in the
 * header htmx sends. Not spring-security-test's csrf(): that swaps the shared CsrfFilter's cookie
 * repository for a session-backed one for good, and every later request in the same context would
 * create a session (see AnonymousSessionIntegrationTest).
 */
fun withCsrfToken(): RequestPostProcessor = RequestPostProcessor { request ->
    request.setCookies(*request.cookies.orEmpty(), Cookie("XSRF-TOKEN", TEST_CSRF_TOKEN))
    request.addHeader("X-XSRF-TOKEN", TEST_CSRF_TOKEN)
    request
}

/** The token [withCsrfToken] holds; pages rendered for such a request carry it. */
const val TEST_CSRF_TOKEN = "test-csrf-token"
```

- [ ] **Step 4: Failing Tests für die Lib-Verdrahtung**

`src/test/kotlin/org/unividuell/mobility/manager/SecurityIntegrationTest.kt`:

```kotlin
package org.unividuell.mobility.manager

import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.unividuell.mobility.manager.user.AppUser
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.user.principal
import org.unividuell.mobility.manager.user.signedInAs
import org.unividuell.mobility.manager.user.withCsrfToken

/**
 * Where this server-rendered app turns the auth lib's single-page-app defaults back into
 * page navigation, and the CSRF protection the lib switches on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val users: AppUserService,
    private val db: DatabaseCleaner,
) {

    private lateinit var user: AppUser

    @BeforeEach
    fun setUp() {
        db.clean()
        user = users.upsert(provider = "github", subject = "4711", login = "octocat", name = "The Octocat")
    }

    @Test
    fun `an anonymous page request is sent to the login page, not answered with 401`() {
        mockMvc.get("/vehicles").andExpect {
            status { isFound() }
            redirectedUrl("/login")
        }
    }

    @Test
    fun `an htmx request after the session has gone is sent to the login page`() {
        mockMvc.post("/fuel/value") {
            header("HX-Request", "true")
            param("value", "45")
            with(withCsrfToken())
        }.andExpect {
            status { isFound() }
            redirectedUrl("/login")
        }
    }

    @Test
    fun `with the test login off, the sign-in starts at GitHub`() {
        mockMvc.get("/login/start").andExpect {
            status { isFound() }
            redirectedUrl("/oauth2/authorization/github")
        }
    }

    @Test
    fun `logout lands on the login page`() {
        mockMvc.post("/logout") { with(signedInAs(user)) }.andExpect {
            status { isFound() }
            redirectedUrl("/login")
        }
    }

    @Test
    fun `a state-changing request without a CSRF token is refused`() {
        mockMvc.post("/fuel/reset") { with(oauth2Login().oauth2User(user.principal())) }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `a token in the header that differs from the cookie is refused`() {
        mockMvc.post("/fuel/reset") {
            with(oauth2Login().oauth2User(user.principal()))
            cookie(Cookie("XSRF-TOKEN", "the-cookie-token"))
            header("X-XSRF-TOKEN", "another-token")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `a state-changing request with the token in the htmx header goes through`() {
        mockMvc.post("/fuel/reset") { with(signedInAs(user)) }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `the header shows the signed-in user's name`() {
        val body = mockMvc.get("/vehicles") { with(signedInAs(user)) }.andReturn().response.contentAsString

        body shouldContain "The Octocat"
    }
}
```

`src/test/kotlin/org/unividuell/mobility/manager/user/TestLoginIntegrationTest.kt`:

```kotlin
package org.unividuell.mobility.manager.user

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.unividuell.mobility.manager.DatabaseCleaner

/**
 * The local door: without a profile, the auth lib's test-user picker signs a test user in
 * through [AppUserService], exactly like GitHub does in production. No @ActiveProfiles("test")
 * on purpose — any active profile would demand a test-login key — so the in-memory datasource
 * is set here instead of in application-test.yaml.
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared&date_class=text&date_string_format=yyyy-MM-dd&foreign_keys=true",
        "spring.datasource.hikari.maximum-pool-size=5",
    ],
)
@AutoConfigureMockMvc
class TestLoginIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: AppUserRepository,
    private val db: DatabaseCleaner,
) {

    @BeforeEach
    fun cleanDb() {
        db.clean()
    }

    @Test
    fun `the sign-in starts at the test-user picker`() {
        val body = mockMvc.get("/login/start").andReturn().response.contentAsString

        body shouldContain """name="login" value="Fry""""
    }

    @Test
    fun `picking a test user provisions an account and signs it in`() {
        val signIn = mockMvc.post("/login/test/as") {
            param("login", "Fry")
            with(withCsrfToken())
        }.andReturn().response

        signIn.status shouldBe 302
        signIn.redirectedUrl shouldBe "/"
        repository.findByProviderAndSubject("test", "Fry")?.displayName shouldBe "Fry"

        val session = Cookie("SESSION", signIn.getCookie("SESSION")!!.value)
        mockMvc.get("/vehicles") { cookie(session) }.andReturn().response.status shouldBe 200
    }
}
```

In `MigrationWithDataTest` vor `private fun flyway(` einfügen:

```kotlin
    @Test
    fun `V8 signs everyone out once`(@TempDir dir: Path) {
        val dataSource = SingleConnectionDataSource("jdbc:sqlite:${dir.resolve("mm.db")}?foreign_keys=true", true)
        try {
            val jdbc = JdbcTemplate(dataSource)
            flyway(dataSource, target = "7").migrate()
            jdbc.update("INSERT INTO SPRING_SESSION VALUES ('primary', 'session', 0, 0, 1800, 0, 'octocat')")
            jdbc.update("INSERT INTO SPRING_SESSION_ATTRIBUTES VALUES ('primary', 'SPRING_SECURITY_CONTEXT', x'00')")

            flyway(dataSource, target = "8").migrate()

            jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Int::class.java) shouldBe 0
            jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES", Int::class.java) shouldBe 0
        } finally {
            dataSource.destroy()
        }
    }

```

In `AppUserServiceIntegrationTest`: Import `org.unividuell.auth.ExternalIdentity` ergänzen und als letzten Test einfügen:

```kotlin
    @Test
    fun `provision hands the auth lib the account id its principal will carry`() {
        val identity = ExternalIdentity(provider = "test", subject = "Fry", login = "Fry", name = null, email = null)

        val accountId = service.provision(identity, roles = emptySet())

        repository.findByAccountId(accountId)?.login shouldBe "Fry"
    }
```

- [ ] **Step 5: Tests laufen lassen, sie müssen scheitern**

Run: `./mvnw -B --no-transfer-progress test -Dtest='SecurityIntegrationTest,TestLoginIntegrationTest,MigrationWithDataTest,AppUserServiceIntegrationTest'`
Expected: FAIL schon beim Kompilieren der Tests: `Unresolved reference 'provision'`. Wer den provision-Test vorübergehend auskommentiert, sieht stattdessen den Kontextstart scheitern, weil die Lib einen `AccountProvisioner` verlangt.

- [ ] **Step 6: V8**

`src/main/resources/db/migration/V8__drop_all_sessions.sql`:

```sql
-- Every stored session holds the old DefaultOAuth2User principal, which the auth lib's AuthPrincipal
-- replaces. Left in place, each would answer 500 instead of the login page; this way everyone signs
-- in once more. SPRING_SESSION_ATTRIBUTES follows through ON DELETE CASCADE.
DELETE FROM SPRING_SESSION;
```

- [ ] **Step 7: AccountProvisioner und CurrentUser**

`AppUserService.kt`: Imports `org.unividuell.auth.AccountProvisioner` und `org.unividuell.auth.ExternalIdentity` ergänzen und den Klassenkopf ersetzen:

```kotlin
/** The auth lib's account hook: both sign-in doors, GitHub and the local test login, end here. */
@Service
class AppUserService(
    private val repository: AppUserRepository,
) : AccountProvisioner {

    /** No roles are configured, so [roles] is always empty. */
    override fun provision(identity: ExternalIdentity, roles: Set<String>): UUID =
        upsert(provider = identity.provider, subject = identity.subject, login = identity.login, name = identity.name).accountId

```

(`upsert` bleibt unverändert darunter.)

`src/main/kotlin/org/unividuell/mobility/manager/user/CurrentUser.kt` (ganze Datei):

```kotlin
package org.unividuell.mobility.manager.user

import org.springframework.stereotype.Component
import org.unividuell.auth.AuthPrincipal

/**
 * Resolves the persisted [AppUser] behind the signed-in principal. The user row
 * always exists by this point — the auth lib provisions it through
 * [AppUserService] at sign-in.
 */
@Component
class CurrentUser(
    private val repository: AppUserRepository,
) {

    fun require(principal: AuthPrincipal): AppUser =
        repository.findByAccountId(principal.id)
            ?: error("no persisted user for account ${principal.id}")
}
```

```bash
git rm src/main/kotlin/org/unividuell/mobility/manager/user/GithubOAuth2UserService.kt
```

- [ ] **Step 8: Controller auf `AuthPrincipal`**

```bash
for f in src/main/kotlin/org/unividuell/mobility/manager/{fuel/FuelController,parts/PartController,vehicle/VehicleController}.kt; do
  perl -0pi -e 's/import org\.springframework\.security\.oauth2\.core\.user\.OAuth2User\n//; s/(import org\.unividuell\.mobility)/import org.unividuell.auth.AuthPrincipal\n$1/; s/principal: OAuth2User/principal: AuthPrincipal/g' "$f"
done
grep -rn "OAuth2User" src/main/kotlin
```

Expected: Das `grep` gibt nichts aus.

- [ ] **Step 9: GlobalModelAdvice und Name im Header**

`src/main/kotlin/org/unividuell/mobility/manager/GlobalModelAdvice.kt` (ganze Datei):

```kotlin
package org.unividuell.mobility.manager

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.unividuell.auth.AuthPrincipal
import org.unividuell.mobility.manager.parts.PartService
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.VehicleContext

/**
 * Exposes the session-selected vehicle to every view as `selectedVehicle` (so the
 * shared header shows the active context on every page) along with the `accent`
 * color derived from it — letting the whole UI tint to the vehicle in context —
 * and the signed-in user's name as `currentUserName`.
 */
@ControllerAdvice
class GlobalModelAdvice(
    private val currentUser: CurrentUser,
    private val vehicleContext: VehicleContext,
    private val partService: PartService,
) {

    @ModelAttribute
    fun populate(
        @AuthenticationPrincipal principal: AuthPrincipal?,
        request: HttpServletRequest,
        model: Model,
    ) {
        val user = principal?.let { currentUser.require(it) }
        // An HttpSession parameter would force session creation on every request —
        // this advice also runs for anonymous ones (e.g. the /actuator/health probe),
        // which must stay session-free. Authenticated requests always have a session.
        val vehicle = user?.let { vehicleContext.current(request.getSession(true), it.id!!) }
        model.addAttribute("currentUserName", user?.displayName)
        model.addAttribute("selectedVehicle", vehicle)
        model.addAttribute("accent", vehicle?.let { Accent.of(it.color) })
        // parts with overdue maintenance mark the vehicle in the shared header
        model.addAttribute("overdueCount", vehicle?.let { partService.overdueCount(it) } ?: 0)
    }
}
```

`src/main/resources/templates/fragments/header.html`: Den Kopf

```html
<html lang="de"
      xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
```

ersetzen durch `<html lang="de" xmlns:th="http://www.thymeleaf.org">`, und

```html
        <span class="text-zinc-500" sec:authentication="principal.attributes['displayName']">Fahrer</span>
```

ersetzen durch

```html
        <span class="text-zinc-500" th:text="${currentUserName}">Fahrer</span>
```

`src/main/resources/templates/vehicles/index.html`: Die Zeile `      xmlns:sec="http://www.thymeleaf.org/extras/spring-security"` löschen, sie ist ungenutzt.

- [ ] **Step 10: SecurityConfig**

`src/main/kotlin/org/unividuell/mobility/manager/SecurityConfig.kt` (ganze Datei):

```kotlin
package org.unividuell.mobility.manager

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler
import org.springframework.security.web.savedrequest.CookieRequestCache

/**
 * Sign-in, CSRF and logout come from the auth lib (org.unividuell:auth-spring-boot-starter),
 * whose rules run before these. The lib is built for a single-page app; this chain turns the
 * three places where a server-rendered app differs back to page navigation.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize("/login", permitAll)
                // health probe for the container/orchestrator; shows only
                // {"status":"UP"} (details default to "never"), no secrets.
                authorize("/actuator/health", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                // the lib answers 401 for an SPA to handle; a browser needs the login page
                authenticationEntryPoint = LoginUrlAuthenticationEntryPoint("/login")
            }
            logout {
                // a handler, not logoutSuccessUrl: the URL would lose against the lib's 204 handler
                logoutSuccessHandler = SimpleUrlLogoutSuccessHandler().apply { setDefaultTargetUrl("/login") }
            }
            requestCache {
                // keep the deep-link-after-login redirect in a cookie instead of the
                // session — anonymous hits on protected routes (bots, crawlers) must
                // not persist a session to SQLite just for the 302 to /login.
                requestCache = CookieRequestCache()
            }
        }
        return http.build()
    }
}
```

- [ ] **Step 11: Konfiguration**

`src/main/resources/application.yaml`: Den ganzen Block unter `spring:` löschen:

```yaml
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: Ov23lieWH11YI3qPP5Og
            client-secret: ${GITHUB_CLIENT_SECRET}
            scope: read:user
```

`src/main/resources/application-production.yaml`: Die Registration `github` ersetzen durch:

```yaml
          github:
            # The organisation's GitHub App, shared by every unividuell app (up to ten
            # callback URLs, https://mobility.unividuell.org/login/oauth2/code/github
            # among them). Public client ID, safe to commit.
            client-id: Iv23liJTgm6EeJ6XshRh
            # required: no default, so the app fails fast if the secret is missing.
            client-secret: ${MOBILITY_MANAGER_GITHUB_CLIENT_SECRET}
            scope: read:user
```

`src/test/resources/application-test.yaml`: Den Kopfkommentar ersetzen durch

```yaml
# Test-only overrides; everything else (datasource driver, flyway, thymeleaf) is
# inherited from the base src/main/resources/application.yaml. Activated via
# @ActiveProfiles("test").
```

und am Ende anhängen (`security:` eingerückt unter dem bestehenden `spring:`):

```yaml
  security:
    oauth2:
      client:
        registration:
          # production's door, with dummy credentials: the auth lib refuses to start
          # without any way in, and the test login below is off
          github:
            client-id: test-client-id
            client-secret: test-client-secret
            scope: read:user

unividuell:
  auth:
    test-login:
      # an active profile would demand a key; tests sign in through MockMvc instead
      enabled: false
```

- [ ] **Step 12: Controller-Tests auf `signedInAs`**

```bash
perl -0pi -e 's/    private val githubId = 4711L\n/    private lateinit var user: AppUser\n/; s/        userId = users\.upsert\(provider = "github", subject = githubId\.toString\(\), login = "octocat", name = "The Octocat"\)\.id!!\n/        user = users.upsert(provider = "github", subject = "4711", login = "octocat", name = "The Octocat")\n        userId = user.id!!\n/; s/oauth2Login\(\)\.attributes \{ it\["id"\] = githubId \}/signedInAs(user)/; s/import org\.springframework\.security\.test\.web\.servlet\.request\.SecurityMockMvcRequestPostProcessors\.oauth2Login\n//; s/import org\.unividuell\.mobility\.manager\.user\.AppUserService\n/import org.unividuell.mobility.manager.user.AppUser\nimport org.unividuell.mobility.manager.user.AppUserService\nimport org.unividuell.mobility.manager.user.signedInAs\n/' \
  src/test/kotlin/org/unividuell/mobility/manager/{fuel/FuelControllerIntegrationTest,vehicle/VehicleControllerIntegrationTest,parts/PartControllerIntegrationTest}.kt
grep -rn "githubId\|oauth2Login()\.attributes" src/test/kotlin
```

Expected: Das `grep` gibt nichts aus. Jede Klasse hat jetzt `private fun login(): RequestPostProcessor = signedInAs(user)`. Alle bestehenden `with(login())` senden damit Principal und CSRF-Token.

- [ ] **Step 13: Alle Tests**

Run: `./mvnw -B --no-transfer-progress test`
Expected: `Tests run: 173, Failures: 0, Errors: 0`. Läuft `AnonymousSessionIntegrationTest` nur im Gesamtlauf rot (`SESSION`-Cookie bei anonymen Requests), hat irgendwo ein `csrf()` von spring-security-test überlebt, siehe Global Constraints.

- [ ] **Step 14: Commit**

```bash
git add maven-repo pom.xml src
git commit -m "feat(auth): sign in through auth-spring-boot-starter" -m "Sign-in, CSRF and logout now come from the auth lib, shipped as a
snapshot in maven-repo/. Locally the lib's test-user picker is the
door; production signs in through the organisation's GitHub App.
AppUserService is the lib's AccountProvisioner and the principal is
its AuthPrincipal, resolved by account id. The app's chain only turns
the lib's single-page-app defaults back into page navigation: a
redirect to /login instead of 401, a redirect after logout instead of
204, and the cookie request cache. V8 empties the session store, as
every stored session holds the old principal class.

Tests send the CSRF token as cookie and header the way a browser does:
spring-security-test's csrf() would leave a session-backed token
repository in the shared context." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: CSRF-Token in die Seiten, Login-Button auf /login/start

**Files:**
- Modify: `src/main/resources/templates/login.html:18`
- Modify: `src/main/resources/templates/fragments/header.html` (Logout-Formular)
- Modify: `src/main/resources/templates/index.html:16`, `src/main/resources/templates/vehicles/index.html` (`<body>`)
- Modify: `src/test/kotlin/org/unividuell/mobility/manager/SecurityIntegrationTest.kt`

**Interfaces:**
- Consumes: `signedInAs(user)`, `TEST_CSRF_TOKEN` aus Task 3; Request-Attribut `_csrf` (setzt Spring Security, das CSRF-Repository kommt von der Lib).
- Produces: —

- [ ] **Step 1: Failing Tests**

In `SecurityIntegrationTest`: Import `org.unividuell.mobility.manager.user.TEST_CSRF_TOKEN` ergänzen und am Ende der Klasse einfügen:

```kotlin
    @Test
    fun `the login page starts the sign-in through the auth lib`() {
        val body = mockMvc.get("/login").andReturn().response.contentAsString

        body shouldContain """href="/login/start""""
    }

    @Test
    fun `the htmx pages hand htmx the CSRF token as a request header`() {
        for (page in listOf("/", "/vehicles")) {
            val body = mockMvc.get(page) { with(signedInAs(user)) }.andReturn().response.contentAsString

            body shouldContain """hx-headers="{&quot;X-XSRF-TOKEN&quot;: &quot;$TEST_CSRF_TOKEN&quot;}""""
        }
    }

    @Test
    fun `the logout form carries the CSRF token`() {
        val body = mockMvc.get("/vehicles") { with(signedInAs(user)) }.andReturn().response.contentAsString

        body shouldContain """<input type="hidden" name="_csrf" value="$TEST_CSRF_TOKEN"/>"""
    }
```

- [ ] **Step 2: Tests laufen lassen, sie müssen scheitern**

Run: `./mvnw -B --no-transfer-progress test -Dtest=SecurityIntegrationTest`
Expected: 3 FAIL (`href="/login/start"`, `hx-headers`, `name="_csrf"` nicht gefunden), 8 PASS

- [ ] **Step 3: Templates**

`login.html`: `<a href="/oauth2/authorization/github"` → `<a href="/login/start"`. Der Text „Mit GitHub anmelden“ bleibt.

`fragments/header.html`: `<form action="/logout" method="post">` → `<form th:action="@{/logout}" method="post">`. Mit `th:action` hängt Spring Security das versteckte `_csrf`-Feld an.

`index.html` und `vehicles/index.html`: Jeweils die Zeile

```html
<body class="h-full bg-zinc-950 text-zinc-100 antialiased selection:bg-accent/30">
```

ersetzen durch

```html
<!-- htmx sends the CSRF token with every request it makes from this page -->
<body class="h-full bg-zinc-950 text-zinc-100 antialiased selection:bg-accent/30"
      th:hx-headers="|{&quot;X-XSRF-TOKEN&quot;: &quot;${_csrf.token}&quot;}|">
```

- [ ] **Step 4: Alle Tests**

Run: `./mvnw -B --no-transfer-progress test`
Expected: `Tests run: 176, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates src/test/kotlin/org/unividuell/mobility/manager/SecurityIntegrationTest.kt
git commit -m "feat(auth): hand the CSRF token to htmx and the logout form" -m "The auth lib switches CSRF protection on. htmx reads the token from
hx-headers on the body of the two pages that use it; the logout form
gets its hidden field through th:action. The login button starts the
sign-in at /login/start: GitHub in production, the picker locally." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Doku, Deployment-Dateien, /verify-Skill, End-to-End-Check

**Files:**
- Modify: `README.md` (Intro Z. 4, Bootstrap Z. 50, Hinweis Z. 54-55, neuer Abschnitt „Auth lib“)
- Modify: `deploy/compose.prod.yaml` (CLIENT_ID-Zeile weg)
- Modify: `deploy/.env.example`
- Modify: `.claude/skills/verify/SKILL.md` (ganze Datei)

**Interfaces:**
- Consumes: Alles aus Task 1–4.
- Produces: —

- [ ] **Step 1: README**

Zeile 4 `Login is via GitHub OAuth2; data is stored in a local SQLite file.` ersetzen durch:

```markdown
Sign-in comes from the auth lib (see [Auth lib](#auth-lib)): the organisation's GitHub App in
production, a test-user picker locally. Data is stored in a local SQLite file.
```

Im Bootstrap-Block `# edit .env: MOBILITY_MANAGER_GITHUB_CLIENT_SECRET` ersetzen durch `# edit .env: MOBILITY_MANAGER_GITHUB_CLIENT_SECRET (the GitHub App's client secret)`.

Den Hinweis

```markdown
> The shared edge-caddy stack must be up (it owns 80/443 + TLS). The GitHub OAuth app's
> callback URL must be `https://mobility.unividuell.org/login/oauth2/code/github`.
```

ersetzen durch

```markdown
> The shared edge-caddy stack must be up (it owns 80/443 + TLS). The organisation's GitHub App
> (client ID in `application-production.yaml`) must list the callback URL
> `https://mobility.unividuell.org/login/oauth2/code/github`.
```

Am Ende anhängen:

````markdown
## Auth lib

Sign-in, CSRF and logout come from `org.unividuell:auth-spring-boot-starter`
(`/opt/unividuell/projects/auth-spring-boot-starter`). It is not on Maven Central: the build
reads it from `maven-repo/`, which is committed. The app provides the lib's one hook,
`AccountProvisioner` (`AppUserService`).

| | locally (no profile) | tests (profile `test`) | production |
|---|---|---|---|
| `/login/start` | test-user picker | GitHub | GitHub |

To take a new build of the snapshot, replace it in place and commit:

```bash
rm -rf maven-repo/org/unividuell/auth-spring-boot-starter
(cd /opt/unividuell/projects/auth-spring-boot-starter && ./mvnw -B deploy -DskipTests -Dmaven.install.skip=true -DaltDeploymentRepository=app::file://$OLDPWD/maven-repo)
```

The repository's `updatePolicy=always` makes a redeployed snapshot win over the copy in `~/.m2`
and in CI's Maven cache.
````

- [ ] **Step 2: Deployment-Dateien**

`deploy/compose.prod.yaml`: Die Zeile `      MOBILITY_MANAGER_GITHUB_CLIENT_ID: ${MOBILITY_MANAGER_GITHUB_CLIENT_ID:-}` löschen.

`deploy/.env.example` (ganze Datei):

```bash
# mobility-manager production secrets (all MOBILITY_MANAGER_-prefixed)
# the organisation's GitHub App client secret (one generated for mobility-manager)
MOBILITY_MANAGER_GITHUB_CLIENT_SECRET=
```

- [ ] **Step 3: /verify-Skill**

`.claude/skills/verify/SKILL.md` (ganze Datei):

````markdown
---
name: verify
description: Build, launch and drive mobility-manager locally to verify changes end-to-end
---

# Verifying mobility-manager locally

## Launch

```bash
mkdir -p data   # SQLite lives at ./data/mobility-manager.db relative to cwd; boot fails without the dir
SERVER_PORT=18080 ./mvnw spring-boot:run
```

- No profile and no secrets: sign-in is the auth lib's test-user picker, there is no GitHub client locally.
- App is up within ~10s; probe `http://127.0.0.1:18080/actuator/health` until 200.

## Sign in

Browser: `/login` → „Mit GitHub anmelden" → `/login/start` shows the picker → pick a user (Fry, Leela, …).
The first pick provisions a `users` row with provider `test`.

curl, with a cookie jar (the auth lib keeps the CSRF token in the `XSRF-TOKEN` cookie):

```bash
B=http://127.0.0.1:18080; J=$(mktemp)
curl -s -c $J -b $J -o /dev/null $B/login/start
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -c $J -b $J -o /dev/null -X POST $B/login/test/as --data-urlencode login=Fry --data-urlencode "_csrf=$T"
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)   # read it again after signing in
curl -s -c $J -b $J $B/vehicles                                                                  # a page
curl -s -c $J -b $J -X POST -H "HX-Request: true" -H "X-XSRF-TOKEN: $T" $B/fuel/reset           # htmx: header
curl -s -c $J -b $J -X POST $B/vehicles --data-urlencode name=Kombi --data-urlencode color=#06b6d4 --data-urlencode "_csrf=$T"   # form: field
```

A mutating request without the token answers 403.

## Gotchas

- Anonymous requests must not create sessions (`Set-Cookie: SESSION=…`) — the 30s healthcheck once filled prod with ~88k empty 30-day sessions. Every response carries `Set-Cookie: XSRF-TOKEN=…`; that is the CSRF cookie, not a session.
- Inspect session persistence directly: `sqlite3 data/mobility-manager.db "SELECT COUNT(*) FROM SPRING_SESSION;"`
- The exact prod healthcheck: `bash -c 'exec 3<>/dev/tcp/127.0.0.1/18080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q UP <&3'`
- The GitHub door only exists under the `production` profile; it cannot be driven locally.
````

- [ ] **Step 4: End-to-End auf einer Kopie der prod-nahen DB**

Die DB im Hauptcheckout (`/opt/unividuell/projects/mobility-manager/data/mobility-manager.db`, Stand V6) ist ein Prod-Abzug. **Nur eine Kopie anfassen.**

```bash
E2E=$(mktemp -d); mkdir -p $E2E/data && cp /opt/unividuell/projects/mobility-manager/data/mobility-manager.db $E2E/data/
sqlite3 $E2E/data/mobility-manager.db "SELECT COUNT(*) FROM users; SELECT COUNT(*) FROM vehicle_managers; SELECT COUNT(*) FROM tags;"
```

Die App mit dieser DB starten. Den Start mit `run_in_background` bzw. über das Harness ausführen, nicht mit `&`:

```bash
SERVER_PORT=18080 SPRING_DATASOURCE_URL="jdbc:sqlite:$E2E/data/mobility-manager.db?date_class=text&date_string_format=yyyy-MM-dd&foreign_keys=true" ./mvnw spring-boot:run
```

Dann prüfen:

```bash
sqlite3 $E2E/data/mobility-manager.db "SELECT id, provider, subject, login FROM users; SELECT COUNT(*) FROM vehicle_managers; SELECT COUNT(*) FROM tags; PRAGMA foreign_key_check; SELECT COUNT(*) FROM SPRING_SESSION;"
```

Expected:
- Gleiche User-Anzahl wie vorher, alle mit `provider = github` und `subject = <GitHub-id>`.
- Gleiche Anzahl in `vehicle_managers` und `tags` wie vorher.
- `foreign_key_check` gibt nichts aus, `SPRING_SESSION` ist 0.

Danach das curl-Rezept aus dem Skill (Step 3) durchfahren. Erwartet: Login als Fry → 302 `/`; htmx-POST → 200; Formular-POST → 302 `/vehicles`; POST ohne Token → 403; `POST /logout` mit `_csrf` → 302 `/login`; `/actuator/health` ohne `SESSION`-Cookie. App stoppen, `$E2E` löschen.

- [ ] **Step 5: Gesamte Suite ein letztes Mal**

Run: `./mvnw -B --no-transfer-progress test`
Expected: `Tests run: 176, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add README.md deploy .claude/skills/verify/SKILL.md
git commit -m "docs: GitHub App, the auth lib snapshot and the picker sign-in" -m "Production signs in through the organisation's GitHub App, so the
client ID override leaves compose and .env. The README explains how
to take a new build of the lib; /verify signs in through the picker
and carries the CSRF token in its curl recipe." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Nach dem Merge (manuell, Clemens, siehe Spec Abschnitt 7)

1. In der GitHub App die Callback-URL `https://mobility.unividuell.org/login/oauth2/code/github` eintragen und ein eigenes Client-Secret erzeugen.
2. Prod-DB sichern.
3. `.env`: Secret tauschen, `MOBILITY_MANAGER_GITHUB_CLIENT_ID` entfernen.
4. `update.sh`, einmal neu anmelden.
5. Die beiden OAuth Apps löschen.
