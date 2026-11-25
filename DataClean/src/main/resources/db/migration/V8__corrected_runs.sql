ALTER TABLE validation_run
    ADD COLUMN parent_run_id uuid REFERENCES validation_run(id),
    ADD COLUMN correction_hash text,
    ADD CONSTRAINT correction_has_parent CHECK ((parent_run_id IS NULL) = (correction_hash IS NULL)),
    ADD CONSTRAINT correction_is_idempotent UNIQUE (parent_run_id, correction_hash);

CREATE INDEX validated_record_in_input_order ON validated_record (run_id, row_number);
