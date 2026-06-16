package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convert the seeded "function" capabilities from Java-backed stubs into in-platform
 * <b>JavaScript</b> functions executed by GraalJS. The JS source lives under
 * {@code resources/js/functions/<slug>.js}; this migration loads it and writes a
 * {@code {language, code, input_schema}} spec. Written in Java so Jackson handles the JSON
 * escaping of the (regex-heavy) source.
 */
public class V5__migrate_functions_to_js extends BaseJavaMigration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        upsert(context, "concatenate", obj(
                "type", "object",
                "properties", obj(
                        "items", obj("type", "array", "items", obj("type", "string")),
                        "separator", obj("type", "string", "description", "Defaults to newline")),
                "required", List.of("items")));

        upsert(context, "dedupe_by_embedding", obj(
                "type", "object",
                "properties", obj(
                        "items", obj("type", "array", "items", obj("type", "string"))),
                "required", List.of("items")));

        upsert(context, "rank_by_relevance", obj(
                "type", "object",
                "properties", obj(
                        "query", obj("type", "string"),
                        "items", obj("type", "array", "items", obj("type", "string"))),
                "required", List.of("query", "items")));

        upsert(context, "read_url_fn", obj(
                "type", "object",
                "properties", obj("url", obj("type", "string")),
                "required", List.of("url")));
    }

    private void upsert(Context ctx, String slug, Map<String, Object> inputSchema) throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("language", "javascript");
        spec.put("runtime", "graaljs");
        spec.put("code", readResource("/js/functions/" + slug + ".js"));
        spec.put("input_schema", inputSchema);

        String json = MAPPER.writeValueAsString(spec);
        try (PreparedStatement ps = ctx.getConnection().prepareStatement(
                "UPDATE capabilities SET spec = CAST(? AS jsonb), version = version + 1, "
                        + "updated_at = now() WHERE slug = ? AND type = 'function'")) {
            ps.setString(1, json);
            ps.setString(2, slug);
            ps.executeUpdate();
        }
    }

    private String readResource(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing migration resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
