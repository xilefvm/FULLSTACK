package dev.dataclean.schema;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record SchemaDefinition(Character delimiter, List<Column> columns) {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{0,63}");

    public enum Type { STRING, INTEGER, DECIMAL, DATE, BOOLEAN }
    public enum Format { EMAIL }

    public record Column(String name, Type type, Boolean required,
                         Integer minLength, Integer maxLength, List<String> allowedValues,
                         BigDecimal min, BigDecimal max, Format format, Boolean unique,
                         String defaultValue, Character inputDecimalSeparator) {
        public Column(String name, Type type, Boolean required, Integer minLength, Integer maxLength,
                      List<String> allowedValues) {
            this(name, type, required, minLength, maxLength, allowedValues, null, null, null, false, null, null);
        }

        public Column {
            requireName(name);
            required = Boolean.TRUE.equals(required);
            unique = Boolean.TRUE.equals(unique);
            if (type == null) throw new IllegalArgumentException("Every column needs a type");
            if (defaultValue != null && (defaultValue.isBlank() || defaultValue.length() > 1000
                    || !defaultValue.equals(defaultValue.strip()) || defaultValue.indexOf(0) >= 0)) {
                throw new IllegalArgumentException("Invalid default value");
            }
            if (inputDecimalSeparator != null && (type != Type.DECIMAL
                    || inputDecimalSeparator != ',' && inputDecimalSeparator != '.')) {
                throw new IllegalArgumentException("Decimal input separator requires a DECIMAL column and comma or dot");
            }
            if (format != null && type != Type.STRING) {
                throw new IllegalArgumentException("Format requires a STRING column");
            }
            if ((min != null || max != null) && type != Type.INTEGER && type != Type.DECIMAL) {
                throw new IllegalArgumentException("Numeric bounds require INTEGER or DECIMAL");
            }
            min = min == null ? null : min.stripTrailingZeros();
            max = max == null ? null : max.stripTrailingZeros();
            if (type == Type.INTEGER && (min != null && min.scale() > 0 || max != null && max.scale() > 0)) {
                throw new IllegalArgumentException("INTEGER bounds must be integers");
            }
            if (min != null && max != null && min.compareTo(max) > 0) {
                throw new IllegalArgumentException("Minimum must not exceed maximum");
            }
            if (minLength != null && (minLength < 0 || minLength > 100_000)
                    || maxLength != null && (maxLength < 0 || maxLength > 100_000)
                    || minLength != null && maxLength != null && minLength > maxLength) {
                throw new IllegalArgumentException("Invalid column length constraints");
            }
            if (allowedValues != null && (allowedValues.size() > 100
                    || allowedValues.stream().anyMatch(value -> value == null || value.isBlank()
                            || value.length() > 1000 || !value.equals(value.strip())))) {
                throw new IllegalArgumentException("Invalid allowed values");
            }
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
    }

    public SchemaDefinition {
        delimiter = delimiter == null ? ',' : delimiter;
        if (delimiter != ',' && delimiter != ';' && delimiter != '\t') {
            throw new IllegalArgumentException("Delimiter must be comma, semicolon or tab");
        }
        if (columns == null || columns.isEmpty() || columns.size() > 100) {
            throw new IllegalArgumentException("A schema requires between 1 and 100 columns");
        }
        Set<String> names = new HashSet<>();
        for (Column column : columns) {
            if (column == null || !names.add(column.name())) {
                throw new IllegalArgumentException("Column names must be unique");
            }
        }
        columns = List.copyOf(columns);
    }

    public static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Names must start with a letter and use up to 64 ASCII letters, digits, underscores or hyphens");
        }
    }
}
