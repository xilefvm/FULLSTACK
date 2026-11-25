package dev.dataclean.engine;

import dev.dataclean.schema.SchemaDefinition;
import dev.dataclean.schema.SchemaDefinition.Column;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DatasetValidator {
    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern EMAIL_LOCAL = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    private static final ObjectReader JSON_LINES = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build().reader();

    public record Suggestion(String action, List<String> candidates, Long relatedRow) {}
    public record Issue(long row, int index, String column, String code, Suggestion suggestion) {
        public Issue(long row, int index, String column, String code) {
            this(row, index, column, code, null);
        }
    }
    public record Summary(long totalRows, long invalidRows) {}
    public record ValidatedRecord(long row, boolean valid, Object data) {}

    public static class InvalidDataset extends RuntimeException {
        public InvalidDataset(String message) {
            super(message);
        }
    }

    public Summary validate(byte[] payload, DatasetFormat format, String sheet, SchemaDefinition schema,
                            Consumer<Issue> issues) {
        return validate(payload, format, sheet, schema, issues, null);
    }

    public Summary validate(byte[] payload, DatasetFormat format, String sheet, SchemaDefinition schema,
                            Consumer<Issue> issues, Consumer<ValidatedRecord> records) {
        var rows = new Rows(schema.columns(), issues, records);
        switch (format) {
            case CSV -> readCsv(payload, schema.delimiter(), rows);
            case JSONL -> readJsonl(payload, rows);
            case XLSX -> ExcelRows.read(payload, sheet, rows);
        }
        return new Summary(rows.total, rows.invalid);
    }

    private static InputStreamReader utf8(byte[] payload) {
        int offset = Arrays.equals(payload, 0, Math.min(3, payload.length), UTF8_BOM, 0, 3) ? 3 : 0;
        return new InputStreamReader(new ByteArrayInputStream(payload, offset, payload.length - offset),
                StandardCharsets.UTF_8.newDecoder());
    }

    private void readCsv(byte[] payload, char delimiter, Rows rows) {
        var format = CSVFormat.RFC4180.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .get();
        try (var reader = utf8(payload); var parser = CSVParser.parse(reader, format)) {
            var positions = rows.header(parser.getHeaderNames());
            for (var record : parser) {
                if (record.size() != positions.size()) rows.columnCount(record::toList);
                else rows.accept(name -> record.get(positions.get(name)));
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new InvalidDataset("Malformed UTF-8 CSV or invalid header");
        }
    }

    private void readJsonl(byte[] payload, Rows rows) {
        try (var reader = new BufferedReader(utf8(payload))) {
            String line;
            long number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                JsonNode record;
                try {
                    record = JSON_LINES.readTree(line);
                    if (record == null || !record.isObject()) {
                        throw new InvalidDataset("Expected a JSON object on line " + number);
                    }
                } catch (JacksonException e) {
                    throw new InvalidDataset("Malformed JSON on line " + number);
                }
                if (!rows.names.containsAll(record.propertyNames())) rows.columnCount(() -> record);
                else rows.accept(name -> jsonValue(record.get(name)), () -> record);
            }
        } catch (IOException e) {
            throw new InvalidDataset("Malformed UTF-8 JSONL");
        }
    }

    private static String jsonValue(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual() || value.isBoolean() || value.isIntegralNumber()) return value.asText();
        if (value.isFloatingPointNumber()) {
            var decimal = value.decimalValue().stripTrailingZeros();
            if (Math.abs((long) decimal.scale()) <= 10_000) return decimal.toPlainString();
        }
        return null;
    }

    static final class Rows {
        private final List<Column> columns;
        private final Set<String> names;
        private final Consumer<Issue> issues;
        private final Consumer<ValidatedRecord> records;
        private final Map<String, Map<String, Long>> seen = new HashMap<>();
        private long total;
        private long invalid;

        Rows(List<Column> columns, Consumer<Issue> issues, Consumer<ValidatedRecord> records) {
            this.columns = columns;
            this.issues = issues;
            this.records = records;
            names = columns.stream().map(Column::name).collect(Collectors.toSet());
            for (Column column : columns) {
                if (column.unique()) seen.put(column.name(), new HashMap<>());
            }
        }

        Map<String, Integer> header(List<String> header) {
            if (header.size() != names.size() || !new HashSet<>(header).equals(names)) {
                throw new InvalidDataset("Header does not match the schema");
            }
            Map<String, Integer> positions = new HashMap<>();
            for (int index = 0; index < header.size(); index++) positions.put(header.get(index), index);
            return positions;
        }

        void columnCount(Supplier<Object> data) {
            issues.accept(new Issue(++total, 0, "", "COLUMN_COUNT"));
            invalid++;
            if (records != null) records.accept(new ValidatedRecord(total, false, data.get()));
        }

        void accept(Function<String, String> values) {
            accept(values, null);
        }

        void accept(Function<String, String> values, Supplier<Object> original) {
            total++;
            int index = 0;
            Map<String, String> data = records == null ? null : new LinkedHashMap<>();
            for (Column column : columns) {
                String raw = values.apply(column.name());
                String value = raw == null ? "" : raw.strip();
                String code = raw == null ? "TYPE" : failure(value, column);
                Long firstRow = code == null && column.unique() && !value.isEmpty()
                        ? seen.get(column.name()).putIfAbsent(value, total) : null;
                if (firstRow != null) {
                    code = "DUPLICATE";
                }
                if (code != null) issues.accept(new Issue(total, index++, column.name(), code,
                        suggest(code, raw == null ? null : value, column, firstRow)));
                if (data != null) data.put(column.name(), raw == null ? null : value);
            }
            if (index != 0) invalid++;
            if (records != null) records.accept(new ValidatedRecord(total, index == 0,
                    index != 0 && original != null ? original.get() : data));
        }
    }

    public void validateDefaults(SchemaDefinition schema) {
        for (var column : schema.columns()) {
            if (column.defaultValue() != null && failure(column.defaultValue(), column) != null) {
                throw new IllegalArgumentException("Default value fails rules for column " + column.name());
            }
        }
    }

    private static Suggestion suggest(String code, String value, Column column, Long firstRow) {
        if (firstRow != null) return new Suggestion("REVIEW_DUPLICATE", List.of(), firstRow);
        if (value == null) return null;
        if (code.equals("REQUIRED")) return proposal(column.defaultValue(), column);
        if (code.equals("NOT_ALLOWED")) {
            var matches = column.allowedValues().stream().distinct()
                    .filter(candidate -> candidate.equalsIgnoreCase(value)).toList();
            if (matches.size() == 1) return proposal(matches.getFirst(), column);
        }
        if (!code.equals("TYPE") || value.length() > 1000) return null;
        if (Character.valueOf(',').equals(column.inputDecimalSeparator())
                && value.matches("[+-]?[0-9]+,[0-9]+")) {
            return proposal(value.replace(',', '.'), column);
        }
        if (column.type() == SchemaDefinition.Type.DATE && value.matches("[0-9]{2}/[0-9]{2}/[0-9]{4}")) {
            String year = value.substring(6);
            String first = value.substring(0, 2);
            String second = value.substring(3, 5);
            var dates = java.util.stream.Stream.of(year + "-" + second + "-" + first,
                    year + "-" + first + "-" + second).distinct().filter(DatasetValidator::isDate).toList();
            if (!dates.isEmpty()) return new Suggestion("CONFIRM_DATE_FORMAT",
                    dates.stream().filter(date -> failure(date, column) == null).toList(), null);
        }
        return null;
    }

    private static Suggestion proposal(String candidate, Column column) {
        if (candidate == null || failure(candidate, column) != null) return null;
        return new Suggestion(column.unique() ? "REVIEW_UNIQUENESS" : "REPLACE", List.of(candidate), null);
    }

    private static String failure(String value, Column column) {
        if (value.isEmpty()) return column.required() ? "REQUIRED" : null;
        if (!matchesType(value, column.type())) return "TYPE";
        int length = value.codePointCount(0, value.length());
        if (column.minLength() != null && length < column.minLength()) return "MIN_LENGTH";
        if (column.maxLength() != null && length > column.maxLength()) return "MAX_LENGTH";
        if (!column.allowedValues().isEmpty() && !column.allowedValues().contains(value)) return "NOT_ALLOWED";
        if (column.format() == SchemaDefinition.Format.EMAIL && !isEmail(value)) return "FORMAT";
        if (column.min() != null || column.max() != null) {
            var number = new BigDecimal(value);
            if (column.min() != null && number.compareTo(column.min()) < 0) return "MIN";
            if (column.max() != null && number.compareTo(column.max()) > 0) return "MAX";
        }
        return null;
    }

    private static boolean isEmail(String value) {
        int at = value.indexOf('@');
        if (value.length() > 254 || at < 1 || at > 64) return false;
        String local = value.substring(0, at);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || !EMAIL_LOCAL.matcher(local).matches()) return false;
        String[] labels = value.substring(at + 1).split("\\.", -1);
        return labels.length > 1 && Arrays.stream(labels).allMatch(label -> DOMAIN_LABEL.matcher(label).matches());
    }

    private static boolean matchesType(String value, SchemaDefinition.Type type) {
        return switch (type) {
            case STRING -> true;
            case INTEGER -> INTEGER.matcher(value).matches();
            case DECIMAL -> DECIMAL.matcher(value).matches();
            case DATE -> isDate(value);
            case BOOLEAN -> value.equals("true") || value.equals("false");
        };
    }

    private static boolean isDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
