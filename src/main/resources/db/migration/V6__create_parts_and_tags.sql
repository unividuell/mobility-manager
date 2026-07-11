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
