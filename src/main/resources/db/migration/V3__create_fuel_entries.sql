CREATE TABLE fuel_entries (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    vehicle_id      INTEGER NOT NULL REFERENCES vehicles(id),
    date            TEXT    NOT NULL,   -- the day of the fueling (ISO yyyy-MM-dd)
    liters          REAL    NOT NULL,
    price_per_liter REAL    NOT NULL,
    kilometers      REAL    NOT NULL,
    created_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
