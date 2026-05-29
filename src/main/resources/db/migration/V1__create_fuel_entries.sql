CREATE TABLE fuel_entries (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    liters          REAL    NOT NULL,
    price_per_liter REAL    NOT NULL,
    kilometers      REAL    NOT NULL,
    created_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
