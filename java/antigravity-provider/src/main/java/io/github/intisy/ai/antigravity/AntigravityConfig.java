package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.ConfigField;
import io.github.intisy.ai.shared.routing.ConfigGroup;
import io.github.intisy.ai.shared.routing.ConfigSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SP-E/E-D: JVM port of antigravity-auth's TS config surface ({@code src/plugin/config/schema.ts}
 * + {@code loader.ts}/{@code edit.ts}) as a typed {@link
 * io.github.intisy.ai.shared.routing.ConfigurableProvider} -- a GENUINELY NEW capability for the
 * Java provider (mirrors {@link AntigravityOAuth}'s "new capability" shape, and claude-code-auth's
 * {@code ClaudeConfig} for the GET-merged/PUT-persisted pattern). Reads/writes the SAME on-disk
 * file the TS driver's core config store uses ({@code config/antigravity.json}, via the injected
 * {@link io.github.intisy.ai.shared.spi.Store} -- never a self-assembled {@code FileStore}), so
 * JVM and TS share one settings file.
 *
 * <p>The nested TS group {@code signature_cache} is flattened to dotted {@link ConfigField#key}s
 * (e.g. {@code "signature_cache.enabled"}) for the flat {@code ConfigField} wire shape, then
 * un-flattened back into the SAME nested JSON structure on read/persist so the on-disk file stays
 * byte-compatible with what the TS side writes.
 */
final class AntigravityConfig {

    // Matches the TS config-file name (config/antigravity.json) exactly -- see loader.ts/edit.ts.
    private static final String STORE_KEY = "antigravity.json";

    private static final List<String> ACCOUNT_STRATEGIES = Arrays.asList("sticky", "round-robin", "hybrid");

    // Mirrors schema.ts's AntigravityConfig shape + DEFAULT_CONFIG exactly, grouped by the
    // sections schema.ts's own comments already delineate. Dotted keys are the flattened form of
    // a nested TS group (see class doc). ONLY keys with a real runtime consumer are exposed here
    // (the 9 pre-existing functional keys + the 3 E-wired features: request_jitter_max_ms,
    // cli_first, signature_cache.*) -- the rest of the historical schema.ts surface was dead
    // (no consumer in either the TS driver or this JVM provider) and was deleted, not just hidden.
    private static final List<GroupDef> GROUPS = Arrays.asList(
            new GroupDef("General", Arrays.asList(
                    field("debug", "Debug logging", "bool", null, Boolean.FALSE),
                    field("debug_tui", "Debug in TUI", "bool", null, Boolean.FALSE),
                    field("log_dir", "Log directory", "text", null, null))),
            new GroupDef("Thinking", Arrays.asList(
                    field("keep_thinking", "Preserve thinking blocks", "bool", null, Boolean.FALSE),
                    field("signature_cache.enabled", "Signature cache enabled", "bool", null, Boolean.TRUE),
                    field("signature_cache.memory_ttl_seconds", "Signature cache memory TTL (s)", "number", null, 3600L),
                    field("signature_cache.disk_ttl_seconds", "Signature cache disk TTL (s)", "number", null, 172800L),
                    field("signature_cache.write_interval_seconds", "Signature cache write interval (s)", "number", null, 60L))),
            new GroupDef("Claude Compatibility", Arrays.asList(
                    field("claude_tool_hardening", "Claude tool hardening", "bool", null, Boolean.TRUE),
                    field("claude_prompt_auto_caching", "Claude prompt auto-caching", "bool", null, Boolean.FALSE))),
            new GroupDef("Model Routing", Arrays.asList(
                    field("cli_first", "Prefer gemini-cli routing", "bool", null, Boolean.FALSE))),
            new GroupDef("Account Selection", Arrays.asList(
                    field("account_selection_strategy", "Account selection strategy", "select", ACCOUNT_STRATEGIES, "hybrid"))),
            new GroupDef("Retry/Backoff", Arrays.asList(
                    field("default_retry_after_seconds", "Default retry-after (s)", "number", null, 60L),
                    field("max_backoff_seconds", "Max backoff (s)", "number", null, 60L),
                    field("request_jitter_max_ms", "Request jitter max (ms)", "number", null, 0L))));

    private AntigravityConfig() {
    }

    static ConfigSchema schema() {
        List<ConfigGroup> groups = new ArrayList<>();
        for (GroupDef g : GROUPS) {
            groups.add(new ConfigGroup(g.title, g.fields));
        }
        return new ConfigSchema(groups);
    }

    static Map<String, Object> getValues(AntigravityBackend backend) {
        return mergedValues(readPersisted(backend));
    }

    static Map<String, Object> putValues(AntigravityBackend backend, Map<String, Object> values) {
        Map<String, Object> overrides = readPersisted(backend);
        if (overrides == null) overrides = new LinkedHashMap<>();
        if (values != null) {
            for (ConfigField f : allFields()) {
                if (!values.containsKey(f.key)) continue;
                Object raw = values.get(f.key);
                if (raw == null) {
                    // Explicit null clears an optional field (log_dir) back to
                    // "use the default" -- matches the TS's optional (`?`) schema fields.
                    setNested(overrides, f.key, null);
                    continue;
                }
                Object coerced = coerce(f, raw);
                // An invalid/unknown value (e.g. a select outside its options) is ignored rather
                // than rejecting the whole request, leaving any prior override/default in place.
                if (coerced != null) setNested(overrides, f.key, coerced);
            }
        }
        backend.store.put(STORE_KEY, backend.json.stringify(overrides));
        return mergedValues(overrides);
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readPersisted(AntigravityBackend backend) {
        String raw = backend.store.get(STORE_KEY);
        if (raw == null || raw.isEmpty()) return null;
        Object parsed = backend.json.parse(raw);
        return parsed instanceof Map ? (Map<String, Object>) parsed : null;
    }

    private static Map<String, Object> mergedValues(Map<String, Object> persisted) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ConfigField f : allFields()) {
            Object v = getNested(persisted, f.key);
            values.put(f.key, v != null ? v : f.defaultValue);
        }
        return values;
    }

    private static Object coerce(ConfigField f, Object raw) {
        switch (f.type) {
            case "bool":
                return raw instanceof Boolean ? raw : null;
            case "number":
                return raw instanceof Number ? raw : null;
            case "select":
                return (raw instanceof String && f.options != null && f.options.contains(raw)) ? raw : null;
            case "text":
                return raw instanceof String ? raw : null;
            default:
                return null;
        }
    }

    private static List<ConfigField> allFields() {
        List<ConfigField> all = new ArrayList<>();
        for (GroupDef g : GROUPS) all.addAll(g.fields);
        return all;
    }

    private static Object getNested(Map<String, Object> root, String dottedKey) {
        if (root == null) return null;
        Object cur = root;
        for (String part : dottedKey.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(part);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static void setNested(Map<String, Object> root, String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            Map<String, Object> nextMap;
            if (next instanceof Map) {
                nextMap = (Map<String, Object>) next;
            } else {
                nextMap = new LinkedHashMap<>();
                cur.put(parts[i], nextMap);
            }
            cur = nextMap;
        }
        cur.put(parts[parts.length - 1], value);
    }

    private static ConfigField field(String key, String label, String type, List<String> options, Object defaultValue) {
        return new ConfigField(key, label, type, options, defaultValue);
    }

    private static final class GroupDef {
        final String title;
        final List<ConfigField> fields;

        GroupDef(String title, List<ConfigField> fields) {
            this.title = title;
            this.fields = fields;
        }
    }
}
