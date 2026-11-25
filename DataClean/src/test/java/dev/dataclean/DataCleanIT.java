package dev.dataclean;

import dev.dataclean.run.RunStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "dataclean.poll-ms=50")
class DataCleanIT {
    private static final String SCHEMA = """
            {"columns":[{"name":"id","type":"INTEGER","required":true},
                        {"name":"region","type":"STRING","allowedValues":["ES","PT"]}]}
            """;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDatabase {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.7");
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RunStore runs;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void registersImmutableVersionsAndExportsAnAsynchronousValidation() throws Exception {
        var created = put("/api/schemas/customers/versions/1", SCHEMA);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(put("/api/schemas/customers/versions/1", SCHEMA).statusCode()).isEqualTo(200);
        String changed = SCHEMA.replace("\"PT\"", "\"FR\"");
        assertThat(put("/api/schemas/customers/versions/1", changed).statusCode()).isEqualTo(409);
        assertThat(put("/api/schemas/customers/versions/2", changed).statusCode()).isEqualTo(201);
        assertThat(get("/api/schemas/customers/versions/1").body()).contains("\"PT\"");
        assertThat(get("/api/schemas/customers/versions/2").body()).contains("\"FR\"");

        String run = submit("customers", 1, "id,region\n1,PT\nworld,FR\n,ES\n");
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var status = json.readTree(get(run).body());
            assertThat(status.path("state").asString()).isEqualTo("COMPLETED");
            assertThat(status.path("totalRows").asInt()).isEqualTo(3);
            assertThat(status.path("invalidRows").asInt()).isEqualTo(2);
        });
        var report = get(run + "/issues.csv");
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).isEqualTo("row,column,code\r\n2,id,TYPE\r\n2,region,NOT_ALLOWED\r\n3,id,REQUIRED\r\n");
        var preview = json.readTree(get(run + "/issues?limit=1").body());
        assertThat(preview.size()).isEqualTo(1);
        assertThat(preview.get(0).path("row").asInt()).isEqualTo(2);
        assertThat(preview.get(0).path("code").asString()).isEqualTo("TYPE");
        var recent = json.readTree(get("/api/runs?state=COMPLETED&limit=1").body());
        assertThat(recent.size()).isEqualTo(1);
        assertThat(recent.get(0).path("id").asString()).isEqualTo(run.substring("/api/runs/".length()));
        assertThat(get("/api/runs?state=UNKNOWN").statusCode()).isEqualTo(400);
        assertThat(get("/api/runs?limit=0").statusCode()).isEqualTo(400);
        assertThat(get(run + "/issues?limit=101").statusCode()).isEqualTo(400);
        assertThat(get("/openapi.yaml").body()).contains("openapi: 3.1.0");
    }

    @Test
    void rollsBackIssuesAlreadyWrittenWhenCsvIsMalformed() throws Exception {
        var created = put("/api/schemas/badinput/versions/1", SCHEMA);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String run = submit("badinput", 1, "id,region\n" + "xyz,ES\n".repeat(600) + "\"unterminated");
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var status = json.readTree(get(run).body());
            assertThat(status.path("state").asString()).isEqualTo("FAILED");
            assertThat(status.path("error").asString()).contains("Malformed");
        });
        UUID id = UUID.fromString(run.substring(run.lastIndexOf('/') + 1));
        assertThat(jdbc.sql("SELECT count(*) FROM row_issue WHERE run_id = ?")
                .param(id).query(Long.class).single()).isZero();
        assertThat(get(run + "/issues.csv").statusCode()).isEqualTo(409);
        assertThat(get(run + "/issues").statusCode()).isEqualTo(409);
    }

    @Test
    void resumesAnOrphanedRunningJob() throws Exception {
        assertThat(put("/api/schemas/recovery/versions/1", SCHEMA).statusCode()).isEqualTo(201);
        UUID id = UUID.randomUUID();
        byte[] payload = "id,region\n1,ES\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.sql("""
                INSERT INTO validation_run(id, schema_name, schema_version, payload, state)
                VALUES (?, 'recovery', 1, ?, 'RUNNING')
                """).params(id, payload).update();

        String run = "/api/runs/" + id;
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var status = json.readTree(get(run).body());
            assertThat(status.path("state").asString()).isEqualTo("COMPLETED");
            assertThat(status.path("totalRows").asInt()).isEqualTo(1);
            assertThat(status.path("invalidRows").asInt()).isZero();
        });
        assertThat(get(run + "/issues.csv").body()).isEqualTo("row,column,code\r\n");
    }

    @Test
    void appliesNewRulesWithoutChangingStoredLegacyVersions() throws Exception {
        jdbc.sql("""
                INSERT INTO schema_version(name, version, definition) VALUES ('rules', 1, CAST(? AS jsonb))
                """).param(SCHEMA).update();
        assertThat(put("/api/schemas/rules/versions/1", SCHEMA).statusCode()).isEqualTo(200);

        String definition = """
                {"columns":[
                  {"name":"email","type":"STRING","format":"EMAIL","unique":true},
                  {"name":"age","type":"INTEGER","min":18,"max":100},
                  {"name":"score","type":"DECIMAL","min":-0.5,"max":0.25}
                ]}
                """;
        String path = "/api/schemas/rules/versions/2";
        var created = put(path, definition);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(put(path, definition.replace("0.25", "0.250")).statusCode()).isEqualTo(200);
        assertThat(put(path.replace("/2", "/3"), definition.replace("100", "17")).statusCode()).isEqualTo(400);

        String csv = """
                email,age,score
                ana@example.com,18,-0.5
                ana@example.com,100,0.25
                bad@@example.com,17,-0.51
                other@example.com,101,0.251
                ,,
                ,,
                """;
        String run = submit("rules", 2, csv);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var status = json.readTree(get(run).body());
            assertThat(status.path("state").asString()).isEqualTo("COMPLETED");
            assertThat(status.path("totalRows").asInt()).isEqualTo(6);
            assertThat(status.path("invalidRows").asInt()).isEqualTo(3);
        });
        assertThat(get(run + "/issues.csv").body().lines()).containsExactly(
                "row,column,code", "2,email,DUPLICATE", "3,email,FORMAT", "3,age,MIN",
                "3,score,MIN", "4,age,MAX", "4,score,MAX");
        String independentRun = submit("rules", 2, "email,age,score\nana@example.com,18,0.25\n");
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(get(independentRun + "/issues.csv").body()).isEqualTo("row,column,code\r\n"));
    }

    @Test
    void validatesCsvJsonlAndSelectedExcelSheetWithTheSameRules() throws Exception {
        assertThat(put("/api/schemas/parity/versions/1", """
                {"columns":[
                  {"name":"id","type":"INTEGER","required":true,"unique":true},
                  {"name":"date","type":"DATE","required":true},
                  {"name":"active","type":"BOOLEAN","required":true}
                ]}
                """).statusCode()).isEqualTo(201);

        String csv = submit("parity", 1, "id,date,active\n1,2026-11-24,true\n1,,false\nx,2026-02-30,maybe\n");
        byte[] jsonl = """
                {"active":true,"id":1,"date":"2026-11-24"}
                {"id":1,"active":false}
                {"id":"x","date":"2026-02-30","active":"maybe"}
                """.getBytes(StandardCharsets.UTF_8);
        String jsonRun = submit("parity", 1, jsonl, "JSONL", null);
        String excelRun = submit("parity", 1, workbook(false), "XLSX", "Datos");
        String expected = "row,column,code\r\n2,id,DUPLICATE\r\n2,date,REQUIRED\r\n"
                + "3,id,TYPE\r\n3,date,TYPE\r\n3,active,TYPE\r\n";

        for (var entry : Map.of(csv, "CSV", jsonRun, "JSONL", excelRun, "XLSX").entrySet()) {
            await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
                var status = json.readTree(get(entry.getKey()).body());
                assertThat(status.path("state").asString()).isEqualTo("COMPLETED");
                assertThat(status.path("format").asString()).isEqualTo(entry.getValue());
                assertThat(status.path("totalRows").asInt()).isEqualTo(3);
                assertThat(status.path("invalidRows").asInt()).isEqualTo(2);
            });
            assertThat(get(entry.getKey() + "/issues.csv").body()).isEqualTo(expected);
        }
    }

    @Test
    void rejectsDuplicateJsonKeysAndExcelFormulasWithoutPublishingReports() throws Exception {
        assertThat(put("/api/schemas/invalidformats/versions/1", """
                {"columns":[{"name":"id","type":"INTEGER"},{"name":"date","type":"DATE"},
                            {"name":"active","type":"BOOLEAN"}]}
                """).statusCode()).isEqualTo(201);
        String jsonRun = submit("invalidformats", 1, "{\"id\":1,\"id\":2}\n".getBytes(StandardCharsets.UTF_8),
                "JSONL", null);
        String excelRun = submit("invalidformats", 1, workbook(true), "XLSX", "Datos");
        for (var entry : Map.of(jsonRun, "Malformed JSON on line 1", excelRun, "XLSX formula").entrySet()) {
            await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
                var status = json.readTree(get(entry.getKey()).body());
                assertThat(status.path("state").asString()).isEqualTo("FAILED");
                assertThat(status.path("error").asString()).contains(entry.getValue());
            });
            assertThat(get(entry.getKey() + "/issues.csv").statusCode()).isEqualTo(409);
        }
    }

    private byte[] workbook(boolean formula) throws IOException {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            book.createSheet("Introducción");
            var sheet = book.createSheet("Datos");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("active");
            header.createCell(1).setCellValue("id");
            header.createCell(2).setCellValue("date");
            var first = sheet.createRow(1);
            first.createCell(0).setCellValue(true);
            first.createCell(1).setCellValue(1);
            var date = first.createCell(2);
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(style);
            date.setCellValue(LocalDate.of(2026, 11, 24));
            var second = sheet.createRow(2);
            second.createCell(0).setCellValue(false);
            second.createCell(1).setCellValue(1);
            var third = sheet.createRow(4);
            third.createCell(0).setCellValue("maybe");
            if (formula) third.createCell(1).setCellFormula("1+1");
            else third.createCell(1).setCellValue("x");
            third.createCell(2).setCellValue("2026-02-30");
            book.write(output);
            return output.toByteArray();
        }
    }

    @Test
    void recommendsCorrectionsAndRetainsRequestedPartitions() throws Exception {
        String definition = """
                {"delimiter":";","columns":[
                  {"name":"id","type":"INTEGER","unique":true},
                  {"name":"country","type":"STRING","required":true,"allowedValues":["ES","PT"],"defaultValue":"ES"},
                  {"name":"amount","type":"DECIMAL","min":0,"inputDecimalSeparator":","},
                  {"name":"day","type":"DATE"}
                ]}
                """;
        String schema = "/api/schemas/corrections/versions/";
        assertThat(put(schema + "1", definition).statusCode()).isEqualTo(201);
        assertThat(put(schema + "2", definition.replace("\"defaultValue\":\"ES\"", "\"defaultValue\":\"FR\""))
                .statusCode()).isEqualTo(400);
        assertThat(get("/api/schemas?prefix=corrections").body()).contains("corrections");

        String csv = "id;country;amount;day\n1;ES;10.50;2026-04-03\n1;es;12,50;03/04/2026\n2;;-5,00;\n";
        String run = submit("corrections", 1, csv.getBytes(StandardCharsets.UTF_8), "CSV", null, true);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(json.readTree(get(run).body()).path("state").asString()).isEqualTo("COMPLETED"));
        var issues = json.readTree(get(run + "/issues").body());
        assertThat(issues.size()).isEqualTo(6);
        assertThat(issues.get(0).path("suggestion").path("relatedRow").asInt()).isEqualTo(1);
        assertThat(issues.get(1).path("suggestion").path("candidates").get(0).asString()).isEqualTo("ES");
        assertThat(issues.get(2).path("suggestion").path("candidates").get(0).asString()).isEqualTo("12.50");
        assertThat(issues.get(3).path("suggestion").path("candidates").size()).isEqualTo(2);
        assertThat(issues.get(4).path("suggestion").path("candidates").get(0).asString()).isEqualTo("ES");
        assertThat(issues.get(5).path("suggestion").isNull()).isTrue();
        var valid = get(run + "/records/valid.jsonl");
        var rejected = get(run + "/records/rejected.jsonl");
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(valid.body().lines()).hasSize(1);
        assertThat(rejected.body().lines()).hasSize(2);
        assertThat(json.readTree(rejected.body().lines().findFirst().orElseThrow()).path("row").asInt()).isEqualTo(2);
        String edits = """
                {"edits":[{"row":2,"column":"id","value":"3"},{"row":2,"column":"country","value":"ES"},
                          {"row":2,"column":"amount","value":"12.50"},{"row":2,"column":"day","value":"2026-04-03"},
                          {"row":3,"column":"country","value":"ES"},{"row":3,"column":"amount","value":"0"}]}
                """;
        assertThat(postJson(run + "/corrections", """
                {"edits":[{"row":1,"column":"id","value":"4"}]}
                """).statusCode()).isEqualTo(400);
        var submitted = postJson(run + "/corrections", edits);
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String child = submitted.headers().firstValue("Location").orElseThrow();
        assertThat(postJson(run + "/corrections", edits).headers().firstValue("Location")).contains(child);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var corrected = json.readTree(get(child).body());
            assertThat(corrected.path("state").asString()).isEqualTo("COMPLETED");
            assertThat(corrected.path("invalidRows").asInt()).isZero();
            assertThat(corrected.path("parentRunId").asString()).isEqualTo(run.substring("/api/runs/".length()));
        });
        var corrected = get(child + "/records/corrected.jsonl");
        assertThat(corrected.statusCode()).isEqualTo(200);
        assertThat(corrected.body().lines()).hasSize(3);
        assertThat(json.readTree(get(run).body()).path("invalidRows").asInt()).isEqualTo(2);
        UUID id = UUID.fromString(run.substring("/api/runs/".length()));
        jdbc.sql("UPDATE validation_run SET records_expires_at = now() - interval '1 hour' WHERE id = ?")
                .param(id).update();
        assertThat(get(run + "/records/valid.jsonl").statusCode()).isEqualTo(410);
        runs.purgeExpiredRecords();
        assertThat(jdbc.sql("SELECT count(*) FROM validated_record WHERE run_id = ?")
                .param(id).query(Long.class).single()).isZero();
        assertThat(get(run + "/issues.csv").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/metrics/dataclean.queue.runs").statusCode()).isEqualTo(200);
    }

    private String submit(String name, int version, String csv) throws IOException, InterruptedException {
        return submit(name, version, csv.getBytes(StandardCharsets.UTF_8), null, null);
    }

    private String submit(String name, int version, byte[] payload, String format, String sheet)
            throws IOException, InterruptedException {
        return submit(name, version, payload, format, sheet, false);
    }

    private String submit(String name, int version, byte[] payload, String format, String sheet, boolean retain)
            throws IOException, InterruptedException {
        String boundary = "dataclean-" + UUID.randomUUID();
        String fields = field(boundary, "schemaName", name) + field(boundary, "schemaVersion", Integer.toString(version));
        if (format != null) fields += field(boundary, "format", format);
        if (sheet != null) fields += field(boundary, "sheet", sheet);
        if (retain) fields += field(boundary, "retainRecords", "true");
        String fileHeader = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dataset\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(fields + fileHeader),
                HttpRequest.BodyPublishers.ofByteArray(payload), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        var response = send(request("/api/runs").header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body).build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return response.headers().firstValue("Location").orElseThrow();
    }

    private String field(String boundary, String name, String value) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
    }

    private HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
        return send(request(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(request(path).GET().build());
    }

    private HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
