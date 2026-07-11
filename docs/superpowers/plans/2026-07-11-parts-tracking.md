# Teile-Tracking mit Wartungspunkten — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Teile-Einbau pro Fahrzeug tracken (km-Stand, Datum, Preis, Tags, Details) mit abhakbaren km-Wartungspunkten, Fälligkeitsübersicht, Header-Badge bei Überfälligkeit und Ersetzen-Flow.

**Architecture:** Neues `parts`-Modul (Package `org.unividuell.mobility.manager.parts`) mit `Part` als Spring-Data-JDBC-Aggregat (Checkpoints + Tag-Referenzen als `@MappedCollection`), user-eigenem `Tag`-Aggregat, reinen Berechnungs-Objekten (`CurrentKmCalculator` im fuel-Modul, `DueCalculator` im parts-Modul) und server-gerenderten Thymeleaf-Seiten. Spec: `docs/superpowers/specs/2026-07-11-parts-tracking-design.md`.

**Tech Stack:** Kotlin + Spring Boot (MVC, Security OAuth2), Spring Data JDBC, SQLite + Flyway, Thymeleaf + Tailwind (Browser-CDN) + htmx 2.0.4, Tests: JUnit 5 + kotest-Matcher + MockMvc.

## Global Constraints

- Branch: `feat/parts-tracking` — dort committen.
- UI-Sprache Deutsch, dunkles Zinc-Design, `max-w-md`-Layout (Muster: bestehende Templates).
- Tests laufen mit `./mvnw test -Dtest='<Klasse>'`; Integrationstests: `@SpringBootTest` + `@ActiveProfiles("test")`.
- Geld als Integer-Cents (`price_cents`), Eingabe ganze Euro. Datumsspalten als ISO-TEXT (`yyyy-MM-dd`), Kotlin `LocalDate`.
- Ownership-Guard immer über `vehicleService.get(vehicleId, userId)` → 404 (nie leaken, dass etwas existiert).
- Ein Checkpoint ist überfällig, wenn `currentKm >= installedAtKm + offsetKm` (exakt erreicht = überfällig).
- Spring-Data-JDBC-Eigenheit: Beim Speichern eines Aggregats werden Kind-Zeilen gelöscht und neu eingefügt — Checkpoint-IDs sind daher NICHT stabil über Saves hinweg. Die UI postet immer IDs der frisch gerenderten Seite; ein veralteter Tab läuft auf 404. Bewusst akzeptiert (Single-User-App).
- created_at-Spalten werden von SQLite befüllt und nicht auf Entities gemappt (bestehendes Muster).

---

### Task 1: Migration V6 + Baseline am Vehicle

**Files:**
- Create: `src/main/resources/db/migration/V6__create_parts_and_tags.sql`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/vehicle/Vehicle.kt`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/vehicle/VehicleService.kt:36-41` (update-Signatur)
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/vehicle/VehicleController.kt:42-52,77-88` (create/update-Params)
- Modify: `src/main/resources/templates/vehicles/form.html`
- Test: `src/test/kotlin/org/unividuell/mobility/manager/vehicle/VehicleServiceIntegrationTest.kt`

**Interfaces:**
- Produces: `Vehicle.baselineKm: Double?`, `Vehicle.baselineOn: LocalDate?`; `VehicleService.update(id, userId, name, color, hasTripMeter, baselineKm: Double? = null, baselineOn: LocalDate? = null)`; Tabellen `parts`, `part_checkpoints`, `tags`, `part_tags` (Spalten siehe SQL).

- [ ] **Step 1: Failing Test schreiben** — in `VehicleServiceIntegrationTest` ergänzen:

```kotlin
@Test
fun `update stores the baseline reading, both fields or none`() {
    val created = service.create(userA, "Moped", "#06b6d4", hasTripMeter = true)

    service.update(
        created.id!!, userA, name = "Moped", color = "#06b6d4", hasTripMeter = true,
        baselineKm = 19123.0, baselineOn = LocalDate.of(2026, 7, 11),
    )
    service.get(created.id!!, userA).baselineKm shouldBe 19123.0
    service.get(created.id!!, userA).baselineOn shouldBe LocalDate.of(2026, 7, 11)

    // one half missing -> both cleared (an anchor needs value AND date)
    service.update(created.id!!, userA, "Moped", "#06b6d4", hasTripMeter = true, baselineKm = 20000.0, baselineOn = null)
    service.get(created.id!!, userA).baselineKm shouldBe null
    service.get(created.id!!, userA).baselineOn shouldBe null
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `./mvnw test -Dtest='VehicleServiceIntegrationTest'`
Expected: Kompilierfehler (`baselineKm` unbekannt).

- [ ] **Step 3: Migration V6 schreiben**

```sql
-- Baseline anchor for trip-meter vehicles: an odometer reading (value + date)
-- taken at ANY point in the vehicle's life. Current total km is then
-- baseline_km + sum of trip distances strictly AFTER baseline_on.
ALTER TABLE vehicles ADD COLUMN baseline_km REAL;
ALTER TABLE vehicles ADD COLUMN baseline_on TEXT;

-- A tracked part installed in a vehicle. Active while retired_on IS NULL.
CREATE TABLE parts (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id          INTEGER NOT NULL REFERENCES vehicles(id),
    name                TEXT    NOT NULL,
    details             TEXT,
    price_cents         INTEGER,
    installed_at_km     REAL    NOT NULL,
    installed_on        TEXT    NOT NULL,
    retired_at_km       REAL,
    retired_on          TEXT,
    -- successor part; auto-nulled when the successor row is deleted
    replaced_by_part_id INTEGER REFERENCES parts(id) ON DELETE SET NULL,
    created_at          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One-shot maintenance checkpoint, due at installed_at_km + offset_km.
CREATE TABLE part_checkpoints (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    part_id    INTEGER NOT NULL REFERENCES parts(id) ON DELETE CASCADE,
    offset_km  REAL    NOT NULL,
    label      TEXT    NOT NULL,
    done_on    TEXT,
    done_at_km REAL
);

-- User-owned tags, reusable across all of the user's vehicles.
CREATE TABLE tags (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    name    TEXT    NOT NULL,
    UNIQUE (user_id, name)
);

CREATE TABLE part_tags (
    part_id INTEGER NOT NULL REFERENCES parts(id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tags(id),
    PRIMARY KEY (part_id, tag_id)
);
```

- [ ] **Step 4: Vehicle-Entity erweitern** — in `Vehicle.kt` nach `hasTripMeter` einfügen (Import `java.time.LocalDate` ergänzen):

```kotlin
    // Baseline anchor for trip-meter vehicles: an odometer reading taken at any
    // point in time. Current km = baselineKm + trips strictly after baselineOn.
    val baselineKm: Double? = null,
    val baselineOn: LocalDate? = null,
```

- [ ] **Step 5: VehicleService.update erweitern** — Signatur + Speichern (beide-oder-keins):

```kotlin
    fun update(
        id: Long,
        userId: Long,
        name: String,
        color: String,
        hasTripMeter: Boolean,
        baselineKm: Double? = null,
        baselineOn: LocalDate? = null,
    ): Vehicle {
        // get(...) returns the full aggregate, so the managers set is preserved
        // through the copy/save round-trip.
        val vehicle = get(id, userId)
        // a baseline anchor only makes sense complete: a reading needs its date
        val anchored = baselineKm != null && baselineOn != null
        return repository.save(
            vehicle.copy(
                name = name.trim(), color = color, hasTripMeter = hasTripMeter,
                baselineKm = if (anchored) baselineKm else null,
                baselineOn = if (anchored) baselineOn else null,
            ),
        )
    }
```

(Import `java.time.LocalDate` ergänzen.)

- [ ] **Step 6: Test laufen lassen — muss grün sein**

Run: `./mvnw test -Dtest='VehicleServiceIntegrationTest'`
Expected: PASS (alle Tests, auch die bestehenden).

- [ ] **Step 7: Controller + Formular** — `VehicleController.update` bekommt die zwei optionalen Params und reicht sie durch:

```kotlin
    @PostMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable id: Long,
        @RequestParam name: String,
        @RequestParam color: String,
        @RequestParam(defaultValue = "false") hasTripMeter: Boolean,
        @RequestParam(required = false) baselineKm: Double?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) baselineOn: LocalDate?,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.update(id, userId, name, color, hasTripMeter, baselineKm, baselineOn)
        return "redirect:/vehicles"
    }
```

(Imports: `org.springframework.format.annotation.DateTimeFormat`, `java.time.LocalDate`.)

In `vehicles/form.html` nach dem Trip-Meter-Label einfügen (Baseline nur beim Bearbeiten sichtbar und nur relevant für Trip-Meter; ein 3-Zeilen-Script koppelt die Sichtbarkeit an die Checkbox):

```html
        <!-- Baseline: an odometer reading taken today (or any day) that anchors
             the absolute km count for trip-meter vehicles. Both or none. -->
        <fieldset th:if="${vehicle != null}" id="baseline-fields"
                  class="flex flex-col gap-3 rounded-xl bg-zinc-900/60 p-4">
            <legend class="px-1 text-sm text-zinc-400">Tacho-Ablesung (Basis für Gesamt-km)</legend>
            <label class="flex flex-col gap-2">
                <span class="text-xs text-zinc-500">Gesamt-km laut Tacho</span>
                <input type="number" name="baselineKm" step="any" min="0" inputmode="decimal"
                       th:value="${vehicle.baselineKm}"
                       placeholder="z.B. 19123"
                       class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3 text-lg
                              text-zinc-100 caret-accent outline-none placeholder:text-zinc-600">
            </label>
            <label class="flex flex-col gap-2">
                <span class="text-xs text-zinc-500">abgelesen am</span>
                <input type="date" name="baselineOn"
                       th:value="${vehicle.baselineOn != null ? vehicle.baselineOn : today}"
                       class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3 text-lg
                              text-zinc-100 caret-accent outline-none">
            </label>
        </fieldset>
        <script th:if="${vehicle != null}">
            // baseline only applies to trip-meter vehicles; hide it otherwise
            const tripMeter = document.querySelector('input[name=hasTripMeter]');
            const toggle = () => document.getElementById('baseline-fields').classList.toggle('hidden', !tripMeter.checked);
            tripMeter.addEventListener('change', toggle);
            toggle();
        </script>
```

`VehicleController.editForm` reicht `today` mit:

```kotlin
    @GetMapping("/{id}/edit")
    fun editForm(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable id: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        model.addAttribute("vehicle", service.get(id, userId))
        model.addAttribute("today", LocalDate.now())
        return "vehicles/form"
    }
```

- [ ] **Step 8: Alle Tests laufen lassen**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V6__create_parts_and_tags.sql src/main/kotlin/org/unividuell/mobility/manager/vehicle/ src/main/resources/templates/vehicles/form.html src/test/kotlin/org/unividuell/mobility/manager/vehicle/VehicleServiceIntegrationTest.kt
git commit -m "feat(parts): migration V6 + baseline reading on vehicles"
```

---

### Task 2: CurrentKmCalculator + FuelService.currentKm

**Files:**
- Create: `src/main/kotlin/org/unividuell/mobility/manager/fuel/CurrentKmCalculator.kt`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/fuel/FuelService.kt` (eine Methode ergänzen)
- Test: `src/test/kotlin/org/unividuell/mobility/manager/fuel/CurrentKmCalculatorTest.kt`

**Interfaces:**
- Consumes: `Vehicle(hasTripMeter, baselineKm, baselineOn)` aus Task 1; `FuelEntry(date, kilometers, odometer)`.
- Produces: `CurrentKmCalculator.currentKm(vehicle: Vehicle, entries: List<FuelEntry>): Double?`; `FuelService.currentKm(vehicle: Vehicle): Double?` (null = unbekannt).

- [ ] **Step 1: Failing Unit-Test schreiben** — `CurrentKmCalculatorTest.kt` (Muster `FuelCalculatorTest`: reine JUnit+kotest-Klasse ohne Spring):

```kotlin
package org.unividuell.mobility.manager.fuel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.unividuell.mobility.manager.vehicle.Vehicle
import java.time.LocalDate

class CurrentKmCalculatorTest {

    private fun tripVehicle(baselineKm: Double? = null, baselineOn: LocalDate? = null) =
        Vehicle(id = 1, name = "Moped", color = "#fff", hasTripMeter = true, baselineKm = baselineKm, baselineOn = baselineOn)

    private fun totalVehicle() =
        Vehicle(id = 2, name = "Kombi", color = "#fff", hasTripMeter = false)

    private fun trip(date: LocalDate, km: Double) =
        FuelEntry(vehicleId = 1, date = date, liters = 5.0, pricePerLiter = 1.7, kilometers = km)

    private fun reading(date: LocalDate, odo: Double) =
        FuelEntry(vehicleId = 2, date = date, liters = 40.0, pricePerLiter = 1.7, odometer = odo)

    @Test
    fun `total-only vehicle uses the highest odometer reading`() {
        val entries = listOf(reading(LocalDate.of(2026, 6, 1), 50_100.0), reading(LocalDate.of(2026, 7, 1), 50_800.0))
        CurrentKmCalculator.currentKm(totalVehicle(), entries) shouldBe 50_800.0
    }

    @Test
    fun `total-only vehicle without readings is unknown`() {
        CurrentKmCalculator.currentKm(totalVehicle(), emptyList()) shouldBe null
    }

    @Test
    fun `trip-meter vehicle sums trips strictly after the baseline date`() {
        val vehicle = tripVehicle(baselineKm = 19_000.0, baselineOn = LocalDate.of(2026, 7, 1))
        val entries = listOf(
            trip(LocalDate.of(2026, 6, 30), 200.0), // before the anchor -> already in the reading
            trip(LocalDate.of(2026, 7, 1), 50.0),   // ON the anchor day -> already in the reading
            trip(LocalDate.of(2026, 7, 5), 100.0),
            trip(LocalDate.of(2026, 7, 9), 23.0),
        )
        CurrentKmCalculator.currentKm(vehicle, entries) shouldBe 19_123.0
    }

    @Test
    fun `trip-meter vehicle without a baseline is unknown`() {
        CurrentKmCalculator.currentKm(tripVehicle(), listOf(trip(LocalDate.of(2026, 7, 5), 100.0))) shouldBe null
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `./mvnw test -Dtest='CurrentKmCalculatorTest'`
Expected: Kompilierfehler (`CurrentKmCalculator` unbekannt).

- [ ] **Step 3: Implementierung**

```kotlin
package org.unividuell.mobility.manager.fuel

import org.unividuell.mobility.manager.vehicle.Vehicle

/**
 * Derives a vehicle's current total km from its fuel data — or null when it is
 * unknowable:
 *
 *  - total-only vehicle: the highest recorded odometer reading;
 *  - trip-meter vehicle: the baseline reading plus every trip distance recorded
 *    strictly AFTER the baseline date (trips on/before that day are already part
 *    of the reading). No baseline -> unknown.
 */
object CurrentKmCalculator {

    fun currentKm(vehicle: Vehicle, entries: List<FuelEntry>): Double? =
        if (vehicle.hasTripMeter) {
            val baselineOn = vehicle.baselineOn
            vehicle.baselineKm?.let { base ->
                if (baselineOn == null) return null
                base + entries.filter { it.date > baselineOn }.sumOf { it.kilometers ?: 0.0 }
            }
        } else {
            entries.mapNotNull { it.odometer }.maxOrNull()
        }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `./mvnw test -Dtest='CurrentKmCalculatorTest'`
Expected: PASS.

- [ ] **Step 5: FuelService-Methode ergänzen** (nach `timeline`, Import `org.unividuell.mobility.manager.vehicle.Vehicle`):

```kotlin
    /** The vehicle's current total km derived from its fuel data — null when unknown. */
    fun currentKm(vehicle: Vehicle): Double? =
        CurrentKmCalculator.currentKm(vehicle, repository.findAllByVehicleIdOrderByDateDescIdDesc(vehicle.id!!))
```

- [ ] **Step 6: Alle Tests + Commit**

```bash
./mvnw test
git add src/main/kotlin/org/unividuell/mobility/manager/fuel/ src/test/kotlin/org/unividuell/mobility/manager/fuel/CurrentKmCalculatorTest.kt
git commit -m "feat(parts): derive a vehicle's current total km from fuel data"
```

---

### Task 3: Part/Tag-Aggregate + Repositories

**Files:**
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/Part.kt`
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartCheckpoint.kt`
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartTagRef.kt`
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/Tag.kt`
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartRepository.kt`
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/TagRepository.kt`
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces (von allen Folgetasks genutzt):
  - `Part(id: Long?, vehicleId: Long, name: String, details: String?, priceCents: Long?, installedAtKm: Double, installedOn: LocalDate, retiredAtKm: Double?, retiredOn: LocalDate?, replacedByPartId: Long?, checkpoints: Set<PartCheckpoint>, tags: Set<PartTagRef>)` mit `val active: Boolean` und `val priceEuro: Long?`
  - `PartCheckpoint(id: Long?, offsetKm: Double, label: String, doneOn: LocalDate?, doneAtKm: Double?)` mit `val open: Boolean`
  - `PartTagRef(tagId: Long)`; `Tag(id: Long?, userId: Long, name: String)`
  - `PartRepository: CrudRepository<Part, Long>` mit `findAllByVehicleIdOrderByInstalledOnDescIdDesc(vehicleId): List<Part>`, `findAllByVehicleId(vehicleId): List<Part>`, `findAllByReplacedByPartId(partId): List<Part>`
  - `TagRepository: CrudRepository<Tag, Long>` mit `findAllByUserIdOrderByName(userId): List<Tag>`, `findByUserIdAndName(userId, name): Tag?`

- [ ] **Step 1: Failing Integrationstest schreiben**

```kotlin
package org.unividuell.mobility.manager.parts

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleRepository
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class PartRepositoryIntegrationTest @Autowired constructor(
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val vehicles: VehicleRepository,
    private val users: AppUserService,
    private val userRepository: AppUserRepository,
) {

    private var userId = 0L
    private var vehicleId = 0L

    @BeforeEach
    fun cleanDb() {
        // dependency order: parts (cascades checkpoints/part_tags) -> tags -> vehicles -> users
        parts.deleteAll()
        tags.deleteAll()
        vehicles.deleteAll()
        userRepository.deleteAll()
        userId = users.upsert(1001L, login = "alice", displayName = "Alice").id!!
        vehicleId = vehicleService.create(userId, "Moped", "#06b6d4").id!!
    }

    @Test
    fun `saves and reloads the full aggregate with checkpoints and tag refs`() {
        val tag = tags.save(Tag(userId = userId, name = "kupplung"))
        val saved = parts.save(
            Part(
                vehicleId = vehicleId,
                name = "Kupplung Sachs",
                details = "Verstärkte Ausführung",
                priceCents = 25_000,
                installedAtKm = 19_123.0,
                installedOn = LocalDate.of(2026, 7, 11),
                checkpoints = setOf(
                    PartCheckpoint(offsetKm = 100.0, label = "Kontrolle"),
                    PartCheckpoint(offsetKm = 15_000.0, label = "Beläge prüfen"),
                ),
                tags = setOf(PartTagRef(tagId = tag.id!!)),
            ),
        )

        val reloaded = parts.findById(saved.id!!).orElseThrow()
        reloaded.name shouldBe "Kupplung Sachs"
        reloaded.priceEuro shouldBe 250
        reloaded.active shouldBe true
        reloaded.checkpoints shouldHaveSize 2
        reloaded.checkpoints.all { it.open } shouldBe true
        reloaded.tags.single().tagId shouldBe tag.id
    }

    @Test
    fun `deleting the referenced successor nulls replacedByPartId`() {
        val successor = parts.save(part(name = "Kupplung neu"))
        val old = parts.save(part(name = "Kupplung alt").copy(replacedByPartId = successor.id))

        parts.deleteById(successor.id!!)

        parts.findById(old.id!!).orElseThrow().replacedByPartId shouldBe null
    }

    @Test
    fun `tag names are unique per user`() {
        tags.save(Tag(userId = userId, name = "kupplung"))
        tags.findByUserIdAndName(userId, "kupplung")!!.name shouldBe "kupplung"
        tags.findByUserIdAndName(userId, "bremse") shouldBe null
    }

    private fun part(name: String) = Part(
        vehicleId = vehicleId, name = name, details = null, priceCents = null,
        installedAtKm = 0.0, installedOn = LocalDate.of(2026, 1, 1),
    )
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `./mvnw test -Dtest='PartRepositoryIntegrationTest'`
Expected: Kompilierfehler (Typen unbekannt).

- [ ] **Step 3: Entities implementieren**

`Part.kt`:

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

/**
 * A tracked part installed in a vehicle. Active while [retiredOn] is null;
 * retiring records the removal reading and, when replaced, the successor part.
 * Checkpoints and tag references are owned by this aggregate — Spring Data JDBC
 * rewrites them on every save (their ids are not stable across saves).
 */
@Table("parts")
data class Part(
    @Id val id: Long? = null,
    val vehicleId: Long,
    val name: String,
    val details: String? = null,
    val priceCents: Long? = null,
    val installedAtKm: Double,
    val installedOn: LocalDate,
    val retiredAtKm: Double? = null,
    val retiredOn: LocalDate? = null,
    val replacedByPartId: Long? = null,
    @MappedCollection(idColumn = "part_id")
    val checkpoints: Set<PartCheckpoint> = emptySet(),
    @MappedCollection(idColumn = "part_id")
    val tags: Set<PartTagRef> = emptySet(),
    // created_at is populated by SQLite's DEFAULT CURRENT_TIMESTAMP (not mapped).
) {
    val active: Boolean get() = retiredOn == null

    // money is stored as integer cents; entered and shown as whole euros
    val priceEuro: Long? get() = priceCents?.div(100)
}
```

`PartCheckpoint.kt`:

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

/**
 * A one-shot maintenance checkpoint on a [Part], due at the part's
 * installed-at km plus [offsetKm]. Checking it off records when and at which
 * reading it was done; both stay null while open.
 */
@Table("part_checkpoints")
data class PartCheckpoint(
    @Id val id: Long? = null,
    val offsetKm: Double,
    val label: String,
    val doneOn: LocalDate? = null,
    val doneAtKm: Double? = null,
) {
    val open: Boolean get() = doneOn == null
}
```

`PartTagRef.kt`:

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.data.relational.core.mapping.Table

/**
 * Join row linking a [Part] to a user-owned [Tag]. A value object owned by the
 * Part aggregate (same pattern as VehicleManager on Vehicle).
 */
@Table("part_tags")
data class PartTagRef(
    val tagId: Long,
)
```

`Tag.kt`:

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * A user-owned label for parts, reusable across all of the user's vehicles.
 * Names are stored trimmed + lowercase, unique per user.
 */
@Table("tags")
data class Tag(
    @Id val id: Long? = null,
    val userId: Long,
    val name: String,
)
```

- [ ] **Step 4: Repositories implementieren**

`PartRepository.kt`:

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.data.repository.CrudRepository

interface PartRepository : CrudRepository<Part, Long> {

    /** All parts of a vehicle, newest install first (date, then id as tie-break). */
    fun findAllByVehicleIdOrderByInstalledOnDescIdDesc(vehicleId: Long): List<Part>

    /** Unordered variant used for deleting a vehicle's parts aggregate-aware. */
    fun findAllByVehicleId(vehicleId: Long): List<Part>

    /** Parts pointing at the given part as their successor. */
    fun findAllByReplacedByPartId(partId: Long): List<Part>
}
```

`TagRepository.kt`:

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.data.repository.CrudRepository

interface TagRepository : CrudRepository<Tag, Long> {

    /** All tags of a user, alphabetically — backs the suggestion chips. */
    fun findAllByUserIdOrderByName(userId: Long): List<Tag>

    fun findByUserIdAndName(userId: Long, name: String): Tag?
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `./mvnw test -Dtest='PartRepositoryIntegrationTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/org/unividuell/mobility/manager/parts/ src/test/kotlin/org/unividuell/mobility/manager/parts/
git commit -m "feat(parts): Part/Tag aggregates and repositories"
```

---

### Task 4: DueCalculator

**Files:**
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/DueCalculator.kt`
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/DueCalculatorTest.kt`

**Interfaces:**
- Consumes: `Part`, `PartCheckpoint` aus Task 3.
- Produces: `DueItem(part: Part, checkpoint: PartCheckpoint, dueAtKm: Double, remainingKm: Double?, overdue: Boolean)`; `DueCalculator.openItems(parts: List<Part>, currentKm: Double?): List<DueItem>` (überfällige zuerst, dann nach Rest-km; bei unbekanntem Stand nach dueAtKm; nur aktive Teile, nur offene Checkpoints).

- [ ] **Step 1: Failing Unit-Test schreiben**

```kotlin
package org.unividuell.mobility.manager.parts

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DueCalculatorTest {

    private fun part(
        installedAtKm: Double,
        checkpoints: Set<PartCheckpoint>,
        retiredOn: LocalDate? = null,
    ) = Part(
        id = 1, vehicleId = 1, name = "Kupplung", details = null, priceCents = null,
        installedAtKm = installedAtKm, installedOn = LocalDate.of(2026, 7, 11),
        retiredOn = retiredOn, retiredAtKm = retiredOn?.let { installedAtKm },
        checkpoints = checkpoints,
    )

    @Test
    fun `flags overdue when the due reading is reached exactly, sorts overdue first`() {
        val p = part(
            installedAtKm = 19_000.0,
            checkpoints = setOf(
                PartCheckpoint(id = 1, offsetKm = 100.0, label = "Kontrolle"),      // due 19100 -> overdue
                PartCheckpoint(id = 2, offsetKm = 15_000.0, label = "Beläge"),      // due 34000 -> upcoming
                PartCheckpoint(id = 3, offsetKm = 123.0, label = "Exakt"),          // due 19123 == current -> overdue
            ),
        )

        val items = DueCalculator.openItems(listOf(p), currentKm = 19_123.0)

        items.map { it.checkpoint.label } shouldBe listOf("Kontrolle", "Exakt", "Beläge")
        items[0].overdue shouldBe true
        items[0].remainingKm shouldBe -23.0
        items[1].overdue shouldBe true
        items[1].remainingKm shouldBe 0.0
        items[2].overdue shouldBe false
        items[2].remainingKm shouldBe 14_877.0
        items[2].dueAtKm shouldBe 34_000.0
    }

    @Test
    fun `unknown current km yields items without a verdict, sorted by due reading`() {
        val p = part(
            installedAtKm = 19_000.0,
            checkpoints = setOf(
                PartCheckpoint(id = 1, offsetKm = 15_000.0, label = "Beläge"),
                PartCheckpoint(id = 2, offsetKm = 100.0, label = "Kontrolle"),
            ),
        )

        val items = DueCalculator.openItems(listOf(p), currentKm = null)

        items.map { it.checkpoint.label } shouldBe listOf("Kontrolle", "Beläge")
        items.all { it.remainingKm == null } shouldBe true
        items.none { it.overdue } shouldBe true
    }

    @Test
    fun `ignores retired parts and done checkpoints`() {
        val done = PartCheckpoint(id = 1, offsetKm = 100.0, label = "Erledigt", doneOn = LocalDate.of(2026, 7, 1), doneAtKm = 19_100.0)
        val activeWithDone = part(installedAtKm = 19_000.0, checkpoints = setOf(done))
        val retired = part(
            installedAtKm = 10_000.0,
            checkpoints = setOf(PartCheckpoint(id = 2, offsetKm = 100.0, label = "Alt")),
            retiredOn = LocalDate.of(2026, 7, 10),
        )

        DueCalculator.openItems(listOf(activeWithDone, retired), currentKm = 99_999.0).shouldBeEmpty()
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `./mvnw test -Dtest='DueCalculatorTest'`
Expected: Kompilierfehler (`DueCalculator` unbekannt).

- [ ] **Step 3: Implementierung**

```kotlin
package org.unividuell.mobility.manager.parts

/**
 * Resolves the open maintenance checkpoints of a vehicle's parts against its
 * current total km. Only active parts and open checkpoints count. A checkpoint
 * is overdue once the due reading is reached exactly. With an unknown current
 * km (null) no verdict is made — items are listed by their due reading instead.
 */
object DueCalculator {

    data class DueItem(
        val part: Part,
        val checkpoint: PartCheckpoint,
        val dueAtKm: Double,
        /** negative or zero = overdue by that many km; null = unknown current km */
        val remainingKm: Double?,
        val overdue: Boolean,
    )

    fun openItems(parts: List<Part>, currentKm: Double?): List<DueItem> = parts
        .filter { it.active }
        .flatMap { part ->
            part.checkpoints.filter { it.open }.map { checkpoint ->
                val dueAtKm = part.installedAtKm + checkpoint.offsetKm
                val remainingKm = currentKm?.let { dueAtKm - it }
                DueItem(
                    part = part,
                    checkpoint = checkpoint,
                    dueAtKm = dueAtKm,
                    remainingKm = remainingKm,
                    overdue = remainingKm != null && remainingKm <= 0,
                )
            }
        }
        // overdue (most negative remaining) first, then upcoming by remaining km;
        // with unknown current km every remaining is null -> due reading decides
        .sortedWith(compareBy({ it.remainingKm ?: Double.MAX_VALUE }, { it.dueAtKm }))
}
```

Hinweis: `DueItem` lebt als nested data class im `DueCalculator` — Folgetasks referenzieren `DueCalculator.DueItem`.

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `./mvnw test -Dtest='DueCalculatorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/org/unividuell/mobility/manager/parts/DueCalculator.kt src/test/kotlin/org/unividuell/mobility/manager/parts/DueCalculatorTest.kt
git commit -m "feat(parts): due/overdue resolution for open checkpoints"
```

---

### Task 5: PartService — anlegen, bearbeiten, Liste, Tags

**Files:**
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartService.kt`
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartServiceIntegrationTest.kt`

**Interfaces:**
- Consumes: `PartRepository`, `TagRepository` (Task 3), `VehicleService.get(id, userId)` (Ownership-404), `FuelService.currentKm(vehicle)` (Task 2), `DueCalculator` (Task 4).
- Produces:
  - `PartService.CheckpointInput(offsetKm: Double, label: String)`
  - `PartService.create(userId, vehicleId, name, details: String?, priceEuro: Long?, installedAtKm: Double, installedOn: LocalDate, tagNames: List<String>, checkpoints: List<CheckpointInput>): Part`
  - `PartService.update(userId, vehicleId, partId, name, details: String?, priceEuro: Long?, installedAtKm: Double, installedOn: LocalDate, tagNames: List<String>, checkpoints: List<CheckpointInput>): Part` — offene Checkpoints werden aus dem Formular neu aufgebaut, erledigte bleiben erhalten
  - `PartService.get(partId, vehicleId, userId): Part` (404 bei fremd/falschem Fahrzeug)
  - `PartService.parseTagNames(raw: String?): List<String>` (Komma-getrennt → getrimmt, lowercase, distinct, ohne Leere)
  - `PartService.tagSuggestions(userId): List<Tag>`
  - `PartService.overviewFor(userId, vehicleId): PartsOverview` mit `PartsOverview(currentKm: Double?, dueItems: List<DueCalculator.DueItem>, activeParts: List<Part>, retiredParts: List<Part>, tagNamesByPartId: Map<Long, List<String>>, partNamesById: Map<Long, String>)`

- [ ] **Step 1: Failing Integrationstest schreiben**

```kotlin
package org.unividuell.mobility.manager.parts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.server.ResponseStatusException
import org.unividuell.mobility.manager.fuel.FuelEntry
import org.unividuell.mobility.manager.fuel.FuelEntryRepository
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleRepository
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class PartServiceIntegrationTest @Autowired constructor(
    private val service: PartService,
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val vehicles: VehicleRepository,
    private val fuelEntries: FuelEntryRepository,
    private val users: AppUserService,
    private val userRepository: AppUserRepository,
) {

    private var userA = 0L
    private var userB = 0L
    private var mopedId = 0L

    @BeforeEach
    fun cleanDb() {
        // dependency order: parts -> tags -> fuel_entries -> vehicles -> users
        parts.deleteAll()
        tags.deleteAll()
        fuelEntries.deleteAll()
        vehicles.deleteAll()
        userRepository.deleteAll()
        userA = users.upsert(1001L, login = "alice", displayName = "Alice").id!!
        userB = users.upsert(1002L, login = "bob", displayName = "Bob").id!!
        mopedId = vehicleService.create(userA, "Moped", "#06b6d4").id!!
    }

    private fun createClutch(): Part = service.create(
        userA, mopedId, name = "Kupplung Sachs", details = "verstärkt", priceEuro = 250,
        installedAtKm = 19_123.0, installedOn = LocalDate.of(2026, 7, 11),
        tagNames = listOf("kupplung", "sachs"),
        checkpoints = listOf(
            PartService.CheckpointInput(100.0, "Kontrolle"),
            PartService.CheckpointInput(15_000.0, "Beläge prüfen"),
        ),
    )

    @Test
    fun `create persists the part with checkpoints, price in cents and upserted tags`() {
        val saved = createClutch()

        saved.priceCents shouldBe 25_000
        saved.checkpoints shouldHaveSize 2
        tags.findAllByUserIdOrderByName(userA).map { it.name } shouldBe listOf("kupplung", "sachs")

        // same tag name on another vehicle of the same user is reused, not duplicated
        val kombiId = vehicleService.create(userA, "Kombi", "#10b981").id!!
        service.create(
            userA, kombiId, name = "Kupplung LUK", details = null, priceEuro = null,
            installedAtKm = 80_000.0, installedOn = LocalDate.of(2026, 7, 1),
            tagNames = listOf("kupplung"), checkpoints = emptyList(),
        )
        tags.findAllByUserIdOrderByName(userA) shouldHaveSize 2
    }

    @Test
    fun `parseTagNames trims, lowercases and drops empties and duplicates`() {
        PartService.parseTagNames(" Kupplung, sachs , ,KUPPLUNG,") shouldBe listOf("kupplung", "sachs")
        PartService.parseTagNames(null) shouldBe emptyList()
    }

    @Test
    fun `update rebuilds open checkpoints from the form but keeps done ones`() {
        val created = createClutch()
        // mark one checkpoint done directly (checkOff flow is Task 6)
        val done = created.checkpoints.first { it.label == "Kontrolle" }
            .copy(doneOn = LocalDate.of(2026, 7, 12), doneAtKm = 19_223.0)
        parts.save(created.copy(checkpoints = created.checkpoints.map { if (it.label == "Kontrolle") done else it }.toSet()))

        val updated = service.update(
            userA, mopedId, created.id!!, name = "Kupplung Sachs", details = "verstärkt", priceEuro = 250,
            installedAtKm = 19_123.0, installedOn = LocalDate.of(2026, 7, 11),
            tagNames = listOf("kupplung"),
            checkpoints = listOf(PartService.CheckpointInput(20_000.0, "Großinspektion")),
        )

        updated.checkpoints.map { it.label }.sorted() shouldBe listOf("Großinspektion", "Kontrolle")
        updated.checkpoints.first { it.label == "Kontrolle" }.doneOn shouldBe LocalDate.of(2026, 7, 12)
        updated.tags shouldHaveSize 1
    }

    @Test
    fun `get rejects a foreign vehicle and a part of another vehicle with 404`() {
        val created = createClutch()
        val otherVehicle = vehicleService.create(userB, "Fremd", "#f43f5e").id!!

        shouldThrow<ResponseStatusException> { service.get(created.id!!, mopedId, userB) }
            .statusCode shouldBe HttpStatus.NOT_FOUND
        shouldThrow<ResponseStatusException> { service.get(created.id!!, otherVehicle, userB) }
            .statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `overviewFor resolves due items against the derived current km`() {
        createClutch()
        // trip-meter vehicle with a baseline: 19_000 + trip of 200 after the anchor -> 19_200
        vehicleService.update(mopedId, userA, "Moped", "#06b6d4", hasTripMeter = true,
            baselineKm = 19_000.0, baselineOn = LocalDate.of(2026, 7, 10))
        fuelEntries.save(FuelEntry(vehicleId = mopedId, date = LocalDate.of(2026, 7, 12), liters = 5.0, pricePerLiter = 1.7, kilometers = 200.0))

        val overview = service.overviewFor(userA, mopedId)

        overview.currentKm shouldBe 19_200.0
        overview.activeParts shouldHaveSize 1
        overview.retiredParts shouldHaveSize 0
        overview.dueItems shouldHaveSize 2
        overview.dueItems[0].checkpoint.label shouldBe "Kontrolle" // due at 19_123+100=19_223, current 19_200 -> 23 km left
        overview.dueItems[0].overdue shouldBe false
        overview.dueItems[0].remainingKm shouldBe 23.0
        overview.tagNamesByPartId[overview.activeParts.single().id!!] shouldBe listOf("kupplung", "sachs")
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `./mvnw test -Dtest='PartServiceIntegrationTest'`
Expected: Kompilierfehler (`PartService` unbekannt).

- [ ] **Step 3: PartService implementieren**

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.unividuell.mobility.manager.fuel.FuelService
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

/**
 * Owns the part-tracking workflow: parts with their maintenance checkpoints and
 * user-owned tags, resolved against the vehicle's derived current km. Every
 * entry point guards ownership through [VehicleService.get] (404, never leaks).
 */
@Service
class PartService(
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val fuelService: FuelService,
) {

    /** A checkpoint as typed in the form: due offset (km after install) and label. */
    data class CheckpointInput(val offsetKm: Double, val label: String)

    /** Everything the parts page needs, resolved in one go. */
    data class PartsOverview(
        val currentKm: Double?,
        val dueItems: List<DueCalculator.DueItem>,
        val activeParts: List<Part>,
        val retiredParts: List<Part>,
        val tagNamesByPartId: Map<Long, List<String>>,
        /** id -> name of every part of the vehicle, for "ersetzt durch …" links. */
        val partNamesById: Map<Long, String>,
    )

    fun create(
        userId: Long,
        vehicleId: Long,
        name: String,
        details: String?,
        priceEuro: Long?,
        installedAtKm: Double,
        installedOn: LocalDate,
        tagNames: List<String>,
        checkpoints: List<CheckpointInput>,
    ): Part {
        vehicleService.get(vehicleId, userId) // 404 unless the user owns it
        validate(name, installedAtKm, priceEuro, checkpoints)
        return parts.save(
            Part(
                vehicleId = vehicleId,
                name = name.trim(),
                details = details?.trim()?.ifEmpty { null },
                priceCents = priceEuro?.times(100),
                installedAtKm = installedAtKm,
                installedOn = installedOn,
                checkpoints = checkpoints.map { PartCheckpoint(offsetKm = it.offsetKm, label = it.label.trim()) }.toSet(),
                tags = resolveTags(userId, tagNames),
            ),
        )
    }

    fun update(
        userId: Long,
        vehicleId: Long,
        partId: Long,
        name: String,
        details: String?,
        priceEuro: Long?,
        installedAtKm: Double,
        installedOn: LocalDate,
        tagNames: List<String>,
        checkpoints: List<CheckpointInput>,
    ): Part {
        val part = get(partId, vehicleId, userId)
        validate(name, installedAtKm, priceEuro, checkpoints)
        // open checkpoints are rebuilt from the form (edit/remove/add in one go);
        // done ones carry history and are kept untouched
        val done = part.checkpoints.filter { !it.open }
        return parts.save(
            part.copy(
                name = name.trim(),
                details = details?.trim()?.ifEmpty { null },
                priceCents = priceEuro?.times(100),
                installedAtKm = installedAtKm,
                installedOn = installedOn,
                checkpoints = (done + checkpoints.map { PartCheckpoint(offsetKm = it.offsetKm, label = it.label.trim()) }).toSet(),
                tags = resolveTags(userId, tagNames),
            ),
        )
    }

    /** Loads a part of an owned vehicle, or 404s (never leaks that it exists). */
    fun get(partId: Long, vehicleId: Long, userId: Long): Part {
        vehicleService.get(vehicleId, userId)
        val part = parts.findById(partId).orElse(null)
        if (part == null || part.vehicleId != vehicleId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return part
    }

    fun tagSuggestions(userId: Long): List<Tag> = tags.findAllByUserIdOrderByName(userId)

    fun overviewFor(userId: Long, vehicleId: Long): PartsOverview {
        val vehicle = vehicleService.get(vehicleId, userId)
        val all = parts.findAllByVehicleIdOrderByInstalledOnDescIdDesc(vehicleId)
        val currentKm = fuelService.currentKm(vehicle)
        val tagNamesById = tags.findAllByUserIdOrderByName(userId).associate { it.id!! to it.name }
        return PartsOverview(
            currentKm = currentKm,
            dueItems = DueCalculator.openItems(all, currentKm),
            activeParts = all.filter { it.active },
            retiredParts = all.filter { !it.active },
            tagNamesByPartId = all.associate { part ->
                part.id!! to part.tags.mapNotNull { tagNamesById[it.tagId] }.sorted()
            },
            partNamesById = all.associate { it.id!! to it.name },
        )
    }

    /** Splits a comma-separated tag input into normalised names: trimmed, lowercase, distinct. */
    private fun resolveTags(userId: Long, tagNames: List<String>): Set<PartTagRef> =
        tagNames.map { name ->
            val existing = tags.findByUserIdAndName(userId, name)
            PartTagRef(tagId = (existing ?: tags.save(Tag(userId = userId, name = name))).id!!)
        }.toSet()

    private fun validate(name: String, installedAtKm: Double, priceEuro: Long?, checkpoints: List<CheckpointInput>) {
        if (name.isBlank() || installedAtKm < 0 || (priceEuro != null && priceEuro < 0) ||
            checkpoints.any { it.offsetKm <= 0 || it.label.isBlank() }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
    }

    companion object {
        /** Splits raw comma-separated input into normalised tag names. */
        fun parseTagNames(raw: String?): List<String> =
            raw.orEmpty().split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `./mvnw test -Dtest='PartServiceIntegrationTest'`
Expected: PASS.

- [ ] **Step 5: Alle Tests + Commit**

```bash
./mvnw test
git add src/main/kotlin/org/unividuell/mobility/manager/parts/PartService.kt src/test/kotlin/org/unividuell/mobility/manager/parts/PartServiceIntegrationTest.kt
git commit -m "feat(parts): PartService with tags and parts overview"
```

---

### Task 6: PartService — Abhaken, Ersetzen, Löschen + Fahrzeug-Kaskade

**Files:**
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartService.kt`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/vehicle/VehicleService.kt:49-54` (delete-Kaskade)
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartServiceIntegrationTest.kt` (ergänzen)
- Test: `src/test/kotlin/org/unividuell/mobility/manager/vehicle/VehicleServiceIntegrationTest.kt` (ergänzen)

**Interfaces:**
- Consumes: alles aus Task 5.
- Produces:
  - `PartService.checkOff(userId, vehicleId, partId, checkpointId, doneOn: LocalDate, doneAtKm: Double?)` — 404 bei unbekanntem Checkpoint
  - `PartService.replace(userId, vehicleId, oldPartId, name, details, priceEuro, installedAtKm, installedOn, tagNames, checkpoints): Part` — transaktional: neues Teil speichern, altes stilllegen (`retiredOn`/`retiredAtKm` = Einbau des neuen) + `replacedByPartId` setzen
  - `PartService.delete(userId, vehicleId, partId)` — `ON DELETE SET NULL`/`CASCADE` der Migration räumen Verweise/Kinder ab
  - `PartService.deleteAllFor(vehicleId)` — Aggregat-bewusstes Löschen aller Teile eines Fahrzeugs (von `VehicleService.delete` aufgerufen; kein Ownership-Check, der Aufrufer hat ihn schon gemacht)
- WICHTIG (Zyklusvermeidung): `VehicleService` darf NICHT `PartService` injizieren (der injiziert selbst `VehicleService`). `VehicleService` bekommt stattdessen `PartRepository` injiziert — gleiches Muster wie heute mit `FuelEntryRepository`.

- [ ] **Step 1: Failing Tests schreiben** — in `PartServiceIntegrationTest` ergänzen:

```kotlin
    @Test
    fun `checkOff records the typed values on exactly the addressed checkpoint`() {
        val created = createClutch()
        val target = created.checkpoints.first { it.label == "Kontrolle" }

        service.checkOff(userA, mopedId, created.id!!, target.id!!, doneOn = LocalDate.of(2026, 7, 20), doneAtKm = 19_250.0)

        val reloaded = parts.findById(created.id!!).orElseThrow()
        val done = reloaded.checkpoints.first { it.label == "Kontrolle" }
        done.doneOn shouldBe LocalDate.of(2026, 7, 20)
        done.doneAtKm shouldBe 19_250.0
        reloaded.checkpoints.first { it.label == "Beläge prüfen" }.open shouldBe true
    }

    @Test
    fun `checkOff 404s for a checkpoint id that does not belong to the part`() {
        val created = createClutch()

        shouldThrow<ResponseStatusException> {
            service.checkOff(userA, mopedId, created.id!!, checkpointId = 999_999L, doneOn = LocalDate.of(2026, 7, 20), doneAtKm = null)
        }.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `replace retires the old part and links the successor atomically`() {
        val old = createClutch()

        val successor = service.replace(
            userA, mopedId, old.id!!, name = "Kupplung LUK", details = null, priceEuro = 300,
            installedAtKm = 25_000.0, installedOn = LocalDate.of(2027, 1, 15),
            tagNames = listOf("kupplung"), checkpoints = listOf(PartService.CheckpointInput(100.0, "Kontrolle")),
        )

        val retired = parts.findById(old.id!!).orElseThrow()
        retired.active shouldBe false
        retired.retiredOn shouldBe LocalDate.of(2027, 1, 15)
        retired.retiredAtKm shouldBe 25_000.0
        retired.replacedByPartId shouldBe successor.id
        parts.findById(successor.id!!).orElseThrow().active shouldBe true
        // the retired part's open checkpoints no longer surface as due items
        service.overviewFor(userA, mopedId).dueItems.map { it.part.id } shouldBe listOf(successor.id)
    }

    @Test
    fun `delete removes the part and nulls successor references to it`() {
        val old = createClutch()
        val successor = service.replace(
            userA, mopedId, old.id!!, name = "Kupplung LUK", details = null, priceEuro = null,
            installedAtKm = 25_000.0, installedOn = LocalDate.of(2027, 1, 15),
            tagNames = emptyList(), checkpoints = emptyList(),
        )

        service.delete(userA, mopedId, successor.id!!)

        parts.findById(successor.id!!).isEmpty shouldBe true
        parts.findById(old.id!!).orElseThrow().replacedByPartId shouldBe null
    }
```

In `VehicleServiceIntegrationTest` ergänzen (Konstruktor bekommt `private val parts: PartRepository`; `cleanDb` löscht `parts.deleteAll()` VOR `fuelEntries.deleteAll()`; Imports `org.unividuell.mobility.manager.parts.*`):

```kotlin
    @Test
    fun `delete also removes the vehicle's parts with their checkpoints`() {
        val created = service.create(userA, "Gone", "#06b6d4")
        parts.save(
            Part(
                vehicleId = created.id!!, name = "Kupplung", details = null, priceCents = null,
                installedAtKm = 100.0, installedOn = LocalDate.of(2026, 7, 11),
                checkpoints = setOf(PartCheckpoint(offsetKm = 100.0, label = "Kontrolle")),
            ),
        )

        service.delete(created.id!!, userA)

        parts.count() shouldBe 0
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `./mvnw test -Dtest='PartServiceIntegrationTest,VehicleServiceIntegrationTest'`
Expected: Kompilierfehler (`checkOff`/`replace`/`delete`/`deleteAllFor` unbekannt).

- [ ] **Step 3: PartService-Methoden ergänzen** (Imports: `org.springframework.transaction.annotation.Transactional`):

```kotlin
    /**
     * Confirms an open checkpoint with the (possibly corrected) date and reading
     * from the confirm step. 404s when the checkpoint doesn't belong to the part.
     */
    fun checkOff(userId: Long, vehicleId: Long, partId: Long, checkpointId: Long, doneOn: LocalDate, doneAtKm: Double?) {
        val part = get(partId, vehicleId, userId)
        val target = part.checkpoints.firstOrNull { it.id == checkpointId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        parts.save(
            part.copy(
                checkpoints = part.checkpoints
                    .map { if (it.id == target.id) it.copy(doneOn = doneOn, doneAtKm = doneAtKm) else it }
                    .toSet(),
            ),
        )
    }

    /**
     * Replaces a part in one step: the successor is created and the old part is
     * retired at the successor's install reading/date, linked via replacedByPartId.
     * Atomic — a failure can't leave the successor in but the old part active.
     */
    @Transactional
    fun replace(
        userId: Long,
        vehicleId: Long,
        oldPartId: Long,
        name: String,
        details: String?,
        priceEuro: Long?,
        installedAtKm: Double,
        installedOn: LocalDate,
        tagNames: List<String>,
        checkpoints: List<CheckpointInput>,
    ): Part {
        val old = get(oldPartId, vehicleId, userId)
        val successor = create(userId, vehicleId, name, details, priceEuro, installedAtKm, installedOn, tagNames, checkpoints)
        parts.save(old.copy(retiredOn = installedOn, retiredAtKm = installedAtKm, replacedByPartId = successor.id))
        return successor
    }

    /**
     * Deletes a part (typo correction). Checkpoints and tag joins cascade via the
     * schema; successor references pointing at it are auto-nulled (ON DELETE SET NULL).
     */
    fun delete(userId: Long, vehicleId: Long, partId: Long) {
        val part = get(partId, vehicleId, userId)
        parts.deleteById(part.id!!)
    }

    /**
     * Removes every part of a vehicle — used when the vehicle itself is deleted.
     * Ownership is the caller's concern. Aggregate-aware (children cascade).
     */
    fun deleteAllFor(vehicleId: Long) {
        parts.deleteAll(parts.findAllByVehicleId(vehicleId))
    }
```

- [ ] **Step 4: VehicleService-Kaskade** — Konstruktor bekommt `private val parts: PartRepository` (Import `org.unividuell.mobility.manager.parts.PartRepository` — bewusst das Repository, nicht den Service: `PartService` injiziert `VehicleService`, andersherum gäbe es einen Bean-Zyklus). `delete` wird zu:

```kotlin
    @Transactional
    fun delete(id: Long, userId: Long) {
        val vehicle = get(id, userId)
        // parts and refuelings reference the vehicle (FK), so they go first; the
        // parts' checkpoints/tag-joins cascade via the schema, the manager join
        // rows cascade with the aggregate. Atomic, so a failure can't leave the
        // vehicle gone but its children orphaned.
        parts.deleteAll(parts.findAllByVehicleId(vehicle.id!!))
        fuelEntries.deleteAllByVehicleId(vehicle.id!!)
        repository.deleteById(vehicle.id!!)
    }
```

- [ ] **Step 5: Tests laufen lassen — müssen grün sein**

Run: `./mvnw test -Dtest='PartServiceIntegrationTest,VehicleServiceIntegrationTest'`
Expected: PASS.

- [ ] **Step 6: Alle Tests + Commit**

```bash
./mvnw test
git add src/main/kotlin/org/unividuell/mobility/manager/ src/test/kotlin/org/unividuell/mobility/manager/
git commit -m "feat(parts): check-off, one-step replace, delete + vehicle cascade"
```

---

### Task 7: Teile-Seite (Liste) + Link von der Fahrzeug-Karte

**Files:**
- Create: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartController.kt`
- Create: `src/main/resources/templates/parts/list.html`
- Modify: `src/main/resources/templates/vehicles/index.html:90-100` (Icon-Link neben dem Tank-Listen-Link)
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt`

**Interfaces:**
- Consumes: `PartService.overviewFor(userId, vehicleId)` (Task 5), `VehicleService.get` — Muster: `FuelController.list`.
- Produces: Route `GET /vehicles/{vehicleId}/parts` (View `parts/list`); Model-Attribute `vehicle`, `overview` (PartsOverview), `today` (LocalDate). Das Abhaken-Formular im Template postet auf die Route aus Task 9 — der Button ist hier schon im Markup, der POST-Endpoint kommt in Task 9.

- [ ] **Step 1: Failing Controller-Test schreiben** (Muster `VehicleControllerIntegrationTest`: `login()`-Helper mit `oauth2Login().attributes { it["id"] = githubId }`):

```kotlin
package org.unividuell.mobility.manager.parts

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.unividuell.mobility.manager.fuel.FuelEntryRepository
import org.unividuell.mobility.manager.user.AppUserRepository
import org.unividuell.mobility.manager.user.AppUserService
import org.unividuell.mobility.manager.vehicle.VehicleRepository
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartControllerIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val partService: PartService,
    private val parts: PartRepository,
    private val tags: TagRepository,
    private val vehicleService: VehicleService,
    private val vehicles: VehicleRepository,
    private val fuelEntries: FuelEntryRepository,
    private val users: AppUserService,
    private val userRepository: AppUserRepository,
) {

    private val githubId = 4711L
    private var userId = 0L
    private var vehicleId = 0L

    @BeforeEach
    fun setUp() {
        // dependency order: parts -> tags -> fuel_entries -> vehicles -> users
        parts.deleteAll()
        tags.deleteAll()
        fuelEntries.deleteAll()
        vehicles.deleteAll()
        userRepository.deleteAll()
        userId = users.upsert(githubId, login = "octocat", displayName = "The Octocat").id!!
        vehicleId = vehicleService.create(userId, "Moped", "#06b6d4", hasTripMeter = false).id!!
    }

    private fun login(): RequestPostProcessor = oauth2Login().attributes { it["id"] = githubId }

    private fun createClutch(): Part = partService.create(
        userId, vehicleId, name = "Kupplung Sachs", details = "verstärkt", priceEuro = 250,
        installedAtKm = 19_123.0, installedOn = LocalDate.of(2026, 7, 11),
        tagNames = listOf("kupplung"),
        checkpoints = listOf(PartService.CheckpointInput(100.0, "Kontrolle")),
    )

    @Test
    fun `parts page shows the empty state, then the active part with tag and price`() {
        mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }
            .andReturn().response.contentAsString shouldContain "Noch keine Teile"

        createClutch()

        val body = mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain "Kupplung Sachs"
        body shouldContain "kupplung"
        body shouldContain "250"
        body shouldContain "Kontrolle"
    }

    @Test
    fun `parts page marks an overdue checkpoint`() {
        createClutch()
        // total-only vehicle: an odometer reading beyond due (19_223) makes it overdue
        fuelEntries.save(
            org.unividuell.mobility.manager.fuel.FuelEntry(
                vehicleId = vehicleId, date = LocalDate.of(2026, 8, 1),
                liters = 5.0, pricePerLiter = 1.7, odometer = 19_300.0,
            ),
        )

        val body = mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain """data-testid="due-overdue""""
    }

    @Test
    fun `parts page hints at the missing baseline instead of judging`() {
        // trip-meter vehicle without a baseline -> unknown current km
        val tripVehicleId = vehicleService.create(userId, "Trippy", "#10b981", hasTripMeter = true).id!!
        partService.create(
            userId, tripVehicleId, name = "Kette", details = null, priceEuro = null,
            installedAtKm = 5_000.0, installedOn = LocalDate.of(2026, 7, 1),
            tagNames = emptyList(), checkpoints = listOf(PartService.CheckpointInput(500.0, "Spannung prüfen")),
        )

        val body = mockMvc.get("/vehicles/$tripVehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain "Tacho-Ablesung"
        body shouldNotContain """data-testid="due-overdue""""
    }

    @Test
    fun `parts page 404s for a foreign vehicle`() {
        val strangerId = users.upsert(2222L, login = "stranger", displayName = "Stranger").id!!
        val foreign = vehicleService.create(strangerId, "Fremd", "#f43f5e").id!!

        mockMvc.get("/vehicles/$foreign/parts") { with(login()) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `vehicle overview links each vehicle to its parts page`() {
        val body = mockMvc.get("/vehicles") { with(login()) }.andReturn().response.contentAsString
        body shouldContain """data-testid="parts-list-link""""
        body shouldContain "/vehicles/$vehicleId/parts"
    }
}
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: FAIL (404 auf `/vehicles/{id}/parts`, fehlender Link).

- [ ] **Step 3: PartController (nur GET der Liste)**

```kotlin
package org.unividuell.mobility.manager.parts

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.unividuell.mobility.manager.user.CurrentUser
import org.unividuell.mobility.manager.vehicle.VehicleService
import java.time.LocalDate

@Controller
@RequestMapping("/vehicles/{vehicleId}/parts")
class PartController(
    private val service: PartService,
    private val vehicleService: VehicleService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        model.addAttribute("vehicle", vehicleService.get(vehicleId, userId)) // 404 unless owned
        model.addAttribute("overview", service.overviewFor(userId, vehicleId))
        model.addAttribute("today", LocalDate.now())
        return "parts/list"
    }
}
```

- [ ] **Step 4: Template `parts/list.html`**

```html
<!DOCTYPE html>
<html lang="de" class="h-full" xmlns:th="http://www.thymeleaf.org"
      th:style="${accent != null} ? '--accent:' + ${accent.color} + ';--accent-fg:' + ${accent.onColor} : null">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
    <meta name="theme-color" content="#09090b">
    <meta name="color-scheme" content="dark">
    <title th:text="'Teile – ' + ${vehicle.name}">Teile</title>
    <script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"></script>
    <th:block th:replace="~{fragments/theme :: accent}"></th:block>
</head>
<body class="h-full bg-zinc-950 text-zinc-100 antialiased selection:bg-accent/30">
<main class="mx-auto flex min-h-full w-full max-w-md flex-col px-4
             pt-[max(env(safe-area-inset-top),1rem)]
             pb-[max(env(safe-area-inset-bottom),1rem)]">

    <div th:replace="~{fragments/header :: header(home=true)}"></div>

    <h1 class="mb-1 text-2xl font-extrabold tracking-tight" th:text="'Teile – ' + ${vehicle.name}">Teile</h1>
    <p class="mb-4 text-sm text-zinc-500">
        <th:block th:if="${overview.currentKm != null}">
            Stand: <span class="tabular-nums" th:text="${#numbers.formatDecimal(overview.currentKm, 1, 'POINT', 0, 'POINT')}">19.200</span> km
        </th:block>
        <!-- unknown current km: no verdict — point at the baseline field instead -->
        <th:block th:if="${overview.currentKm == null}">
            Gesamt-km unbekannt – trage im
            <a th:href="@{/vehicles/{id}/edit(id=${vehicle.id})}" class="text-accent hover:text-accent-bright">Fahrzeug</a>
            eine Tacho-Ablesung ein.
        </th:block>
    </p>

    <!-- ═══ Fälligkeit: open checkpoints of active parts, overdue first ═══ -->
    <section th:if="${!overview.dueItems.isEmpty()}" class="mb-6 flex flex-col gap-2">
        <h2 class="text-xs font-semibold uppercase tracking-wide text-zinc-500">Wartung</h2>
        <div th:each="item : ${overview.dueItems}"
             th:data-testid="${item.overdue} ? 'due-overdue' : 'due-upcoming'"
             th:classappend="${item.overdue} ? 'ring-1 ring-rose-500/40' : ''"
             class="rounded-xl bg-zinc-900/60 p-3">
            <div class="flex items-baseline justify-between gap-3">
                <span class="min-w-0 truncate font-medium"
                      th:text="${item.part.name} + ' · ' + ${item.checkpoint.label}">Kupplung · Kontrolle</span>
                <span class="shrink-0 text-sm tabular-nums"
                      th:classappend="${item.overdue} ? 'font-semibold text-rose-400' : 'text-zinc-400'">
                    <th:block th:if="${item.remainingKm != null}"
                              th:text="${item.overdue}
                                       ? '+' + ${#numbers.formatDecimal(-item.remainingKm, 1, 'POINT', 0, 'POINT')} + ' km drüber'
                                       : 'noch ' + ${#numbers.formatDecimal(item.remainingKm, 1, 'POINT', 0, 'POINT')} + ' km'">noch 23 km</th:block>
                    <th:block th:if="${item.remainingKm == null}"
                              th:text="'bei ' + ${#numbers.formatDecimal(item.dueAtKm, 1, 'POINT', 0, 'POINT')} + ' km'">bei 19.223 km</th:block>
                </span>
            </div>
            <div class="mt-0.5 text-xs text-zinc-500"
                 th:text="'fällig bei ' + ${#numbers.formatDecimal(item.dueAtKm, 1, 'POINT', 0, 'POINT')} + ' km'">fällig bei 19.223 km</div>

            <!-- check-off: a <details> confirm step with prefilled, editable values -->
            <details class="mt-2">
                <summary class="cursor-pointer text-sm font-medium text-accent hover:text-accent-bright">Abhaken</summary>
                <form th:action="@{/vehicles/{v}/parts/{p}/checkpoints/{c}/done(v=${vehicle.id},p=${item.part.id},c=${item.checkpoint.id})}"
                      method="post" class="mt-2 flex flex-col gap-2">
                    <label class="flex items-center justify-between gap-3 text-sm text-zinc-400">
                        Erledigt am
                        <input type="date" name="doneOn" required th:value="${today}"
                               class="rounded-lg bg-zinc-950/60 px-3 py-2 text-zinc-100 outline-none">
                    </label>
                    <label class="flex items-center justify-between gap-3 text-sm text-zinc-400">
                        bei km
                        <input type="number" name="doneAtKm" step="any" min="0" inputmode="decimal"
                               th:value="${overview.currentKm}"
                               class="w-32 rounded-lg bg-zinc-950/60 px-3 py-2 text-right tabular-nums text-zinc-100 outline-none">
                    </label>
                    <button type="submit"
                            class="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-accent-fg transition hover:bg-accent-bright">
                        Bestätigen
                    </button>
                </form>
            </details>
        </div>
    </section>

    <!-- ═══ Aktive Teile ═══ -->
    <section class="flex flex-col gap-3">
        <h2 class="text-xs font-semibold uppercase tracking-wide text-zinc-500">Eingebaut</h2>

        <p th:if="${overview.activeParts.isEmpty()}"
           class="rounded-xl bg-zinc-900/60 px-4 py-8 text-center text-zinc-500">
            Noch keine Teile.
        </p>

        <div th:each="part : ${overview.activeParts}" th:data-part="${part.id}"
             class="flex flex-col gap-2 rounded-2xl bg-zinc-900/60 p-4">
            <div class="flex items-baseline justify-between gap-3">
                <span class="min-w-0 truncate text-lg font-bold tracking-tight" th:text="${part.name}">Kupplung Sachs</span>
                <span th:if="${part.priceEuro != null}" class="shrink-0 text-sm tabular-nums text-zinc-400"
                      th:text="${part.priceEuro} + ' €'">250 €</span>
            </div>
            <div th:if="${!overview.tagNamesByPartId.get(part.id).isEmpty()}" class="flex flex-wrap gap-1.5">
                <span th:each="tag : ${overview.tagNamesByPartId.get(part.id)}"
                      class="rounded-full bg-zinc-800 px-2 py-0.5 text-xs text-zinc-300" th:text="${tag}">kupplung</span>
            </div>
            <div class="text-sm text-zinc-500">
                eingebaut bei <span class="tabular-nums" th:text="${#numbers.formatDecimal(part.installedAtKm, 1, 'POINT', 0, 'POINT')}">19.123</span> km
                am <span th:text="${#temporals.format(part.installedOn, 'dd.MM.yyyy')}">11.07.2026</span>
                <th:block th:if="${overview.currentKm != null}">
                    · <span class="tabular-nums" th:text="${#numbers.formatDecimal(overview.currentKm - part.installedAtKm, 1, 'POINT', 0, 'POINT')}">77</span> km gelaufen
                </th:block>
            </div>
            <p th:if="${part.details != null}" class="line-clamp-2 text-sm text-zinc-400" th:text="${part.details}">Details</p>
            <div class="flex items-center gap-3 border-t border-white/5 pt-2 text-sm">
                <a th:href="@{/vehicles/{v}/parts/{id}/edit(v=${vehicle.id},id=${part.id})}"
                   class="text-zinc-400 transition hover:text-zinc-100">Bearbeiten</a>
                <a th:href="@{/vehicles/{v}/parts/new(v=${vehicle.id},replaces=${part.id})}"
                   data-testid="replace-part-link"
                   class="text-accent transition hover:text-accent-bright">Ersetzen</a>
            </div>
        </div>
    </section>

    <!-- ═══ Historie: retired parts, collapsed ═══ -->
    <details th:if="${!overview.retiredParts.isEmpty()}" class="mt-6">
        <summary class="cursor-pointer text-xs font-semibold uppercase tracking-wide text-zinc-500">
            Historie (<span th:text="${overview.retiredParts.size()}">1</span>)
        </summary>
        <div class="mt-2 flex flex-col gap-2">
            <div th:each="part : ${overview.retiredParts}" class="rounded-xl bg-zinc-900/40 p-3 text-sm text-zinc-500">
                <div class="flex items-baseline justify-between gap-3">
                    <span class="min-w-0 truncate font-medium text-zinc-400" th:text="${part.name}">Kupplung alt</span>
                    <span class="shrink-0 tabular-nums"
                          th:text="${#numbers.formatDecimal(part.retiredAtKm - part.installedAtKm, 1, 'POINT', 0, 'POINT')} + ' km gelaufen'">5.877 km gelaufen</span>
                </div>
                <div th:text="'ausgebaut bei ' + ${#numbers.formatDecimal(part.retiredAtKm, 1, 'POINT', 0, 'POINT')} + ' km am ' + ${#temporals.format(part.retiredOn, 'dd.MM.yyyy')}">ausgebaut …</div>
                <div th:if="${part.replacedByPartId != null}"
                     th:text="'ersetzt durch ' + ${overview.partNamesById.get(part.replacedByPartId)}">ersetzt durch …</div>
            </div>
        </div>
    </details>

    <a th:href="@{/vehicles/{v}/parts/new(v=${vehicle.id})}"
       class="mt-6 w-full rounded-xl bg-accent px-4 py-4 text-center text-base font-semibold text-accent-fg
              transition active:scale-[0.98] hover:bg-accent-bright
              focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-bright">
        + Neues Teil
    </a>

</main>
</body>
</html>
```

- [ ] **Step 5: Icon-Link auf der Fahrzeug-Karte** — in `vehicles/index.html` direkt VOR dem `fuel-list-link`-Anchor einfügen (Schraubenschlüssel-Icon):

```html
                <a th:href="@{/vehicles/{id}/parts(id=${vehicle.id})}"
                   data-testid="parts-list-link"
                   aria-label="Teile" title="Teile"
                   class="rounded-lg p-2 text-zinc-400 transition hover:text-accent-bright
                          focus:outline-none focus-visible:ring-2 focus-visible:ring-accent">
                    <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
                         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <path d="M21.75 6.75a4.5 4.5 0 0 1-6.364 4.093l-6.75 6.75a2.121 2.121 0 1 1-3-3l6.75-6.75A4.5 4.5 0 0 1 17.25 2.25l-2.47 2.47 1.06 3.44 3.44 1.06 2.47-2.47z"></path>
                    </svg>
                </a>
```

- [ ] **Step 6: Tests laufen lassen — müssen grün sein**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: PASS. Hinweis: Der Abhaken-POST-Endpoint existiert noch nicht — die Liste rendert nur das Formular, das ist hier ausreichend.

- [ ] **Step 7: Alle Tests + Commit**

```bash
./mvnw test
git add src/main/kotlin/org/unividuell/mobility/manager/parts/PartController.kt src/main/resources/templates/parts/ src/main/resources/templates/vehicles/index.html src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt
git commit -m "feat(parts): per-vehicle parts page with due list, cards and history"
```

---

### Task 8: Teil-Formular (anlegen/bearbeiten) + POST-Endpoints

**Files:**
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartController.kt`
- Create: `src/main/resources/templates/parts/form.html`
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt` (ergänzen)

**Interfaces:**
- Consumes: `PartService.create/update/get/tagSuggestions`, `PartService.parseTagNames`, `FuelService.currentKm` (Vorbefüllung Einbau-km).
- Produces: Routen `GET /vehicles/{vehicleId}/parts/new`, `POST /vehicles/{vehicleId}/parts`, `GET /vehicles/{vehicleId}/parts/{id}/edit`, `POST /vehicles/{vehicleId}/parts/{id}`, `POST /vehicles/{vehicleId}/parts/{id}/delete`. Checkpoint-Zeilen kommen als parallele Params `checkpointOffsetKm`/`checkpointLabel` (Strings; komplett leere Zeilen werden verworfen, halb gefüllte → 400). Tags als ein Komma-String `tags`. Preis als `priceEuro` (ganze Euro). `parseCheckpoints(offsets: List<String>?, labels: List<String>?): List<PartService.CheckpointInput>` als private Controller-Funktion.

- [ ] **Step 1: Failing Tests schreiben** — in `PartControllerIntegrationTest` ergänzen (Imports: `org.springframework.test.web.servlet.post`, `io.kotest.matchers.shouldBe`, `io.kotest.matchers.collections.shouldHaveSize`):

```kotlin
    @Test
    fun `create persists a part from the form and redirects to the list`() {
        mockMvc.post("/vehicles/$vehicleId/parts") {
            with(login())
            param("name", "Kupplung Sachs")
            param("details", "verstärkte Ausführung")
            param("priceEuro", "250")
            param("installedAtKm", "19123")
            param("installedOn", "2026-07-11")
            param("tags", "Kupplung, Sachs")
            param("checkpointOffsetKm", "100", "")      // one filled row, one empty row (dropped)
            param("checkpointLabel", "Kontrolle", "")
        }.andExpect { status { is3xxRedirection() } }

        val saved = parts.findAllByVehicleId(vehicleId).single()
        saved.name shouldBe "Kupplung Sachs"
        saved.priceCents shouldBe 25_000
        saved.checkpoints.single().label shouldBe "Kontrolle"
        tags.findAllByUserIdOrderByName(userId).map { it.name } shouldBe listOf("kupplung", "sachs")
    }

    @Test
    fun `create rejects a half-filled checkpoint row with 400`() {
        mockMvc.post("/vehicles/$vehicleId/parts") {
            with(login())
            param("name", "Kette")
            param("installedAtKm", "100")
            param("installedOn", "2026-07-11")
            param("checkpointOffsetKm", "500")
            param("checkpointLabel", "")               // offset without label
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `edit form is prefilled and update rewrites open checkpoints`() {
        val created = createClutch()

        val body = mockMvc.get("/vehicles/$vehicleId/parts/${created.id}/edit") { with(login()) }
            .andReturn().response.contentAsString
        body shouldContain "Kupplung Sachs"
        body shouldContain "kupplung"

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}") {
            with(login())
            param("name", "Kupplung Sachs Plus")
            param("priceEuro", "250")
            param("installedAtKm", "19123")
            param("installedOn", "2026-07-11")
            param("tags", "kupplung")
            param("checkpointOffsetKm", "20000")
            param("checkpointLabel", "Großinspektion")
        }.andExpect { status { is3xxRedirection() } }

        val updated = parts.findById(created.id!!).orElseThrow()
        updated.name shouldBe "Kupplung Sachs Plus"
        updated.checkpoints.single().label shouldBe "Großinspektion"
    }

    @Test
    fun `delete removes the part from the list`() {
        val created = createClutch()

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}/delete") { with(login()) }
            .andExpect { status { is3xxRedirection() } }

        parts.count() shouldBe 0
    }

    @Test
    fun `form POSTs 404 for a foreign vehicle`() {
        val strangerId = users.upsert(2222L, login = "stranger", displayName = "Stranger").id!!
        val foreign = vehicleService.create(strangerId, "Fremd", "#f43f5e").id!!

        mockMvc.post("/vehicles/$foreign/parts") {
            with(login())
            param("name", "Hijack")
            param("installedAtKm", "1")
            param("installedOn", "2026-07-11")
        }.andExpect { status { isNotFound() } }
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: FAIL (404/405 auf den neuen Routen).

- [ ] **Step 3: Controller-Endpoints ergänzen** — in `PartController` (neue Imports: `PostMapping`, `RequestParam`, `org.springframework.format.annotation.DateTimeFormat`, `org.springframework.http.HttpStatus`, `org.springframework.web.server.ResponseStatusException`, `org.unividuell.mobility.manager.fuel.FuelService`; Konstruktor bekommt zusätzlich `private val fuelService: FuelService`):

```kotlin
    @GetMapping("/new")
    fun newForm(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @RequestParam(required = false) replaces: Long?,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicle = vehicleService.get(vehicleId, userId)
        // "replace" opens the same form prefilled from the predecessor (Task 10
        // wires the template side; passing null renders a plain new-part form)
        val predecessor = replaces?.let { service.get(it, vehicleId, userId) }
        model.addAttribute("vehicle", vehicle)
        model.addAttribute("part", null)
        model.addAttribute("predecessor", predecessor)
        model.addAttribute("prefillName", predecessor?.name)
        model.addAttribute("prefillTags", predecessor?.let { tagsAsInput(userId, it) })
        model.addAttribute("currentKm", fuelService.currentKm(vehicle))
        model.addAttribute("today", LocalDate.now())
        model.addAttribute("tagSuggestions", service.tagSuggestions(userId))
        return "parts/form"
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @RequestParam name: String,
        @RequestParam(required = false) details: String?,
        @RequestParam(required = false) priceEuro: Long?,
        @RequestParam installedAtKm: Double,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) installedOn: LocalDate,
        @RequestParam(required = false) tags: String?,
        @RequestParam(required = false) checkpointOffsetKm: List<String>?,
        @RequestParam(required = false) checkpointLabel: List<String>?,
        @RequestParam(required = false) replacesPartId: Long?,
    ): String {
        val userId = currentUser.require(principal).id!!
        val tagNames = PartService.parseTagNames(tags)
        val checkpoints = parseCheckpoints(checkpointOffsetKm, checkpointLabel)
        if (replacesPartId != null) {
            service.replace(userId, vehicleId, replacesPartId, name, details, priceEuro, installedAtKm, installedOn, tagNames, checkpoints)
        } else {
            service.create(userId, vehicleId, name, details, priceEuro, installedAtKm, installedOn, tagNames, checkpoints)
        }
        return "redirect:/vehicles/$vehicleId/parts"
    }

    @GetMapping("/{id}/edit")
    fun editForm(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
        model: Model,
    ): String {
        val userId = currentUser.require(principal).id!!
        val vehicle = vehicleService.get(vehicleId, userId)
        val part = service.get(id, vehicleId, userId)
        model.addAttribute("vehicle", vehicle)
        model.addAttribute("part", part)
        model.addAttribute("predecessor", null)
        model.addAttribute("prefillName", part.name)
        model.addAttribute("prefillTags", tagsAsInput(userId, part))
        model.addAttribute("currentKm", fuelService.currentKm(vehicle))
        model.addAttribute("today", LocalDate.now())
        model.addAttribute("tagSuggestions", service.tagSuggestions(userId))
        return "parts/form"
    }

    @PostMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
        @RequestParam name: String,
        @RequestParam(required = false) details: String?,
        @RequestParam(required = false) priceEuro: Long?,
        @RequestParam installedAtKm: Double,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) installedOn: LocalDate,
        @RequestParam(required = false) tags: String?,
        @RequestParam(required = false) checkpointOffsetKm: List<String>?,
        @RequestParam(required = false) checkpointLabel: List<String>?,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.update(
            userId, vehicleId, id, name, details, priceEuro, installedAtKm, installedOn,
            PartService.parseTagNames(tags), parseCheckpoints(checkpointOffsetKm, checkpointLabel),
        )
        return "redirect:/vehicles/$vehicleId/parts"
    }

    @PostMapping("/{id}/delete")
    fun delete(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @PathVariable id: Long,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.delete(userId, vehicleId, id)
        return "redirect:/vehicles/$vehicleId/parts"
    }

    /** The part's tags as the comma-string the form's text input expects. */
    private fun tagsAsInput(userId: Long, part: Part): String {
        val namesById = service.tagSuggestions(userId).associate { it.id!! to it.name }
        return part.tags.mapNotNull { namesById[it.tagId] }.sorted().joinToString(", ")
    }

    /**
     * Pairs the parallel checkpoint form arrays. Rows with both fields blank are
     * dropped (the always-present empty template row); half-filled rows are a 400.
     */
    private fun parseCheckpoints(offsets: List<String>?, labels: List<String>?): List<PartService.CheckpointInput> {
        val pairs = offsets.orEmpty().map { it.trim() }.zip(labels.orEmpty().map { it.trim() })
        return pairs.mapNotNull { (offset, label) ->
            when {
                offset.isEmpty() && label.isEmpty() -> null
                offset.isEmpty() || label.isEmpty() ->
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST)
                else -> PartService.CheckpointInput(
                    offsetKm = offset.replace(',', '.').toDoubleOrNull()
                        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST),
                    label = label,
                )
            }
        }
    }
```

- [ ] **Step 4: Template `parts/form.html`** — offene Checkpoints des Teils werden beim Bearbeiten als vorbefüllte Zeilen gerendert (erledigte nicht — die bleiben serverseitig erhalten):

```html
<!DOCTYPE html>
<html lang="de" class="h-full" xmlns:th="http://www.thymeleaf.org"
      th:style="${accent != null} ? '--accent:' + ${accent.color} + ';--accent-fg:' + ${accent.onColor} : null">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
    <meta name="theme-color" content="#09090b">
    <meta name="color-scheme" content="dark">
    <title th:text="${part != null} ? 'Teil bearbeiten' : 'Neues Teil'">Teil</title>
    <script src="https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4"></script>
    <th:block th:replace="~{fragments/theme :: accent}"></th:block>
</head>
<body class="h-full bg-zinc-950 text-zinc-100 antialiased selection:bg-accent/30">
<main class="mx-auto flex min-h-full w-full max-w-md flex-col px-4
             pt-[max(env(safe-area-inset-top),1rem)]
             pb-[max(env(safe-area-inset-bottom),1rem)]">

    <div th:replace="~{fragments/header :: header(home=true)}"></div>

    <h1 class="mb-2 text-2xl font-extrabold tracking-tight"
        th:text="${part != null} ? 'Teil bearbeiten' : 'Neues Teil'">Neues Teil</h1>

    <!-- replace flow: the predecessor is retired when this form is saved -->
    <p th:if="${predecessor != null}" class="mb-4 rounded-xl bg-zinc-900/60 px-4 py-3 text-sm text-zinc-400">
        ersetzt <span class="font-medium text-zinc-200" th:text="${predecessor.name}">Kupplung alt</span>
    </p>

    <!-- new → POST /vehicles/{v}/parts ; edit → POST /vehicles/{v}/parts/{id} -->
    <form th:action="${part != null}
                     ? @{/vehicles/{v}/parts/{id}(v=${vehicle.id},id=${part.id})}
                     : @{/vehicles/{v}/parts(v=${vehicle.id})}"
          method="post" class="flex flex-col gap-5">

        <input th:if="${predecessor != null}" type="hidden" name="replacesPartId" th:value="${predecessor.id}">

        <label class="flex flex-col gap-2">
            <span class="text-sm text-zinc-400">Name</span>
            <input type="text" name="name" required autofocus autocomplete="off"
                   th:value="${prefillName}"
                   placeholder="z.B. Kupplung Sachs"
                   class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3 text-lg
                          text-zinc-100 caret-accent outline-none placeholder:text-zinc-600">
        </label>

        <label class="flex flex-col gap-2">
            <span class="text-sm text-zinc-400">Tags (Komma-getrennt)</span>
            <input type="text" name="tags" id="tags-input" autocomplete="off"
                   th:value="${prefillTags}"
                   placeholder="z.B. kupplung, sachs"
                   class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3
                          text-zinc-100 caret-accent outline-none placeholder:text-zinc-600">
            <!-- the user's existing tags as one-tap suggestions -->
            <div th:if="${!tagSuggestions.isEmpty()}" class="flex flex-wrap gap-1.5">
                <button th:each="tag : ${tagSuggestions}" type="button"
                        th:text="${tag.name}" th:data-tag="${tag.name}"
                        class="tag-suggestion rounded-full bg-zinc-800 px-2.5 py-1 text-xs text-zinc-300
                               transition hover:bg-zinc-700">kupplung</button>
            </div>
        </label>

        <div class="grid grid-cols-2 gap-3">
            <label class="flex flex-col gap-2">
                <span class="text-sm text-zinc-400">Einbau bei km</span>
                <input type="number" name="installedAtKm" required step="any" min="0" inputmode="decimal"
                       th:value="${part != null} ? ${part.installedAtKm} : ${currentKm}"
                       class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3 tabular-nums
                              text-zinc-100 caret-accent outline-none">
            </label>
            <label class="flex flex-col gap-2">
                <span class="text-sm text-zinc-400">Einbau am</span>
                <input type="date" name="installedOn" required
                       th:value="${part != null} ? ${part.installedOn} : ${today}"
                       class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3
                              text-zinc-100 caret-accent outline-none">
            </label>
        </div>

        <label class="flex flex-col gap-2">
            <span class="text-sm text-zinc-400">Preis (ganze Euro, optional)</span>
            <input type="number" name="priceEuro" step="1" min="0" inputmode="numeric"
                   th:value="${part?.priceEuro}"
                   placeholder="z.B. 250"
                   class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3 tabular-nums
                          text-zinc-100 caret-accent outline-none placeholder:text-zinc-600">
        </label>

        <label class="flex flex-col gap-2">
            <span class="text-sm text-zinc-400">Details</span>
            <textarea name="details" rows="6"
                      placeholder="Hersteller, Teilenummer, Bezugsquelle, Notizen …"
                      class="rounded-xl border-b-2 border-accent bg-zinc-900 px-4 py-3
                             text-zinc-100 caret-accent outline-none placeholder:text-zinc-600"
                      th:text="${part?.details}"></textarea>
        </label>

        <!-- checkpoint rows: parallel arrays checkpointOffsetKm[] / checkpointLabel[];
             editing lists the OPEN checkpoints (done ones are kept server-side) -->
        <fieldset class="flex flex-col gap-2">
            <legend class="mb-1 text-sm text-zinc-400">Wartungspunkte (nach X km)</legend>
            <div id="checkpoint-rows" class="flex flex-col gap-2">
                <th:block th:if="${part != null}">
                    <div th:each="cp : ${part.checkpoints}" th:if="${cp.open}" class="flex gap-2">
                        <input type="number" name="checkpointOffsetKm" step="any" min="1" inputmode="decimal"
                               th:value="${cp.offsetKm}" placeholder="nach km"
                               class="w-28 rounded-xl bg-zinc-900 px-3 py-2.5 tabular-nums text-zinc-100 outline-none placeholder:text-zinc-600">
                        <input type="text" name="checkpointLabel" th:value="${cp.label}" placeholder="was ist zu tun?"
                               class="min-w-0 flex-1 rounded-xl bg-zinc-900 px-3 py-2.5 text-zinc-100 outline-none placeholder:text-zinc-600">
                    </div>
                </th:block>
                <div class="flex gap-2">
                    <input type="number" name="checkpointOffsetKm" step="any" min="1" inputmode="decimal"
                           placeholder="nach km"
                           class="w-28 rounded-xl bg-zinc-900 px-3 py-2.5 tabular-nums text-zinc-100 outline-none placeholder:text-zinc-600">
                    <input type="text" name="checkpointLabel" placeholder="was ist zu tun?"
                           class="min-w-0 flex-1 rounded-xl bg-zinc-900 px-3 py-2.5 text-zinc-100 outline-none placeholder:text-zinc-600">
                </div>
            </div>
            <button type="button" id="add-checkpoint-row"
                    class="self-start rounded-lg px-1 py-1 text-sm font-medium text-accent transition hover:text-accent-bright">
                + weiterer Punkt
            </button>
        </fieldset>

        <div class="mt-auto flex gap-3">
            <a th:href="@{/vehicles/{v}/parts(v=${vehicle.id})}"
               class="flex-1 rounded-xl bg-zinc-800 px-4 py-4 text-center text-base font-semibold text-zinc-200
                      transition active:scale-[0.98] hover:bg-zinc-700">
                Abbrechen
            </a>
            <button type="submit"
                    class="flex-1 rounded-xl bg-accent px-4 py-4 text-base font-semibold text-accent-fg
                           transition active:scale-[0.98] hover:bg-accent-bright
                           focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-bright">
                Speichern
            </button>
        </div>
    </form>

    <!-- delete lives outside the main form (nested forms are invalid HTML) -->
    <form th:if="${part != null}" th:action="@{/vehicles/{v}/parts/{id}/delete(v=${vehicle.id},id=${part.id})}"
          method="post" class="mt-3"
          onsubmit="return confirm('Teil wirklich löschen?')">
        <button type="submit"
                class="w-full rounded-xl px-4 py-3 text-sm font-medium text-rose-400/80 transition hover:text-rose-300">
            Teil löschen
        </button>
    </form>

    <script>
        // clone the last (empty) checkpoint row on demand — no framework needed
        document.getElementById('add-checkpoint-row').addEventListener('click', () => {
            const rows = document.getElementById('checkpoint-rows');
            const clone = rows.lastElementChild.cloneNode(true);
            clone.querySelectorAll('input').forEach(input => input.value = '');
            rows.appendChild(clone);
        });
        // tap a suggestion chip to append that tag to the comma-separated input
        document.querySelectorAll('.tag-suggestion').forEach(chip => {
            chip.addEventListener('click', () => {
                const input = document.getElementById('tags-input');
                const names = input.value.split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
                if (!names.includes(chip.dataset.tag)) names.push(chip.dataset.tag);
                input.value = names.join(', ');
            });
        });
    </script>

</main>
</body>
</html>
```

- [ ] **Step 5: Tests laufen lassen — müssen grün sein**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: PASS.

- [ ] **Step 6: Alle Tests + Commit**

```bash
./mvnw test
git add src/main/kotlin/org/unividuell/mobility/manager/parts/ src/main/resources/templates/parts/ src/test/kotlin/org/unividuell/mobility/manager/parts/
git commit -m "feat(parts): create/edit form with tags, price and checkpoint rows"
```

---

### Task 9: Abhaken-Endpoint

**Files:**
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartController.kt`
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt` (ergänzen)

**Interfaces:**
- Consumes: `PartService.checkOff` (Task 6); das Formular aus Task 7 postet `doneOn` (Pflicht) und `doneAtKm` (optional) auf `POST /vehicles/{vehicleId}/parts/{partId}/checkpoints/{checkpointId}/done`.
- Produces: genau diese Route; Redirect zurück zur Teile-Seite.

- [ ] **Step 1: Failing Tests schreiben** — in `PartControllerIntegrationTest` ergänzen:

```kotlin
    @Test
    fun `checking off posts the confirmed values and redirects to the list`() {
        val created = createClutch()
        val checkpoint = created.checkpoints.single()

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}/checkpoints/${checkpoint.id}/done") {
            with(login())
            param("doneOn", "2026-07-20")
            param("doneAtKm", "19250")
        }.andExpect { status { is3xxRedirection() } }

        val done = parts.findById(created.id!!).orElseThrow().checkpoints.single()
        done.doneOn shouldBe LocalDate.of(2026, 7, 20)
        done.doneAtKm shouldBe 19_250.0
        // done checkpoints leave the due list
        mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }
            .andReturn().response.contentAsString shouldNotContain "Abhaken"
    }

    @Test
    fun `checking off without a reading stores only the date`() {
        val created = createClutch()
        val checkpoint = created.checkpoints.single()

        mockMvc.post("/vehicles/$vehicleId/parts/${created.id}/checkpoints/${checkpoint.id}/done") {
            with(login())
            param("doneOn", "2026-07-20")
        }.andExpect { status { is3xxRedirection() } }

        val done = parts.findById(created.id!!).orElseThrow().checkpoints.single()
        done.doneOn shouldBe LocalDate.of(2026, 7, 20)
        done.doneAtKm shouldBe null
    }

    @Test
    fun `checking off a foreign vehicle's checkpoint 404s`() {
        val strangerId = users.upsert(2222L, login = "stranger", displayName = "Stranger").id!!
        val foreignVehicle = vehicleService.create(strangerId, "Fremd", "#f43f5e").id!!
        val foreignPart = partService.create(
            strangerId, foreignVehicle, name = "Fremdteil", details = null, priceEuro = null,
            installedAtKm = 1.0, installedOn = LocalDate.of(2026, 7, 1),
            tagNames = emptyList(), checkpoints = listOf(PartService.CheckpointInput(100.0, "X")),
        )

        mockMvc.post("/vehicles/$foreignVehicle/parts/${foreignPart.id}/checkpoints/${foreignPart.checkpoints.single().id}/done") {
            with(login())
            param("doneOn", "2026-07-20")
        }.andExpect { status { isNotFound() } }
    }
```

Hinweis: `createClutch()` in diesem Test legt genau EINEN Checkpoint an („Kontrolle") — `checkpoints.single()` passt.

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: FAIL (405/404 auf der done-Route).

- [ ] **Step 3: Endpoint ergänzen**

```kotlin
    @PostMapping("/{partId}/checkpoints/{checkpointId}/done")
    fun checkOff(
        @AuthenticationPrincipal principal: OAuth2User,
        @PathVariable vehicleId: Long,
        @PathVariable partId: Long,
        @PathVariable checkpointId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) doneOn: LocalDate,
        @RequestParam(required = false) doneAtKm: Double?,
    ): String {
        val userId = currentUser.require(principal).id!!
        service.checkOff(userId, vehicleId, partId, checkpointId, doneOn, doneAtKm)
        return "redirect:/vehicles/$vehicleId/parts"
    }
```

- [ ] **Step 4: Tests laufen lassen — müssen grün sein**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: PASS.

- [ ] **Step 5: Alle Tests + Commit**

```bash
./mvnw test
git add src/main/kotlin/org/unividuell/mobility/manager/parts/PartController.kt src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt
git commit -m "feat(parts): confirmable check-off endpoint"
```

---

### Task 10: Ersetzen-Flow Ende-zu-Ende

**Files:**
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt` (ergänzen)

**Interfaces:**
- Consumes: `GET /vehicles/{v}/parts/new?replaces={id}` (Task 8 rendert Vorbefüllung + hidden `replacesPartId`), `POST /vehicles/{v}/parts` mit `replacesPartId` → `PartService.replace` (Task 6). Es fehlt kein Produktionscode — dieser Task verifiziert den zusammengesteckten Flow.

- [ ] **Step 1: End-zu-End-Tests schreiben** — in `PartControllerIntegrationTest` ergänzen:

```kotlin
    @Test
    fun `replace form is prefilled from the predecessor and carries the hidden id`() {
        val old = createClutch()

        val body = mockMvc.get("/vehicles/$vehicleId/parts/new") {
            with(login())
            param("replaces", old.id.toString())
        }.andReturn().response.contentAsString

        body shouldContain "ersetzt"
        body shouldContain "Kupplung Sachs"                      // prefilled name
        body shouldContain """name="replacesPartId""""
        body shouldContain """value="${old.id}""""
    }

    @Test
    fun `posting with replacesPartId retires the old part and shows both on the page`() {
        val old = createClutch()

        mockMvc.post("/vehicles/$vehicleId/parts") {
            with(login())
            param("name", "Kupplung LUK")
            param("installedAtKm", "25000")
            param("installedOn", "2027-01-15")
            param("tags", "kupplung")
            param("replacesPartId", old.id.toString())
        }.andExpect { status { is3xxRedirection() } }

        val body = mockMvc.get("/vehicles/$vehicleId/parts") { with(login()) }.andReturn().response.contentAsString
        body shouldContain "Kupplung LUK"                        // active card
        body shouldContain "Historie"                            // retired section appeared
        body shouldContain "ersetzt durch Kupplung LUK"
        parts.findById(old.id!!).orElseThrow().active shouldBe false
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen grün sein (kein neuer Produktionscode)**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: PASS. Falls FAIL: Lücke zwischen Task 8/6 schließen (z.B. hidden field oder Vorbefüllung vergessen).

- [ ] **Step 3: Alle Tests + Commit**

```bash
./mvnw test
git add src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt
git commit -m "test(parts): replace flow end-to-end"
```

---

### Task 11: Überfällig-Badge im Header

**Files:**
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/GlobalModelAdvice.kt`
- Modify: `src/main/kotlin/org/unividuell/mobility/manager/parts/PartService.kt` (eine Methode)
- Modify: `src/main/resources/templates/fragments/header.html`
- Test: `src/test/kotlin/org/unividuell/mobility/manager/parts/PartControllerIntegrationTest.kt` (ergänzen)

**Interfaces:**
- Consumes: `DueCalculator.openItems`, `FuelService.currentKm`, `VehicleContext.current` (bestehend).
- Produces: `PartService.overdueCount(vehicle: Vehicle): Int` (ohne Ownership-Check — der Kontext ist schon user-gefiltert); Model-Attribut `overdueCount: Int` auf jeder Seite; Badge im Header verlinkt auf `/vehicles/{id}/parts`.

- [ ] **Step 1: Failing Test schreiben** — in `PartControllerIntegrationTest` ergänzen (Session-Cookie-Muster aus `VehicleControllerIntegrationTest.select`):

```kotlin
    @Test
    fun `header badge appears on any page once the selected vehicle has overdue items`() {
        createClutch() // due at 19_223
        fuelEntries.save(
            org.unividuell.mobility.manager.fuel.FuelEntry(
                vehicleId = vehicleId, date = LocalDate.of(2026, 8, 1),
                liters = 5.0, pricePerLiter = 1.7, odometer = 19_300.0,
            ),
        )
        // select the vehicle as the session context; carry the SESSION cookie on
        val select = mockMvc.post("/vehicles/$vehicleId/select") { with(login()) }.andReturn()
        val sessionCookie = select.response.getCookie("SESSION")!!

        val body = mockMvc.get("/vehicles") {
            with(login())
            cookie(sessionCookie)
        }.andReturn().response.contentAsString

        body shouldContain """data-testid="overdue-badge""""
        body shouldContain "/vehicles/$vehicleId/parts"
    }

    @Test
    fun `header badge stays away without overdue items`() {
        createClutch() // no fuel data -> unknown km -> no verdict, no badge
        val select = mockMvc.post("/vehicles/$vehicleId/select") { with(login()) }.andReturn()
        val sessionCookie = select.response.getCookie("SESSION")!!

        val body = mockMvc.get("/vehicles") {
            with(login())
            cookie(sessionCookie)
        }.andReturn().response.contentAsString

        body shouldNotContain """data-testid="overdue-badge""""
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: FAIL (kein Badge im Markup).

- [ ] **Step 3: `PartService.overdueCount` ergänzen** (Import `org.unividuell.mobility.manager.vehicle.Vehicle`):

```kotlin
    /**
     * How many checkpoints of the vehicle are overdue right now — feeds the
     * header badge. No ownership check: callers pass an already-authorised vehicle.
     */
    fun overdueCount(vehicle: Vehicle): Int =
        DueCalculator.openItems(
            parts.findAllByVehicleId(vehicle.id!!),
            fuelService.currentKm(vehicle),
        ).count { it.overdue }
```

- [ ] **Step 4: `GlobalModelAdvice` erweitern** — Konstruktor bekommt `private val partService: PartService` (Import `org.unividuell.mobility.manager.parts.PartService`); in `populate` ergänzen:

```kotlin
        // parts with overdue maintenance mark the vehicle in the shared header
        model.addAttribute("overdueCount", vehicle?.let { partService.overdueCount(it) } ?: 0)
```

- [ ] **Step 5: Header-Fragment erweitern** — das Badge darf NICHT in den bestehenden Fahrzeug-`<a>` (verschachtelte Links sind invalides HTML). Es kommt als Geschwister-Element direkt NACH dem schließenden `</a>` des Fahrzeug-Links und VOR das rechte `<div class="flex items-center gap-3">`. `mr-auto` hält das `justify-between`-Layout intakt (Badge klebt am linken Block, der rechte bleibt rechts):

```html
    <!-- overdue maintenance on the selected vehicle: red badge -> parts page -->
    <a th:if="${selectedVehicle != null and overdueCount > 0}"
       th:href="@{/vehicles/{id}/parts(id=${selectedVehicle.id})}"
       data-testid="overdue-badge"
       th:text="${overdueCount}"
       aria-label="Überfällige Wartung" title="Überfällige Wartung"
       class="-ml-1 mr-auto flex h-5 min-w-5 items-center justify-center rounded-full bg-rose-500/90 px-1.5
              text-xs font-bold tabular-nums text-white">1</a>
```

- [ ] **Step 6: Tests laufen lassen — müssen grün sein**

Run: `./mvnw test -Dtest='PartControllerIntegrationTest'`
Expected: PASS.

- [ ] **Step 7: Alle Tests + Commit**

```bash
./mvnw test
git add src/main/kotlin/org/unividuell/mobility/manager/ src/main/resources/templates/fragments/header.html src/test/kotlin/org/unividuell/mobility/manager/parts/
git commit -m "feat(parts): overdue badge next to the vehicle name in the header"
```

---

## Offene bewusste Entscheidungen (für Reviewer)

- Checkpoint-IDs sind über Aggregat-Saves hinweg nicht stabil (Spring Data JDBC löscht/re-inserted Kind-Zeilen). Die UI postet nur frisch gerenderte IDs; ein veralteter Tab läuft auf 404 statt auf falsche Daten. Akzeptiert.
- Das Abhaken nutzt ein `<details>`-Element statt eines htmx-Swaps — gleicher UX-Effekt (aufklappbarer Bestätigungsschritt mit editierbaren, vorbefüllten Feldern), null zusätzliche Requests/JS.
- `VehicleService` injiziert `PartRepository` (nicht `PartService`), weil `PartService` selbst `VehicleService` braucht — Bean-Zyklus vermieden. Gleiches Muster wie das bestehende `FuelEntryRepository`-Injekt.
- Die Baseline-Felder erscheinen nur im Bearbeiten-Formular (nicht beim Anlegen): eine Tacho-Ablesung „zu einem beliebigen Zeitpunkt" setzt ein existierendes Fahrzeug voraus; direkt nach dem Anlegen ist sie einen Klick entfernt.
