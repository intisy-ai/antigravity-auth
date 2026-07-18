package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.ConfigField;
import io.github.intisy.ai.shared.routing.ConfigGroup;
import io.github.intisy.ai.shared.routing.ConfigSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * <p>Nested TS groups ({@code signature_cache}/{@code health_score}/{@code token_bucket}) are
 * flattened to dotted {@link ConfigField#key}s (e.g. {@code "signature_cache.enabled"}) for the
 * flat {@code ConfigField} wire shape, then un-flattened back into the SAME nested JSON structure
 * on read/persist so the on-disk file stays byte-compatible with what the TS side writes. {@code
 * model_ranking} (a string array) and {@code $schema} are NOT exposed here -- {@link ConfigField}
 * has no array-typed value, an intentional gap (see the migration report).
 */
final class AntigravityConfig {

    // Matches the TS config-file name (config/antigravity.json) exactly -- see loader.ts/edit.ts.
    private static final String STORE_KEY = "antigravity.json";

    private static final List<String> TOAST_SCOPES = Arrays.asList("root_only", "all");
    private static final List<String> ACCOUNT_STRATEGIES = Arrays.asList("sticky", "round-robin", "hybrid");
    private static final List<String> SCHEDULING_MODES = Arrays.asList("cache_first", "balance", "performance_first");
    private static final List<String> AUTO_MODE_STAGES = Arrays.asList("best", "high", "balanced", "fastest");

    // Mirrors schema.ts's AntigravityConfig shape + DEFAULT_CONFIG exactly, grouped by the
    // sections schema.ts's own comments already delineate. Dotted keys are the flattened form of
    // a nested TS group (see class doc).
    private static final List<GroupDef> GROUPS = Arrays.asList(
            new GroupDef("General", Arrays.asList(
                    field("quiet_mode", "Quiet mode", "bool", null, Boolean.FALSE),
                    field("toast_scope", "Toast scope", "select", TOAST_SCOPES, "root_only"),
                    field("debug", "Debug logging", "bool", null, Boolean.FALSE),
                    field("debug_tui", "Debug in TUI", "bool", null, Boolean.FALSE),
                    field("debug_gemini_payloads", "Debug Gemini payloads", "bool", null, Boolean.FALSE),
                    field("log_dir", "Log directory", "text", null, null))),
            new GroupDef("Thinking", Arrays.asList(
                    field("keep_thinking", "Preserve thinking blocks", "bool", null, Boolean.FALSE),
                    field("signature_cache.enabled", "Signature cache enabled", "bool", null, Boolean.TRUE),
                    field("signature_cache.memory_ttl_seconds", "Signature cache memory TTL (s)", "number", null, 3600L),
                    field("signature_cache.disk_ttl_seconds", "Signature cache disk TTL (s)", "number", null, 172800L),
                    field("signature_cache.write_interval_seconds", "Signature cache write interval (s)", "number", null, 60L))),
            new GroupDef("Reliability", Arrays.asList(
                    field("empty_response_max_attempts", "Empty response max attempts", "number", null, 4L),
                    field("empty_response_retry_delay_ms", "Empty response retry delay (ms)", "number", null, 2000L),
                    field("tool_id_recovery", "Tool ID orphan recovery", "bool", null, Boolean.TRUE))),
            new GroupDef("Claude Compatibility", Arrays.asList(
                    field("claude_tool_hardening", "Claude tool hardening", "bool", null, Boolean.TRUE),
                    field("claude_prompt_auto_caching", "Claude prompt auto-caching", "bool", null, Boolean.FALSE))),
            new GroupDef("Token Refresh", Arrays.asList(
                    field("proactive_token_refresh", "Proactive token refresh", "bool", null, Boolean.TRUE),
                    field("proactive_refresh_buffer_seconds", "Proactive refresh buffer (s)", "number", null, 1800L),
                    field("proactive_refresh_check_interval_seconds", "Proactive refresh check interval (s)", "number", null, 300L))),
            new GroupDef("Rate Limiting", Arrays.asList(
                    field("max_rate_limit_wait_seconds", "Max rate-limit wait (s)", "number", null, 300L),
                    field("quota_fallback", "Quota fallback (deprecated)", "bool", null, Boolean.FALSE),
                    field("cli_first", "Prefer gemini-cli routing", "bool", null, Boolean.FALSE))),
            new GroupDef("Model Fallback", Arrays.asList(
                    field("fallback_enabled", "Model fallback enabled", "bool", null, Boolean.FALSE),
                    field("auto_mode", "Auto mode enabled", "bool", null, Boolean.TRUE),
                    field("auto_mode_stage", "Auto mode stage", "select", AUTO_MODE_STAGES, null))),
            new GroupDef("Account Selection", Arrays.asList(
                    field("account_selection_strategy", "Account selection strategy", "select", ACCOUNT_STRATEGIES, "hybrid"),
                    field("pid_offset_enabled", "PID-based account offset", "bool", null, Boolean.FALSE),
                    field("switch_on_first_rate_limit", "Switch on first rate limit", "bool", null, Boolean.TRUE),
                    field("scheduling_mode", "Scheduling mode", "select", SCHEDULING_MODES, "cache_first"),
                    field("max_cache_first_wait_seconds", "Max cache-first wait (s)", "number", null, 60L),
                    field("failure_ttl_seconds", "Failure TTL (s)", "number", null, 3600L))),
            new GroupDef("Retry/Backoff", Arrays.asList(
                    field("default_retry_after_seconds", "Default retry-after (s)", "number", null, 60L),
                    field("max_backoff_seconds", "Max backoff (s)", "number", null, 60L),
                    field("request_jitter_max_ms", "Request jitter max (ms)", "number", null, 0L))),
            new GroupDef("Quota", Arrays.asList(
                    field("soft_quota_threshold_percent", "Soft quota threshold (%)", "number", null, 90L),
                    field("quota_refresh_interval_minutes", "Quota refresh interval (min)", "number", null, 15L),
                    field("soft_quota_cache_ttl_minutes", "Soft quota cache TTL (minutes, or \"auto\")", "text", null, "auto"))),
            new GroupDef("Health Score", Arrays.asList(
                    field("health_score.initial", "Initial score", "number", null, 70L),
                    field("health_score.success_reward", "Success reward", "number", null, 1L),
                    field("health_score.rate_limit_penalty", "Rate-limit penalty", "number", null, -10L),
                    field("health_score.failure_penalty", "Failure penalty", "number", null, -20L),
                    field("health_score.recovery_rate_per_hour", "Recovery rate/hour", "number", null, 2L),
                    field("health_score.min_usable", "Min usable score", "number", null, 50L),
                    field("health_score.max_score", "Max score", "number", null, 100L))),
            new GroupDef("Token Bucket", Arrays.asList(
                    field("token_bucket.max_tokens", "Max tokens", "number", null, 50L),
                    field("token_bucket.regeneration_rate_per_minute", "Regeneration rate/min", "number", null, 6L),
                    field("token_bucket.initial_tokens", "Initial tokens", "number", null, 50L))),
            new GroupDef("Updates", Collections.singletonList(
                    field("auto_update", "Auto-update plugin", "bool", null, Boolean.TRUE))));

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
                    // Explicit null clears an optional field (log_dir/auto_mode_stage) back to
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
