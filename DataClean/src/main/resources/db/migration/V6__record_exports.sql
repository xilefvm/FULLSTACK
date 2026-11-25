ALTER TABLE validation_run
    ADD COLUMN retain_records boolean NOT NULL DEFAULT false,
    ADD COLUMN records_expires_at timestamptz;

CREATE TABLE validated_record (
    run_id uuid NOT NULL REFERENCES validation_run(id) ON DELETE CASCADE,
    row_number bigint NOT NULL,
    valid boolean NOT NULL,
    data text NOT NULL,
    PRIMARY KEY (run_id, valid, row_number)
);

CREATE INDEX validation_run_export_expiry ON validation_run (records_expires_at)
    WHERE records_expires_at IS NOT NULL;
