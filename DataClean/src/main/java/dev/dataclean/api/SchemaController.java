package dev.dataclean.api;

import dev.dataclean.schema.SchemaDefinition;
import dev.dataclean.schema.SchemaStore;
import dev.dataclean.engine.DatasetValidator;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/schemas")
class SchemaController {
    private final SchemaStore schemas;
    private final DatasetValidator validator;

    SchemaController(SchemaStore schemas, DatasetValidator validator) {
        this.schemas = schemas;
        this.validator = validator;
    }

    @GetMapping
    List<SchemaStore.Version> versions(@RequestParam(defaultValue = "") String prefix,
                                      @RequestParam(defaultValue = "50") int limit) {
        if (!prefix.isEmpty()) SchemaDefinition.requireName(prefix);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be between 1 and 100");
        return schemas.versions(prefix, limit);
    }

    @PutMapping("/{name}/versions/{version}")
    ResponseEntity<SchemaDefinition> create(@PathVariable String name, @PathVariable int version,
                                             @RequestBody SchemaDefinition definition) {
        validator.validateDefaults(definition);
        if (!schemas.create(name, version, definition)) {
            var existing = schemas.find(name, version).orElseThrow();
            if (existing.equals(definition)) return ResponseEntity.ok(existing);
            throw new ResponseStatusException(CONFLICT, "This schema version is immutable");
        }
        var location = URI.create("/api/schemas/" + name + "/versions/" + version);
        return ResponseEntity.created(location).body(definition);
    }

    @GetMapping("/{name}/versions/{version}")
    SchemaDefinition find(@PathVariable String name, @PathVariable int version) {
        return schemas.find(name, version)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Schema version not found"));
    }
}
