package dev.dataclean.engine;

import dev.dataclean.engine.DatasetValidator.InvalidDataset;
import dev.dataclean.engine.DatasetValidator.Issue;
import dev.dataclean.schema.SchemaDefinition;
import dev.dataclean.schema.SchemaDefinition.Column;
import dev.dataclean.schema.SchemaDefinition.Type;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetValidatorTest {
    private final DatasetValidator validator = new DatasetValidator();

    @Test
    void validatesReorderedHeadersAndCountsLogicalRecordsWithEmbeddedNewlines() {
        var schema = new SchemaDefinition(';', List.of(
                new Column("id", Type.INTEGER, true, null, null, null),
                new Column("title", Type.STRING, true, 2, 20, null)));
        var input = "\ufefftitle;id\r\n\"two\nlines\";1\r\n;x\r\nonly_one\r\n";
        var issues = new ArrayList<Issue>();
        var summary = validator.validate(input.getBytes(StandardCharsets.UTF_8), DatasetFormat.CSV, null, schema, issues::add);
        assertThat(summary).isEqualTo(new DatasetValidator.Summary(3, 2));
        assertThat(issues).containsExactly(new Issue(2, 0, "id", "TYPE"),
                new Issue(2, 1, "title", "REQUIRED"), new Issue(3, 0, "", "COLUMN_COUNT"));
    }

    @Test
    void rejectsDuplicateHeadersAndMalformedUtf8() {
        var schema = new SchemaDefinition(',', List.of(new Column("id", Type.STRING, true, null, null, null)));
        assertThatThrownBy(() -> validator.validate("id,id\na,b\n".getBytes(StandardCharsets.UTF_8),
                DatasetFormat.CSV, null, schema, issue -> {})).isInstanceOf(InvalidDataset.class);
        byte[] invalid = {'i', 'd', '\n', (byte) 0xc3, '('};
        assertThatThrownBy(() -> validator.validate(invalid, DatasetFormat.CSV, null, schema, issue -> {})).isInstanceOf(InvalidDataset.class);
    }
}
