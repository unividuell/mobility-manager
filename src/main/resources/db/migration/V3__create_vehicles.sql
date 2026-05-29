CREATE TABLE vehicles (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    color      TEXT NOT NULL,            -- hex, e.g. "#06b6d4"
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE vehicle_managers (
    vehicle_id INTEGER NOT NULL REFERENCES vehicles(id),
    user_id    INTEGER NOT NULL REFERENCES users(id),
    PRIMARY KEY (vehicle_id, user_id)
);
