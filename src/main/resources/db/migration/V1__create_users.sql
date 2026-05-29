CREATE TABLE users (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    github_id    INTEGER NOT NULL UNIQUE,
    login        TEXT    NOT NULL,
    display_name TEXT    NOT NULL,
    created_at   TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
