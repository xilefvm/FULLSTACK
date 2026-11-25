package dev.dataclean.run;

import dev.dataclean.engine.DatasetFormat;
import dev.dataclean.engine.DatasetValidator.Issue;
import dev.dataclean.engine.DatasetValidator.Summary;
import dev.dataclean.engine.DatasetValidator.Suggestion;
import dev.dataclean.engine.DatasetValidator.ValidatedRecord;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RunStore {
    private static final String SELECT_RUN = """
            SELECT id, schema_name, schema_version, state, submitted_at, finished_at,
                   total_rows, invalid_rows, error, input_format, sheet_name, records_expires_at, attempt_started_at,
                   parent_run_id
            FROM validation_run
            """;

    public enum State { QUEUED, RUNNING, COMPLETED, FAILED }

    public record Run(UUID id, String schemaName, int schemaVersion, State state,
                      OffsetDateTime submittedAt, OffsetDateTime finishedAt,
                      Long totalRows, Long invalidRows, String error, DatasetFormat format, String sheet,
                      OffsetDateTime recordsExpiresAt, OffsetDateTime attemptStartedAt, UUID parentRunId) {}
    public record Input(String schemaName, int schemaVersion, byte[] payload, DatasetFormat format, String sheet,
                        boolean retainRecords) {}

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final ObjectMapper json;

    public RunStore(JdbcClient jdbc, JdbcTemplate template, ObjectMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    public UUID submit(String name, int version, byte[] payload, DatasetFormat format, String sheet, boolean retainRecords) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO validation_run(id, schema_name, schema_version, payload, state, input_format, sheet_name, retain_records)
                VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?)
                """).params(id, name, version, payload, format.name(), sheet, retainRecords).update();
        return id;
    }

    public Optional<Run> find(UUID id) {
        return jdbc.sql(SELECT_RUN + "WHERE id = ?").param(id).query(RunStore::mapRun).optional();
    }

    public Optional<UUID> correction(UUID parentId, String hash) {
        return jdbc.sql("SELECT id FROM validation_run WHERE parent_run_id = ? AND correction_hash = ?")
                .params(parentId, hash).query(UUID.class).optional();
    }

    public UUID submitCorrection(Run parent, String hash, byte[] payload) {
        UUID id = UUID.randomUUID();
        return jdbc.sql("""
                INSERT INTO validation_run(id, schema_name, schema_version, payload, state,
                    input_format, retain_records, parent_run_id, correction_hash)
                VALUES (?, ?, ?, ?, 'QUEUED', 'JSONL', true, ?, ?)
                ON CONFLICT (parent_run_id, correction_hash) DO NOTHING RETURNING id
                """).params(id, parent.schemaName(), parent.schemaVersion(), payload, parent.id(), hash)
                .query(UUID.class).optional().orElseGet(() -> correction(parent.id(), hash).orElseThrow());
    }

    public List<Run> recent(State state, int limit) {
        String order = " ORDER BY submitted_at DESC, id DESC LIMIT ?";
        var statement = state == null ? jdbc.sql(SELECT_RUN + order).param(limit)
                : jdbc.sql(SELECT_RUN + "WHERE state = ?" + order).params(state.name(), limit);
        return statement.query(RunStore::mapRun).list();
    }

    private static Run mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new Run(rs.getObject("id", UUID.class),
                rs.getString("schema_name"), rs.getInt("schema_version"), State.valueOf(rs.getString("state")),
                rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("total_rows", Long.class), rs.getObject("invalid_rows", Long.class),
                rs.getString("error"), DatasetFormat.valueOf(rs.getString("input_format")),
                rs.getString("sheet_name"), rs.getObject("records_expires_at", OffsetDateTime.class),
                rs.getObject("attempt_started_at", OffsetDateTime.class), rs.getObject("parent_run_id", UUID.class));
    }

    public List<Issue> preview(UUID id, int limit) {
        return jdbc.sql("""
                SELECT row_number, issue_index, column_name, code, suggestion::text
                FROM row_issue WHERE run_id = ? ORDER BY row_number, issue_index LIMIT ?
                """).params(id, limit).query((rs, n) ->
                new Issue(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5) == null ? null : json.readValue(rs.getString(5), Suggestion.class))).list();
    }

    public List<UUID> pending() {
        return jdbc.sql("""
                SELECT id FROM validation_run WHERE state IN ('QUEUED', 'RUNNING')
                ORDER BY submitted_at, id LIMIT 64
                """).query(UUID.class).list();
    }

    public boolean markRunning(UUID id) {
        return jdbc.sql("""
                UPDATE validation_run SET state = 'RUNNING', attempt_started_at = clock_timestamp()
                WHERE id = ? AND state IN ('QUEUED', 'RUNNING')
                """).param(id).update() == 1;
    }

    public long countByState(State state) {
        return jdbc.sql("SELECT count(*) FROM validation_run WHERE state = ?")
                .param(state.name()).query(Long.class).single();
    }

    public double oldestQueuedSeconds() {
        return jdbc.sql("""
                SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(submitted_at)), 0)::double precision
                FROM validation_run WHERE state = 'QUEUED'
                """).query(Double.class).single();
    }

    public double oldestRunningSeconds() {
        return jdbc.sql("""
                SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(COALESCE(attempt_started_at, submitted_at))), 0)::double precision
                FROM validation_run WHERE state = 'RUNNING'
                """).query(Double.class).single();
    }

    public Optional<Input> lockInput(UUID id) {
        return jdbc.sql("""
                SELECT schema_name, schema_version, payload, input_format, sheet_name, retain_records
                FROM validation_run WHERE id = ? AND state = 'RUNNING' FOR UPDATE
                """).param(id).query((rs, n) -> new Input(rs.getString(1), rs.getInt(2), rs.getBytes(3),
                DatasetFormat.valueOf(rs.getString(4)), rs.getString(5), rs.getBoolean(6))).optional();
    }

    public void insertIssues(UUID id, List<Issue> issues) {
        template.batchUpdate("""
                INSERT INTO row_issue(run_id, row_number, issue_index, column_name, code, suggestion)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, issues, 500, (statement, issue) -> {
            statement.setObject(1, id);
            statement.setLong(2, issue.row());
            statement.setInt(3, issue.index());
            statement.setString(4, issue.column());
            statement.setString(5, issue.code());
            statement.setString(6, issue.suggestion() == null ? null : json.writeValueAsString(issue.suggestion()));
        });
    }

    public void complete(UUID id, Summary summary) {
        jdbc.sql("""
                UPDATE validation_run SET state = 'COMPLETED', finished_at = clock_timestamp(),
                    total_rows = ?, invalid_rows = ?, payload = ''::bytea,
                    records_expires_at = CASE WHEN retain_records THEN clock_timestamp() + interval '7 days' END
                WHERE id = ?
                """).params(summary.totalRows(), summary.invalidRows(), id).update();
    }

    public void insertRecords(UUID id, List<ValidatedRecord> records) {
        template.batchUpdate("""
                INSERT INTO validated_record(run_id, row_number, valid, data) VALUES (?, ?, ?, ?)
                """, records, 500, (statement, record) -> {
            statement.setObject(1, id);
            statement.setLong(2, record.row());
            statement.setBoolean(3, record.valid());
            statement.setString(4, json.writeValueAsString(record.data()));
        });
    }

    public void purgeExpiredRecords() {
        jdbc.sql("""
                DELETE FROM validated_record record USING validation_run run
                WHERE record.run_id = run.id AND run.records_expires_at <= now()
                """).update();
    }

    public record RetainedRow(long number, boolean valid, String data) {}

    @Transactional(readOnly = true)
    public void streamAllRecords(UUID id, Consumer<RetainedRow> sink) {
        template.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT row_number, valid, data FROM validated_record
                    WHERE run_id = ? ORDER BY row_number
                    """);
            statement.setObject(1, id);
            statement.setFetchSize(500);
            return statement;
        }, rs -> {
            sink.accept(new RetainedRow(rs.getLong(1), rs.getBoolean(2), rs.getString(3)));
        });
    }

    @Transactional(readOnly = true)
    public void streamRecords(UUID id, boolean valid, Consumer<String> sink) {
        template.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT row_number, data FROM validated_record
                    WHERE run_id = ? AND valid = ? ORDER BY row_number
                    """);
            statement.setObject(1, id);
            statement.setBoolean(2, valid);
            statement.setFetchSize(500);
            return statement;
        }, rs -> {
            String data = rs.getString(2);
            sink.accept(valid ? data : "{\"row\":" + rs.getLong(1) + ",\"data\":" + data + "}");
        });
    }

    public void fail(UUID id, String message) {
        jdbc.sql("""
                UPDATE validation_run SET state = 'FAILED', finished_at = now(),
                    error = ?, payload = ''::bytea WHERE id = ? AND state = 'RUNNING'
                """).params(message, id).update();
    }

    @Transactional(readOnly = true)
    public void streamIssues(UUID id, Consumer<Issue> sink) {
        template.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT row_number, issue_index, column_name, code
                    FROM row_issue WHERE run_id = ? ORDER BY row_number, issue_index
                    """);
            statement.setObject(1, id);
            statement.setFetchSize(500);
            return statement;
        }, rs -> {
            sink.accept(new Issue(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4)));
        });
    }
}
