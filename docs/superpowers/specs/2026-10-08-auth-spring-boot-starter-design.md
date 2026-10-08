# Anmeldung über auth-spring-boot-starter — Design

Datum: 2026-10-08
Status: entworfen, vom User abgenommen (Brainstorming-Session)

## Ziel

mobility-manager meldet über `org.unividuell:auth-spring-boot-starter:0.2.0-SNAPSHOT` an, die
Lib, die im Rahmen von countdown extrahiert wurde (Checkout:
`/opt/unividuell/projects/auth-spring-boot-starter`, HEAD `6557d29`). Der eigene GitHub-Login
(`GithubOAuth2UserService`, das OAuth-Gerüst in `SecurityConfig`) fällt weg. Lokal meldet man
sich über den Test-User-Picker der Lib an, in production über die GitHub App der Organisation.

Vorbild ist countdown, Branch `feature/centralized-social-login-auth-dfb589`
(`40386fa`, `6d09d3d`, `bf2c2ba`, `4f815c0`).

## Entscheidungen aus dem Brainstorming

- **Test-Login lokal:** ja. Ohne Profil führt `/login/start` zum Picker der Lib, GitHub braucht
  es lokal nicht. `/verify` kann damit eingeloggte Flows fahren.
- **Account-ID (Ansatz A):** `users.id` bleibt INTEGER (alle FKs und jedes `userId: Long`
  unberührt), eine neue Spalte `account_id` trägt die UUID des `AuthPrincipal`. Verworfen:
  `users.id` selbst zur UUID machen (Rebuild von `vehicle_managers` und `tags`, ohne
  funktionalen Gewinn).
- **GitHub App statt OAuth App**, wie countdown: die bestehende App der Organisation
  (Client-ID `Iv23liJTgm6EeJ6XshRh`) bekommt mobility-managers Callback-URL. Die beiden OAuth
  Apps (prod + localhost) werden am Ende gelöscht.
- **Kaum Lib-Features:** keine Rollen (`unividuell.auth.roles` bleibt leer), keine
  `csrf-cookie.excluded-paths`, kein Key (kein Staging). Die Picker-User bleiben die Defaults
  der Lib (zwölf Futurama-Figuren).
- **Server-gerendert bleibt server-gerendert:** Die Lib ist für eine SPA gebaut (401, Logout
  204, kein Request-Cache). mobility-manager überschreibt genau diese drei Punkte in seiner Chain.
- **CSRF an:** Der Picker braucht das Token (die Lib liest es ohne Null-Check), und die Lib
  schaltet es ohnehin ein. htmx schickt es als Header mit.
- **Deep-Link nach Login bleibt** (`CookieRequestCache`). Der Picker leitet nach der Wahl auf `/`.

## 1 · Build & Abhängigkeiten

Zwei Commits, damit ein Bruch durch das Upgrade nicht in der Auth-Migration untergeht.

**Upgrade (Commit 1, Testsuite grün vor jeder Auth-Änderung):**

| | vorher | nachher |
|---|---|---|
| `spring-boot-starter-parent` | 4.0.6 | 4.1.1 |
| `kotlin.version` | 2.3.0 | 2.4.10 |
| `spring-modulith.version` | 2.0.6 | 2.1.1 |

Das ist die Paarung von Lib und countdown. `BP_JVM_VERSION` bleibt 25. Am Code ändert sich nur,
was das Upgrade erzwingt.

**Lib einbinden (Commit 2):**

- Snapshot aus dem sauberen Lib-Checkout deployen, wie das Lib-README es beschreibt:
  `./mvnw -B deploy -DskipTests -Dmaven.install.skip=true -DaltDeploymentRepository=app::file://<repo>/maven-repo`.
  `maven-repo/` im Projektroot wird committet.
- `pom.xml`:
  - Repository `unividuell-local` → `file://${project.basedir}/maven-repo`, mit
    `<snapshots><updatePolicy>always</updatePolicy></snapshots>`. CI nutzt
    `setup-java … cache: maven`; ohne `always` verliert ein in place neu deployter Snapshot
    gegen den Cache.
  - Property `unividuell-auth.version` = `0.2.0-SNAPSHOT`, Dependency
    `org.unividuell:auth-spring-boot-starter`.
  - `spring-boot-starter-oauth2-client` fliegt raus (die Lib bringt
    `spring-boot-starter-security-oauth2-client`). `spring-boot-starter-security` bleibt
    explizit, die App baut eine eigene Chain.
  - `thymeleaf-extras-springsecurity6` fliegt raus, sobald der Header es nicht mehr nutzt
    (Abschnitt 3).
- CI-Workflow und Buildpack bleiben unverändert: kein Token, keine Registry-Konfiguration,
  `maven-repo/` landet nicht im Image.

## 2 · Security-Verdrahtung

Die Lib registriert einen `Customizer<HttpSecurity>`, den Spring Security vor der Konfiguration
der App anwendet: `/login/**`, `/oauth2/**` und Error-Dispatches `permitAll`, `oauth2Login`
(Login-Seite `/login/start`, eigener User-Service, Failure-Handler), 401-Entry-Point,
`NullRequestCache`, CSRF über `CookieCsrfTokenRepository` (Cookie `XSRF-TOKEN`, Header
`X-XSRF-TOKEN`, Plain-Handler), `POST /logout` mit 204.

**`SecurityConfig` der App danach:**

```kotlin
http {
    authorizeHttpRequests {
        authorize("/login", permitAll)
        authorize("/actuator/health", permitAll)
        authorize(anyRequest, authenticated)
    }
    // server-rendered: anonymous browsers land on the login page, not on a bare 401
    exceptionHandling { authenticationEntryPoint = LoginUrlAuthenticationEntryPoint("/login") }
    // an explicit handler: logoutSuccessUrl would lose against the lib's 204 handler
    logout { logoutSuccessHandler = SimpleUrlLogoutSuccessHandler().apply { setDefaultTargetUrl("/login") } }
    requestCache { requestCache = CookieRequestCache() }
}
```

- Es fallen weg: `oauth2Login { … }`, `csrf { disable() }`, `formLogin`/`httpBasic { disable() }`,
  `authorize("/error")` (die Lib gibt Error-Dispatches frei) und `GithubOAuth2UserService`.
- `AnonymousSessionIntegrationTest` bleibt gültig: anonym → 302, kein `SESSION`-Cookie.
  `CookieCsrfTokenRepository` setzt das `XSRF-TOKEN`-Cookie, legt aber keine Session an.

**CSRF in den Templates:**

- Formulare mit `th:action` bekommen das versteckte `_csrf`-Feld automatisch
  (Spring Securitys `RequestDataValueProcessor`).
- `fragments/header.html`: Das Logout-Formular wechselt von `action="/logout"` auf
  `th:action="@{/logout}"`.
- `index.html` und `vehicles/index.html` (die beiden Seiten mit htmx) setzen `hx-headers` mit
  `X-XSRF-TOKEN` = `${_csrf.token}` am `<body>`. htmx vererbt das Attribut an jeden Request der
  Seite, kein zusätzliches JS.

**Login-Seite:** `/login` (Tanken-Seite) bleibt die eigene Route der App. Ihr Button zeigt auf
`/login/start`: in production weiter zu GitHub, lokal zum Picker. Ein gescheiterter GitHub-Login
landet auf der Fehlerseite der Lib (`/login/start?error`).

## 3 · Accounts & Daten

### Migration V7 — `V7__key_users_by_provider_and_subject.sql`

SQLite kann eine `UNIQUE`-Spalte (`github_id`) weder droppen noch ihren `NOT NULL` lockern, also
wird `users` neu gebaut. Flyway führt das Skript in einer Transaktion aus; `PRAGMA
defer_foreign_keys = ON` verschiebt die FK-Prüfung von `vehicle_managers` und `tags` auf den
Commit. Das implizite `DELETE` von `DROP TABLE` zählt die verwaisten Kind-Zeilen hoch, das
Wieder-Einfügen derselben `id`s in die neue `users` zählt sie zurück.

```sql
PRAGMA defer_foreign_keys = ON;
CREATE TABLE users_old AS SELECT * FROM users;
DROP TABLE users;
CREATE TABLE users (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id   TEXT    NOT NULL UNIQUE,      -- UUID, AuthPrincipal.id
    provider     TEXT    NOT NULL,             -- 'github' | 'test'
    subject      TEXT    NOT NULL,             -- provider's stable id, as text
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

- Vorab in einer Scratch-DB geprüft (SQLite 3.43): Kind-Zeilen bleiben, ihre FK-Klauseln zeigen
  weiter auf `users`, `PRAGMA foreign_key_check` ist leer, `sqlite_sequence` übernimmt das
  Maximum der `id`s, und ein echter Waise scheitert weiterhin beim Commit.
- Liefe Flyway wider Erwarten ohne Transaktion, gälte das PRAGMA nicht über das Statement
  hinaus: `DROP TABLE users` bräche dann sofort mit einer FK-Verletzung ab. Laut, nicht still.
- `account_id` muss als kanonische UUID (`8-4-4-4-12`, kleingeschrieben) vorliegen, weil die App
  sie als `java.util.UUID` liest.
- GitHubs `id` aus `/user` ist für OAuth App und GitHub App dieselbe. Die migrierten Zeilen
  `github/<id>` passen also nach dem Wechsel auf die GitHub App.

### Migration V8 — `V8__drop_all_sessions.sql`

`DELETE FROM SPRING_SESSION;` (die Attribute folgen über `ON DELETE CASCADE`). Jede gespeicherte
Session hält einen `DefaultOAuth2User`; die Controller erwarten künftig einen `AuthPrincipal` und
würden mit 500 statt mit dem Login antworten. Jeder meldet sich einmal neu an.

### Code

- **`AppUser`:** `accountId: UUID`, `provider: String`, `subject: String` statt `githubId`.
  `login`, `displayName` bleiben.
- **`AppUserRepository`:** `findByProviderAndSubject(provider, subject)`,
  `findByAccountId(accountId)` statt `findByGithubId`.
- **`AppUserService` implementiert `AccountProvisioner`:**
  - `upsert(identity: ExternalIdentity): AppUser` sucht über `(provider, subject)`, legt sonst
    mit `UUID.randomUUID()` an, frischt bei jedem Sign-in `login` und `displayName` auf
    (`identity.name`, falls nicht leer, sonst `login`).
  - `provision(identity, roles) = upsert(identity).accountId`. `roles` sind immer leer, keine
    Rollen konfiguriert.
  - Weiterhin Suchen-dann-Speichern wie bisher. Ein gleichzeitiger Erst-Login desselben Users
    endet in der `UNIQUE`-Verletzung, also einem gescheiterten Sign-in, den ein erneuter Klick
    behebt. Bei einem Ein-Personen-Betrieb kein Grund für `ON CONFLICT`.
- **`CurrentUser.require(principal: AuthPrincipal)`:** `findByAccountId(principal.id)`.
- **Controller** (`FuelController`, `PartController`, `VehicleController`) und
  **`GlobalModelAdvice`:** `@AuthenticationPrincipal principal: AuthPrincipal` (in der Advice
  nullable) statt `OAuth2User`.
- **Name im Header:** `GlobalModelAdvice` legt `currentUserName` (aus `users.display_name`) ins
  Model; `header.html` nutzt `th:text="${currentUserName}"` statt
  `sec:authentication="principal.attributes['displayName']"`. Der `AuthPrincipal` trägt nur
  `provider` und `login`.
- **Gelöscht:** `GithubOAuth2UserService`.
- **UUID in SQLite:** Ob Spring Data JDBC und der xerial-Treiber `UUID` ↔ `TEXT` ohne Hilfe
  abbilden, sichert ein Repository-Test ab. Falls nicht, kommt ein Converter-Paar in
  `DatabaseConfig.jdbcCustomConversions` dazu, wie bei `LocalDate`.

## 4 · Konfiguration je Umgebung

| | lokal (kein Profil) | Tests (Profil `test`) | production |
|---|---|---|---|
| Test-Login | an, ohne Key | aus | aus (Lib: `@Profile("!production")`) |
| GitHub-Registration | keine | Dummy-Secret | GitHub App |
| `/login/start` | Picker | Redirect zu GitHub | Redirect zu GitHub |

- **`application.yaml`:** Der Block `spring.security.oauth2.client.registration.github` fliegt
  raus. `GITHUB_CLIENT_SECRET` braucht es lokal nicht mehr.
- **`application-production.yaml`:** vollständige Registration `github` mit
  `client-id: Iv23liJTgm6EeJ6XshRh` (öffentlich, darf ins Repo),
  `client-secret: ${MOBILITY_MANAGER_GITHUB_CLIENT_SECRET}` (ohne Default, Start scheitert ohne),
  `scope: read:user`. `forward-headers-strategy: framework` bleibt, sonst baut Spring die
  `redirect_uri` mit `http://`.
- **`application-test.yaml`:** `unividuell.auth.test-login.enabled: false` und eine Registration
  `github` mit Dummy-Werten. Ein aktives Profil ohne Key würde den Start verweigern, ganz ohne
  Tür ebenso.
- **Deployment:** `MOBILITY_MANAGER_GITHUB_CLIENT_ID` verschwindet aus `deploy/compose.prod.yaml`,
  `deploy/.env.example` und dem README. Das README nennt die GitHub App statt der OAuth App und
  die Callback-URL `https://mobility.unividuell.org/login/oauth2/code/github`.

## 5 · Tests

**Umbau bestehender Tests:**

- Ein Test-Helper `signedInAs(user: AppUser): RequestPostProcessor` ersetzt
  `oauth2Login().attributes { it["id"] = githubId }`. Er hängt einen `AuthPrincipal` aus
  `user.accountId`, `provider`, `login` an (`oauth2Login().oauth2User(…)`).
- `users.upsert(githubId, login, displayName)` → `users.upsert(ExternalIdentity(…))`.
- Jeder mutierende MockMvc-Request (POST/PUT/DELETE) bekommt `.with(csrf())`.

**Neu:**

- **Migration mit Daten** (ohne Spring-Kontext): Flyway bis V6 auf eine Temp-Datei-SQLite
  (`foreign_keys=true`), Users, Fahrzeuge, Manager und Tags einfügen, dann bis zum Ende
  migrieren. Erwartet: gleiche `id`s, `provider`/`subject` korrekt, `account_id` als UUID
  lesbar, Kind-Zeilen intakt, `foreign_key_check` leer, keine Sessions mehr.
- **`AppUserService`:** derselbe Account (gleiche `accountId`) über mehrere Sign-ins, Login und
  Name werden aufgefrischt, `github:x` und `test:x` sind zwei Accounts, `name = null` fällt auf
  `login` zurück.
- **Repository:** `accountId` übersteht den Roundtrip durch SQLite.
- **Security:** anonymer Seitenaufruf → 302 auf `/login`; `POST /logout` → 302 auf `/login`;
  POST ohne Token → 403; POST mit `X-XSRF-TOKEN`-Header → durch; die htmx-Seiten rendern
  `hx-headers` mit dem Token; `/login/start` leitet im Testprofil auf
  `/oauth2/authorization/github`.

**Manuell:** V7 und V8 auf einer Kopie von `data/mobility-manager.db` laufen lassen; lokal
starten und den Picker-Login samt Tank-, Fahrzeug- und Teile-Flow im Browser durchgehen.

## 6 · `/verify`-Skill

`.claude/skills/verify/SKILL.md` wird angepasst:

- Start ohne `GITHUB_CLIENT_SECRET`.
- Login über `http://127.0.0.1:18080/login/start` → Picker → Figur wählen.
- curl-Rezept: Cookie-Jar, `XSRF-TOKEN` aus dem Jar als `X-XSRF-TOKEN` (htmx) bzw. `_csrf`
  (Formulare) mitschicken.
- Der Satz „a real login can't be driven locally" entfällt.

## 7 · Rollout (manuell, Clemens)

1. In der GitHub App der Organisation die Callback-URL
   `https://mobility.unividuell.org/login/oauth2/code/github` eintragen und ein eigenes
   Client-Secret für mobility-manager erzeugen (eine App kann mehrere haben, so bleibt es
   getrennt widerrufbar).
2. Auf dem Server die Prod-DB sichern (`/opt/unividuell/mobility-manager/data/mobility-manager.db`,
   Container gestoppt oder per `sqlite3 .backup`).
3. In `.env` `MOBILITY_MANAGER_GITHUB_CLIENT_SECRET` auf das Secret der App setzen,
   `MOBILITY_MANAGER_GITHUB_CLIENT_ID` entfernen.
4. Mergen, Image abwarten, `update.sh`. V7 und V8 laufen beim Start. Einmal neu anmelden.
5. Erst nach dem erfolgreichen Login in production die beiden OAuth Apps (prod + localhost)
   in GitHub löschen.

## Nicht im Umfang

- Rollen, Staging-Umgebung mit Key, eigene Picker-User.
- Eine eigene Fehlerseite für gescheiterte GitHub-Logins (die der Lib reicht).
- Verbesserungen am htmx-Verhalten bei abgelaufener Session (bleibt wie heute: Redirect auf
  `/login`, das htmx in das Ziel-Element tauscht).
- Änderungen an der Lib selbst.
