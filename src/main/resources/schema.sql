CREATE TABLE IF NOT EXISTS mdm_golden_record (
    id                TEXT        PRIMARY KEY,
    global_pid        TEXT        NOT NULL,
    global_cid        TEXT        NOT NULL,
    mastered_date_ts  TIMESTAMPTZ NOT NULL,
    created_date      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_date      TIMESTAMPTZ NOT NULL DEFAULT now(),
    golden_record     JSONB       NOT NULL
);
