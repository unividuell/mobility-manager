# Teile-Tracking mit Wartungspunkten — Design

Datum: 2026-07-11
Status: entworfen, vom User abgenommen (Brainstorming-Session)

## Ziel

Tracken, wann und bei welchem Gesamt-km-Stand Teile in ein Fahrzeug eingebaut
wurden (z.B. „neue Kupplung bei 19.123 km am 11.07.2026"). Teile tragen Name,
user-weite Tags und eine Details-Textarea. An jedem Teil hängen km-basierte
Wartungspunkte („nach 100 km kontrollieren", „nach 15.000 km Beläge prüfen"),
die in einer Fälligkeitsübersicht pro Fahrzeug erscheinen und abgehakt werden
können. Überfällige Punkte markieren das Fahrzeug im App-Header. Ein Teil kann
durch ein neues ersetzt (retired) werden; die Historie bleibt einsehbar.

## Entscheidungen aus dem Brainstorming

- **km-Stand-Ermittlung:** aus Tankdaten abgeleitet (keine manuelle Pflege).
- **Wartungspunkte:** einmalig, relativ zum Einbau-km-Stand, abhakbar.
  Wiederholung = neuen Punkt anlegen. Keine wiederkehrenden Intervalle.
- **Teil-Identität:** Pflicht-Name plus optionale Tags (user-eigene,
  fahrzeugübergreifend wiederverwendbar).
- **Retire:** Ersetzen in einem Schritt (neues Teil anlegen, altes stilllegen).
- **Übersicht:** pro Fahrzeug, keine globale Sammelansicht.
- **Abhaken:** `done_on`/`done_at_km` sind vorbefüllt, aber im Abhaken-Schritt
  editierbar — kein Ein-Klick-Abhaken.
- **Preis:** optionales Feld am Teil. Eingabe in ganzen Euro, Speicherung als
  Integer-Cent-Betrag (`price_cents`) — keine Float-Rundungsprobleme.
- **Architektur:** eigenes `parts`-Modul, `Part` als Spring-Data-JDBC-Aggregat
  (Ansatz A; verworfen: Teile im Vehicle-Aggregat, Event-Log-Modell).

## Datenmodell (Migration V6)

### Neue Tabellen

```sql
parts (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id          INTEGER NOT NULL REFERENCES vehicles(id),
    name                TEXT    NOT NULL,
    details             TEXT,                 -- Freitext (üppige Textarea)
    price_cents         INTEGER,              -- Kaufpreis in Cent, nullable
    installed_at_km     REAL    NOT NULL,     -- absoluter Gesamt-km-Stand
    installed_on        TEXT    NOT NULL,     -- ISO yyyy-MM-dd
    retired_at_km       REAL,                 -- NULL = aktiv
    retired_on          TEXT,                 -- NULL = aktiv
    replaced_by_part_id INTEGER REFERENCES parts(id),  -- Nachfolger, nullable
    created_at          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
)

part_checkpoints (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    part_id     INTEGER NOT NULL REFERENCES parts(id),
    offset_km   REAL    NOT NULL,   -- fällig bei installed_at_km + offset_km
    label       TEXT    NOT NULL,   -- z.B. „Beläge prüfen"
    done_on     TEXT,               -- NULL = offen
    done_at_km  REAL                -- NULL = offen
)

tags (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    name    TEXT    NOT NULL,       -- getrimmt, lowercase
    UNIQUE (user_id, name)
)

part_tags (
    part_id INTEGER NOT NULL REFERENCES parts(id),
    tag_id  INTEGER NOT NULL REFERENCES tags(id),
    PRIMARY KEY (part_id, tag_id)
)
```

### Erweiterung `vehicles`

- `baseline_km REAL` (nullable)
- `baseline_on TEXT` (nullable, ISO-Datum)

Die Baseline ist ein **Anker zu einem beliebigen Zeitpunkt** im Leben des
Fahrzeugs: der User liest den Tacho ab und trägt Wert + Datum ein — auch wenn
schon beliebig viele Trip-Meter-Tankungen erfasst sind. Beide Felder werden im
bestehenden Fahrzeug-Formular gepflegt (nur bei Trip-Meter-Fahrzeugen
eingeblendet, Datum default heute). Erneutes Setzen überschreibt beide Werte
und korrigiert damit aufgelaufene Drift.

### Aggregate (Spring Data JDBC)

- `Part` ist das Aggregat: `PartCheckpoint`s und Tag-Referenzen (`PartTag`)
  hängen als `@MappedCollection` daran (Muster: `VehicleManager` am `Vehicle`).
- `Tag` ist ein eigenes, user-eigenes Aggregat.

## Aktueller Gesamt-km-Stand

Neue Berechnungsfunktion (im `fuel`-Modul, vom `parts`-Modul konsumiert):

- **Total-only-Fahrzeug:** höchster erfasster `odometer`-Wert.
- **Trip-Meter-Fahrzeug:** `baseline_km + Σ(kilometers)` aller Tankeinträge
  mit `date` **strikt nach** `baseline_on`. Trips am oder vor dem Baseline-Tag
  stecken bereits im abgelesenen Tachowert. (Bewusste Unschärfe: eine Tankung
  am Baseline-Tag *nach* der Ablesung fällt unter den Tisch — die Regel bleibt
  dafür eindeutig, und ein Neu-Setzen der Baseline korrigiert alles.)
- **Unbekannt** (Trip-Meter ohne Baseline, oder keine Daten): Checkpoints
  werden ohne fällig/überfällig-Bewertung angezeigt, mit Hinweis auf das
  Baseline-Feld. Keine Header-Markierung.

Ein offener Checkpoint ist **überfällig**, wenn
`aktueller km-Stand ≥ installed_at_km + offset_km`, sonst **anstehend**
(Anzeige der Rest-km).

## Seiten & Abläufe

### Teile-Seite `/vehicles/{vehicleId}/parts`

Verlinkt von der Fahrzeug-Übersicht (analog Tank-Liste). Drei Blöcke:

1. **Fälligkeit:** offene Checkpoints aktiver Teile. Überfällige zuerst (rot,
   „+X km drüber"), dann anstehende nach Rest-km sortiert. Pro Eintrag:
   Teil-Name, Label, Fällig-bei-km, Rest-km/Überzug, Abhaken-Button.
2. **Aktive Teile:** Karten mit Name, Tags, Einbau-km/-Datum, Preis (falls
   erfasst), gelaufene km seit Einbau, Details-Auszug. Aktionen: Bearbeiten,
   Ersetzen.
3. **Historie:** stillgelegte Teile dezent/eingeklappt, mit Laufleistung
   (Ausbau-km − Einbau-km) und Verweis auf den Nachfolger.

### Abhaken

Der Abhaken-Button klappt per htmx ein Inline-Formular auf (Panel-Swap-Muster
der App) mit zwei vorbefüllten, **editierbaren** Feldern: „Erledigt am"
(default heute) und „Erledigt bei km" (default aktueller Fahrzeug-km-Stand;
leer bei unbekanntem Stand) plus Bestätigen/Abbrechen. Erst Bestätigen
schreibt `done_on`/`done_at_km`.

### Teil anlegen/bearbeiten

Eigene Formular-Seite (Muster `vehicles/form.html`): Name, Tags, Details
(Textarea), Preis (optional, ganze Euro), Einbau-km (vorbefüllt mit dem
aktuellen Fahrzeug-km-Stand, leer falls unbekannt),
Einbau-Datum (default heute), Checkpoint-Zeilen (Offset-km + Label, „+ weiterer
Punkt" per kleinem Inline-Script). Checkpoints sind auch am bestehenden Teil
nachträglich ergänzbar.

### Tags-Eingabe

Textfeld mit Komma-Trennung plus klickbare, server-seitig gerenderte
Vorschläge aus den bestehenden Tags des Users (kein Autocomplete-Endpoint).
Unbekannte Tags werden beim Speichern angelegt.

### Ersetzen-Flow

„Ersetzen" am aktiven Teil öffnet das Anlege-Formular, vorbefüllt mit Name +
Tags des alten Teils und Hinweis „ersetzt {alter Name}". Speichern läuft in
einer Transaktion: neues Teil anlegen; altes Teil erhält
`retired_on`/`retired_at_km` (= Einbau-Datum/-km des neuen) und
`replaced_by_part_id`. Offene Checkpoints des alten Teils bleiben in der DB,
fallen aber aus der Fälligkeitsliste (nur aktive Teile werden bewertet).

### Header-Markierung

`GlobalModelAdvice` liefert zusätzlich `overdueCount` für das gewählte
Fahrzeug (eine Query über aktive Teile + offene Checkpoints + km-Stand). Der
Header zeigt bei > 0 einen kleinen roten Punkt mit Zahl neben dem
Fahrzeugnamen, verlinkt auf die Teile-Seite.

## Fehlerfälle & Validierung

- **Zugriffsschutz:** alle Routen über `vehicleService.get(vehicleId, userId)`
  → 404 bei fremdem Fahrzeug (bestehendes Muster). Zusätzlich: Teil gehört
  zum Fahrzeug, Checkpoint gehört zum Teil (sonst 404). Tag-Vorschläge zeigen
  nur Tags des eingeloggten Users.
- **Validierung:** Name Pflicht; Einbau-km ≥ 0; Preis optional, ganze Euro
  ≥ 0; Checkpoint-Offset > 0; Label Pflicht. Tags getrimmt + lowercase, unique pro User, leere verworfen.
- **Ersetzen:** bewusst keine Prüfung „neuer Einbau-km ≥ alter Einbau-km"
  (Tachotausch soll nicht blockieren).
- **Löschkaskaden:** Fahrzeug-Löschung entfernt Teile samt Checkpoints und
  Tag-Zuordnungen (gleiche Erweiterung wie bei den Tankeinträgen). Tags selbst
  bleiben (user-eigen). Einzelnes Teil ist löschbar (Fehleingabe); Checkpoints
  und Tag-Zuordnungen kaskadieren, `replaced_by_part_id`-Verweise auf das
  gelöschte Teil werden genullt.

## Tests

- **Unit:** Fälligkeitsberechnung — Baseline-Regel (nur Trips strikt nach
  `baseline_on`), Total-only via letztem Odometer, überfällig/anstehend-
  Grenzfälle (exakt erreicht = überfällig), unbekannter Stand.
- **Integration (Repository/Service):** Aggregat-Roundtrip Teil + Checkpoints
  + Tags; Tag-Wiederverwendung über Fahrzeuge; Ersetzen-Flow transaktional;
  Kaskaden bei Fahrzeug-/Teil-Löschung.
- **Integration (Controller):** 404 bei fremden Fahrzeugen/Teilen; Abhaken mit
  überschriebenen Werten; `overdueCount` im Header-Model.
