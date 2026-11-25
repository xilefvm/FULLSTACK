CREATE INDEX validation_run_history ON validation_run (submitted_at DESC, id DESC);
CREATE INDEX validation_run_history_by_state ON validation_run (state, submitted_at DESC, id DESC);
