package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.ConfigField;
import io.github.intisy.ai.shared.routing.ConfigGroup;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-E/E-D: {@link AntigravityConfig} is a GENUINELY NEW capability (no {@code /v1/config} URL
 * branch existed in the Java provider before this migration). Covers the schema shape (grouped,
 * dotted keys for the flattened nested TS groups), GET/PUT round-tripping against a {@link Store}
 * test double, invalid-value handling, and -- the store-threading rule -- that {@link
 * AntigravityProvider#putConfigValues}/{@link AntigravityProvider#getConfigValues} serve from
 * {@link HandlerCtx#store} when the host injects one, never a self-assembled {@code FileStore}.
 */
class AntigravityConfigTest {

    @Test
    void schema_hasGroupedFields_matchingDefaultConfigShape() {
        ConfigSchema schema = new AntigravityProvider().configSchema(new HandlerCtx());
        assertFalse(schema.groups.isEmpty());

        int totalFields = 0;
        boolean sawNestedKey = false;
        for (ConfigGroup g : schema.groups) {
            assertNotNull(g.title);
            for (ConfigField f : g.fields) {
                totalFields++;
                assertNotNull(f.key);
                assertNotNull(f.type);
                if (f.key.contains(".")) sawNestedKey = true;
            }
        }
        // schema.ts's AntigravityConfig surface was pruned to only the keys with a real runtime
        // consumer (9 pre-existing functional keys + the 3 E-wired features), so the field count
        // is much smaller than the old ~50-setting historical schema.
        assertTrue(totalFields >= 12, "expected >= 12 settings, found " + totalFields);
        assertTrue(sawNestedKey, "nested TS groups must flatten to dotted ConfigField keys");
    }

    @Test
    void getValues_noPersistedFile_returnsDefaults(@TempDir Path configDir) {
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), noopHttp());

        Map<String, Object> values = AntigravityConfig.getValues(backend);

        assertEquals(Boolean.FALSE, values.get("debug"));
        assertEquals("hybrid", values.get("account_selection_strategy"));
        assertEquals(Boolean.TRUE, values.get("signature_cache.enabled"));
        assertEquals(0L, ((Number) values.get("request_jitter_max_ms")).longValue());
        assertEquals(Boolean.FALSE, values.get("cli_first"));
    }

    @Test
    void putValues_persistsOverride_andRoundTripsViaInjectedStore() {
        InMemoryStore store = new InMemoryStore();
        AntigravityBackend backend = AntigravityBackend.forTest(store, noopHttp());

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("debug", Boolean.TRUE);
        incoming.put("signature_cache.enabled", Boolean.FALSE);
        incoming.put("account_selection_strategy", "round-robin");
        incoming.put("bogus_unknown_key", "ignored"); // not a declared field -> must be dropped

        Map<String, Object> updated = AntigravityConfig.putValues(backend, incoming);
        assertEquals(Boolean.TRUE, updated.get("debug"));
        assertEquals(Boolean.FALSE, updated.get("signature_cache.enabled"));
        assertEquals("round-robin", updated.get("account_selection_strategy"));
        assertFalse(updated.containsKey("bogus_unknown_key"));

        // Round trip through a FRESH backend built from the SAME injected store -- proves
        // persistence went through the store the test injected, not a per-instance cache.
        AntigravityBackend reread = AntigravityBackend.forTest(store, noopHttp());
        Map<String, Object> values = AntigravityConfig.getValues(reread);
        assertEquals(Boolean.TRUE, values.get("debug"));
        assertEquals(Boolean.FALSE, values.get("signature_cache.enabled"));
        // An untouched sibling of the same nested group must keep its default.
        assertEquals(3600L, ((Number) values.get("signature_cache.memory_ttl_seconds")).longValue());

        String raw = store.raw("antigravity.json");
        assertNotNull(raw);
        assertTrue(raw.contains("\"signature_cache\""),
                "the nested TS group shape must survive persistence (not flattened on disk)");
    }

    @Test
    void putValues_invalidSelectValue_isIgnored_keepingPriorOverride() {
        InMemoryStore store = new InMemoryStore();
        AntigravityBackend backend = AntigravityBackend.forTest(store, noopHttp());
        AntigravityConfig.putValues(backend, singleton("account_selection_strategy", "sticky"));

        Map<String, Object> updated = AntigravityConfig.putValues(
                backend, singleton("account_selection_strategy", "not-a-real-strategy"));

        assertEquals("sticky", updated.get("account_selection_strategy"),
                "an invalid select value must be ignored, keeping the prior override");
    }

    @Test
    void putValues_explicitNull_clearsAnOptionalField() {
        InMemoryStore store = new InMemoryStore();
        AntigravityBackend backend = AntigravityBackend.forTest(store, noopHttp());
        AntigravityConfig.putValues(backend, singleton("log_dir", "/tmp/antigravity"));

        Map<String, Object> updated = AntigravityConfig.putValues(backend, singleton("log_dir", null));

        assertEquals(null, updated.get("log_dir"));
    }

    @Test
    void provider_putThenGetConfigValues_usesInjectedStore_notASelfAssembledFileStore(@TempDir Path configDir) {
        InMemoryStore store = new InMemoryStore();
        AntigravityBackend backend = AntigravityBackend.forTest(store, noopHttp());
        AntigravityBackend.registerForTest(store, backend);

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString(); // a real dir is ALSO present...
        ctx.store = store; // ...but store-threading requires the injected store to win.

        AntigravityProvider provider = new AntigravityProvider();
        provider.putConfigValues(ctx, singleton("debug", Boolean.TRUE));
        Map<String, Object> values = provider.getConfigValues(ctx);

        assertEquals(Boolean.TRUE, values.get("debug"));
        String raw = store.raw("antigravity.json");
        assertNotNull(raw, "putConfigValues via HandlerCtx.store must write to the INJECTED store");
        assertTrue(raw.contains("debug"));
        // No file should ever appear under configDir -- proves no FileStore was self-assembled.
        assertFalse(configDir.resolve("antigravity.json").toFile().exists());
    }

    private static Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static HttpClient noopHttp() {
        return req -> {
            throw new IllegalStateException("no HTTP call expected in a config test: " + req.url);
        };
    }

    /** Minimal in-memory {@link Store} test double (config/OAuth capability tests). */
    static final class InMemoryStore implements Store {
        private final Map<String, String> data = new LinkedHashMap<>();

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public void put(String key, String value) {
            data.put(key, value);
        }

        @Override
        public boolean exists(String key) {
            return data.containsKey(key);
        }

        @Override
        public void delete(String key) {
            data.remove(key);
        }

        @Override
        public void update(String key, UnaryOperator<String> mutator) {
            data.put(key, mutator.apply(data.get(key)));
        }

        @Override
        public List<String> listKeys(String prefix) {
            List<String> out = new ArrayList<>();
            for (String k : data.keySet()) {
                if (k.startsWith(prefix)) out.add(k);
            }
            return out;
        }

        String raw(String key) {
            return data.get(key);
        }
    }
}
