CREATE TABLE IF NOT EXISTS users (
    id         UUID PRIMARY KEY,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    data       JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS user_audit_logs (
    user_id    UUID PRIMARY KEY REFERENCES users(id),
    audit_data JSONB NOT NULL
);
