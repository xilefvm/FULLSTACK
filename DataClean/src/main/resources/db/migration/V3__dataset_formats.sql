ALTER TABLE validation_run
    ADD COLUMN input_format text NOT NULL DEFAULT 'CSV' CHECK (input_format IN ('CSV', 'JSONL', 'XLSX')),
    ADD COLUMN sheet_name text CHECK (sheet_name IS NULL OR input_format = 'XLSX');
