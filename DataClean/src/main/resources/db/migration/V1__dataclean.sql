CREATE TABLE schema_version (
    name text NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    definition jsonb NOT NULL,
    PRIMARY KEY (name, version)
);

CREATE TABLE validation_run (
    id uuid PRIMARY KEY,
    schema_name text NOT NULL,
    schema_version integer NOT NULL,
    payload bytea NOT NULL,
    state text NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    total_rows bigint,
    invalid_rows bigint,
    error text,
    FOREIGN KEY (schema_name, schema_version) REFERENCES schema_version(name, version)
);

CREATE INDEX validation_run_pending ON validation_run (submitted_at, id)
    WHERE state IN ('QUEUED', 'RUNNING');

CREATE TABLE row_issue (
    run_id uuid NOT NULL REFERENCES validation_run(id) ON DELETE CASCADE,
    row_number bigint NOT NULL,
    issue_index integer NOT NULL,
    column_name text NOT NULL,
    code text NOT NULL,
    PRIMARY KEY (run_id, row_number, issue_index)
);
