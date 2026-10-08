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
