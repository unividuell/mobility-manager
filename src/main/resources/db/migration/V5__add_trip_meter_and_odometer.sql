-- Vehicles gain a trip-meter flag. Vehicles that already exist recorded trip
-- distances (the kilometers column), so they default to having a trip meter.
ALTER TABLE vehicles ADD COLUMN has_trip_meter INTEGER NOT NULL DEFAULT 1;

-- A fuel entry now records EITHER a trip distance (kilometers, for trip-meter
-- vehicles) OR an absolute odometer reading (odometer, for total-only vehicles).
-- For the latter the driven distance is derived from the previous reading rather
-- than stored, so a back-dated entry inserted later re-derives its neighbours'
-- distances automatically. That makes kilometers nullable — and SQLite needs a
-- table rebuild to drop a NOT NULL constraint. Nothing references fuel_entries,
-- so the drop/rename is safe with foreign keys enabled.
CREATE TABLE fuel_entries_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id      INTEGER NOT NULL REFERENCES vehicles(id),
    date            TEXT    NOT NULL,   -- the day of the fueling (ISO yyyy-MM-dd)
    liters          REAL    NOT NULL,
    price_per_liter REAL    NOT NULL,
    kilometers      REAL,               -- trip distance (trip-meter vehicles)
    odometer        REAL,               -- absolute reading (total-only vehicles)
    created_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO fuel_entries_new (id, vehicle_id, date, liters, price_per_liter, kilometers, created_at)
SELECT id, vehicle_id, date, liters, price_per_liter, kilometers, created_at
FROM fuel_entries;

DROP TABLE fuel_entries;
ALTER TABLE fuel_entries_new RENAME TO fuel_entries;
