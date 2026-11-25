package dev.dataclean.schema;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SchemaStore {
    public record Version(String name, int version) {}
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public SchemaStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean create(String name, int version, SchemaDefinition definition) {
        SchemaDefinition.requireName(name);
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
        return jdbc.sql("""
                INSERT INTO schema_version(name, version, definition) VALUES (?, ?, CAST(? AS jsonb))
                ON CONFLICT (name, version) DO NOTHING
                """).params(name, version, json.writeValueAsString(definition)).update() == 1;
    }

    public Optional<SchemaDefinition> find(String name, int version) {
        return jdbc.sql("SELECT definition::text FROM schema_version WHERE name = ? AND version = ?")
                .params(name, version).query(String.class).optional()
                .map(definition -> json.readValue(definition, SchemaDefinition.class));
    }

    public List<Version> versions(String prefix, int limit) {
        return jdbc.sql("""
                SELECT name, version FROM schema_version WHERE starts_with(name, ?)
                ORDER BY name, version DESC LIMIT ?
                """).params(prefix, limit).query((rs, n) -> new Version(rs.getString(1), rs.getInt(2))).list();
    }
}
