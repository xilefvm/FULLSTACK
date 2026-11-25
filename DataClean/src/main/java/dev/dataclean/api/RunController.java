package dev.dataclean.api;

import dev.dataclean.engine.DatasetFormat;
import dev.dataclean.engine.DatasetValidator.Issue;
import dev.dataclean.run.RunStore;
import dev.dataclean.run.CorrectionService;
import dev.dataclean.run.RunStore.Run;
import dev.dataclean.run.RunStore.State;
import dev.dataclean.schema.SchemaStore;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.GONE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

@RestController
@RequestMapping("/api/runs")
class RunController {
    private final SchemaStore schemas;
    private final RunStore runs;
    private final CorrectionService corrections;

    RunController(SchemaStore schemas, RunStore runs, CorrectionService corrections) {
        this.schemas = schemas;
        this.runs = runs;
        this.corrections = corrections;
    }

    record Submission(UUID id) {}
    record CorrectionRequest(List<CorrectionService.Edit> edits) {}

    @PostMapping("/{id}/corrections")
    ResponseEntity<Submission> correct(@PathVariable UUID id, @RequestBody CorrectionRequest request) {
        UUID child = corrections.submit(id, request.edits());
        return ResponseEntity.accepted().location(URI.create("/api/runs/" + child)).body(new Submission(child));
    }

    @PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Submission> submit(@RequestParam String schemaName, @RequestParam int schemaVersion,
                                      @RequestParam(defaultValue = "CSV") DatasetFormat format,
                                      @RequestParam(required = false) String sheet,
                                      @RequestParam(defaultValue = "false") boolean retainRecords,
                                      @RequestPart MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "Dataset file is empty");
        if (sheet != null && (format != DatasetFormat.XLSX || sheet.isBlank()
                || sheet.length() > 31 || sheet.indexOf(0) >= 0)) {
            throw new ResponseStatusException(BAD_REQUEST, "Sheet must be a valid XLSX sheet name");
        }
        if (schemas.find(schemaName, schemaVersion).isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Schema version not found");
        }
        UUID id = runs.submit(schemaName, schemaVersion, file.getBytes(), format, sheet, retainRecords);
        return ResponseEntity.accepted().location(URI.create("/api/runs/" + id)).body(new Submission(id));
    }

    @GetMapping("/{id}")
    Run status(@PathVariable UUID id) {
        return runs.find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Run not found"));
    }

    @GetMapping
    List<Run> recent(@RequestParam(required = false) State state,
                     @RequestParam(defaultValue = "25") int limit) {
        return runs.recent(state, checkedLimit(limit));
    }

    @GetMapping("/{id}/issues")
    List<Issue> preview(@PathVariable UUID id, @RequestParam(defaultValue = "20") int limit) {
        requireCompleted(id);
        return runs.preview(id, checkedLimit(limit));
    }

    private int checkedLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "Limit must be between 1 and 100");
        }
        return limit;
    }

    private Run requireCompleted(UUID id) {
        Run run = status(id);
        if (run.state() != State.COMPLETED) {
            throw new ResponseStatusException(CONFLICT, "Results are available after successful processing");
        }
        return run;
    }

    @GetMapping(value = "/{id}/records/{partition}.jsonl", produces = "application/x-ndjson")
    ResponseEntity<StreamingResponseBody> records(@PathVariable UUID id, @PathVariable String partition) {
        if (!partition.equals("valid") && !partition.equals("rejected") && !partition.equals("corrected")) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown record partition");
        }
        var run = requireCompleted(id);
        if (partition.equals("corrected") && run.parentRunId() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Run is not a correction");
        }
        var expiresAt = run.recordsExpiresAt();
        if (expiresAt == null) throw new ResponseStatusException(NOT_FOUND, "Row retention was not requested");
        if (!expiresAt.isAfter(OffsetDateTime.now())) throw new ResponseStatusException(GONE, "Retained rows have expired");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header("Content-Disposition", "attachment; filename=\"" + partition + "-" + id + ".jsonl\"")
                .body(output -> {
                    var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                    java.util.function.Consumer<String> writeLine = line -> {
                        try {
                            writer.write(line);
                            writer.write('\n');
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    };
                    if (partition.equals("corrected")) runs.streamAllRecords(id, row -> writeLine.accept(row.data()));
                    else runs.streamRecords(id, partition.equals("valid"), writeLine);
                    writer.flush();
                });
    }

    @GetMapping(value = "/{id}/issues.csv", produces = "text/csv;charset=UTF-8")
    ResponseEntity<StreamingResponseBody> export(@PathVariable UUID id) {
        requireCompleted(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"issues-" + id + ".csv\"")
                .body(output -> {
                    var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                    var csv = new CSVPrinter(writer, CSVFormat.RFC4180);
                    csv.printRecord("row", "column", "code");
                    runs.streamIssues(id, issue -> {
                        try {
                            csv.printRecord(issue.row(), issue.column(), issue.code());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    csv.flush();
                });
    }
}
