package dev.dataclean.run;

import dev.dataclean.schema.SchemaDefinition;
import dev.dataclean.schema.SchemaStore;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CorrectionService {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private final RunStore runs;
    private final SchemaStore schemas;
    private final ObjectMapper json;

    public record Edit(long row, String column, String value) {}

    public CorrectionService(RunStore runs, SchemaStore schemas, ObjectMapper json) {
        this.runs = runs;
        this.schemas = schemas;
        this.json = json;
    }

    @Transactional
    public UUID submit(UUID originalId, List<Edit> edits) {
        var parent = runs.find(originalId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        var schema = schemas.find(parent.schemaName(), parent.schemaVersion()).orElseThrow();
        var byRow = checkEdits(edits, schema);
        var ordered = edits.stream().sorted(Comparator.comparingLong(Edit::row).thenComparing(Edit::column)).toList();
        String digest = digest(json.writeValueAsBytes(ordered));
        return runs.correction(originalId, digest).orElseGet(() -> prepare(parent, digest, byRow));
    }

    private UUID prepare(RunStore.Run parent, String digest, Map<Long, Map<String, String>> edits) {
        if (parent.state() != RunStore.State.COMPLETED || parent.recordsExpiresAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed run with retained records required");
        }
        if (!parent.recordsExpiresAt().isAfter(OffsetDateTime.now())) throw expired();
        var payload = new ByteArrayOutputStream();
        long[] count = {0};
        runs.streamAllRecords(parent.id(), row -> {
            count[0]++;
            var changes = edits.remove(row.number());
            if (!(json.readTree(row.data()) instanceof ObjectNode record)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Row " + row.number() + " has a structural error; correct the source file first");
            }
            if (changes != null) {
                if (row.valid()) throw new IllegalArgumentException("Only rejected rows can be edited");
                changes.forEach(record::put);
            }
            byte[] line = json.writeValueAsBytes(record);
            if (payload.size() + (long) line.length + 1 > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The corrected JSONL exceeds 20 MiB");
            }
            payload.writeBytes(line);
            payload.write('\n');
        });
        if (count[0] != parent.totalRows()) throw expired();
        if (!edits.isEmpty()) throw new IllegalArgumentException("An edited row does not exist");
        return runs.submitCorrection(parent, digest, payload.toByteArray());
    }

    private Map<Long, Map<String, String>> checkEdits(List<Edit> edits, SchemaDefinition schema) {
        if (edits == null || edits.isEmpty() || edits.size() > 1000) {
            throw new IllegalArgumentException("Submit between 1 and 1000 cell edits");
        }
        var columns = schema.columns().stream().map(SchemaDefinition.Column::name).collect(Collectors.toSet());
        Map<Long, Map<String, String>> byRow = new HashMap<>();
        for (var edit : edits) {
            if (edit == null || edit.row() < 1 || !columns.contains(edit.column())
                    || edit.value() == null || edit.value().length() > 4096 || edit.value().indexOf(0) >= 0) {
                throw new IllegalArgumentException("Each edit requires a row, a known column and a value of up to 4096 characters");
            }
            if (byRow.computeIfAbsent(edit.row(), ignored -> new HashMap<>())
                    .putIfAbsent(edit.column(), edit.value()) != null) {
                throw new IllegalArgumentException("An edited cell appears more than once");
            }
        }
        return byRow;
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.GONE, "Retained rows have expired");
    }
}
