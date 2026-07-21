package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline tests for {@link AntigravitySchemaCleaner#clean}. Each group mirrors a pipeline phase;
 * {@link #mutateVsCopy_matchesTs()} pins the mutate-vs-copy behavior (inputMutated:false / sameRef
 * only for primitives).
 */
class AntigravitySchemaCleanerTest {

    // Empty-object placeholder property/required the cleaner injects for object schemas with no props.
    private static Map<String, Object> ph() {
        return map("_placeholder", map("type", "boolean", "description", "Placeholder. Always pass true."));
    }

    private static Map<String, Object> emptyObjectSchema() {
        return map("type", "object", "properties", ph(), "required", list("_placeholder"));
    }

    // ---- phase 1a: $ref -> hint ------------------------------------------------------------------

    @Test
    void refs() {
        assertEquals(map("type", "object", "description", "See: Foo", "properties", ph(), "required", list("_placeholder")),
                AntigravitySchemaCleaner.clean(map("$ref", "#/$defs/Foo")));
        assertEquals(map("type", "object", "description", "existing (See: Bar)", "properties", ph(), "required", list("_placeholder")),
                AntigravitySchemaCleaner.clean(map("$ref", "Bar", "description", "existing")));
        assertEquals(map("type", "object", "description", "See: ", "properties", ph(), "required", list("_placeholder")),
                AntigravitySchemaCleaner.clean(map("$ref", "#/$defs/")));
        assertEquals(map("type", "object", "properties", map("x",
                        map("type", "object", "description", "See: X", "properties", ph(), "required", list("_placeholder")))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("x", map("$ref", "#/$defs/X")))));
    }

    // ---- phase 1b: const -> enum -----------------------------------------------------------------

    @Test
    void constToEnum() {
        assertEquals(map("enum", list("foo"), "type", "string"), AntigravitySchemaCleaner.clean(map("const", "foo")));
        assertEquals(map("description", "Allowed: 42"), AntigravitySchemaCleaner.clean(map("const", 42)));
        assertEquals(map("description", "Allowed: true"), AntigravitySchemaCleaner.clean(map("const", true)));
        assertEquals(map("description", "Allowed: null"), AntigravitySchemaCleaner.clean(map("const", null)));
        assertEquals(map("enum", list("y"), "type", "string"), AntigravitySchemaCleaner.clean(map("const", "x", "enum", list("y"))));
        assertEquals(map("type", "object", "properties", map("mode", map("enum", list("fixed"), "type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("mode", map("const", "fixed")))));
    }

    // ---- phase 1c: enum hints --------------------------------------------------------------------

    @Test
    void enumHints() {
        assertEquals(map("type", "object", "properties", map("status",
                        map("type", "string", "enum", list("active", "inactive", "pending"), "description", "Allowed: active, inactive, pending"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("status",
                        map("type", "string", "enum", list("active", "inactive", "pending"))))));
        // single-value enum: no hint (length not > 1)
        assertEquals(map("type", "object", "properties", map("single", map("type", "string", "enum", list("only")))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("single", map("type", "string", "enum", list("only"))))));
        // 11 values: no hint (length > 10), still string-typed
        List<Object> eleven = list("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k");
        assertEquals(map("type", "string", "enum", eleven),
                AntigravitySchemaCleaner.clean(map("type", "string", "enum", eleven)));
    }

    // ---- phase 1c-2: enum normalization ----------------------------------------------------------

    @Test
    void normalizeEnums() {
        assertEquals(map("type", "object", "properties", map("flag", map("type", "boolean", "description", "Allowed: true"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("flag", map("type", "boolean", "enum", list(true))))));
        assertEquals(map("type", "object", "properties", map("n", map("type", "integer", "description", "Allowed: 1, 2, 3"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("n", map("type", "integer", "enum", list(1, 2, 3))))));
        assertEquals(map("type", "object", "properties", map("mode", map("enum", list("a", "b"), "description", "Allowed: a, b", "type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("mode", map("enum", list("a", "b"))))));
        assertEquals(map("type", "object", "properties", map("rules", map("type", "array", "items",
                        map("type", "object", "properties", map("negate", map("type", "boolean", "description", "Allowed: true, false")))))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("rules", map("type", "array", "items",
                        map("type", "object", "properties", map("negate", map("type", "boolean", "enum", list(true, false)))))))));
        assertEquals(map("description", "Allowed: "), AntigravitySchemaCleaner.clean(map("enum", list())));
    }

    // ---- phase 1d: additionalProperties hints ----------------------------------------------------

    @Test
    void additionalProperties() {
        assertEquals(map("type", "object", "properties", map("a", map("type", "string")), "description", "No extra properties allowed"),
                AntigravitySchemaCleaner.clean(map("type", "object", "additionalProperties", false, "properties", map("a", map("type", "string")))));
        assertEquals(map("type", "object", "properties", map("a", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "additionalProperties", true, "properties", map("a", map("type", "string")))));
        assertEquals(map("type", "object", "description", "base (No extra properties allowed)", "properties", map("a", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "additionalProperties", false, "description", "base", "properties", map("a", map("type", "string")))));
    }

    // ---- phase 1e: constraints -> description -----------------------------------------------------

    @Test
    void constraints() {
        assertEquals(map("type", "string", "description", "minLength: 1 (maxLength: 100) (pattern: ^a) (format: email)"),
                AntigravitySchemaCleaner.clean(map("type", "string", "minLength", 1, "maxLength", 100, "pattern", "^a", "format", "email")));
        assertEquals(map("type", "array", "description", "minItems: 2 (maxItems: 5)"),
                AntigravitySchemaCleaner.clean(map("type", "array", "minItems", 2, "maxItems", 5)));
        assertEquals(map("type", "number", "description", "exclusiveMinimum: 0 (exclusiveMaximum: 10) (default: 5)"),
                AntigravitySchemaCleaner.clean(map("type", "number", "exclusiveMinimum", 0, "exclusiveMaximum", 10, "default", 5)));
        // examples is an array (typeof object) -> not moved to description, dropped as unsupported
        assertEquals(map("type", "string", "description", "default: z"),
                AntigravitySchemaCleaner.clean(map("type", "string", "examples", list("x", "y"), "default", "z")));
    }

    // ---- phase 2a: allOf merge -------------------------------------------------------------------

    @Test
    void allOf() {
        assertEquals(map("type", "object", "properties", map("a", map("type", "string"), "b", map("type", "number")), "required", list("a", "b")),
                AntigravitySchemaCleaner.clean(map("type", "object", "allOf", list(
                        map("properties", map("a", map("type", "string")), "required", list("a")),
                        map("properties", map("b", map("type", "number")), "required", list("b"))))));
        // merged non-property keys (type, description) copied when absent; key order: properties, type, description
        assertEquals(map("properties", map("a", map("type", "string"), "b", map("type", "number")), "type", "object", "description", "d1"),
                AntigravitySchemaCleaner.clean(map("allOf", list(
                        map("type", "object", "properties", map("a", map("type", "string")), "description", "d1"),
                        map("properties", map("b", map("type", "number")))))));
        // existing properties/required merged + deduped
        assertEquals(map("type", "object", "properties", map("existing", map("type", "boolean"), "a", map("type", "string")), "required", list("existing", "a")),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("existing", map("type", "boolean")), "required", list("existing"),
                        "allOf", list(map("properties", map("a", map("type", "string")), "required", list("a", "existing"))))));
        // null / primitive allOf items skipped
        assertEquals(map("properties", map("a", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("allOf", list(null, "bad", map("properties", map("a", map("type", "string")))))));
    }

    // ---- phase 2b: anyOf/oneOf flatten -----------------------------------------------------------

    @Test
    void anyOfEnumMerge() {
        assertEquals(map("type", "object", "properties", map("format", map("type", "string", "enum", list("text", "markdown", "html")))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("format",
                        map("anyOf", list(map("const", "text"), map("const", "markdown"), map("const", "html")))))));
        assertEquals(map("type", "object", "properties", map("status", map("type", "string", "enum", list("pending", "active", "completed")))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("status",
                        map("oneOf", list(map("const", "pending"), map("const", "active"), map("const", "completed")))))));
        assertEquals(map("type", "object", "properties", map("level", map("type", "string", "enum", list("low", "medium", "high")))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("level",
                        map("anyOf", list(map("enum", list("low")), map("enum", list("medium")), map("enum", list("high"))))))));
        assertEquals(map("type", "object", "properties", map("color", map("type", "string", "enum", list("red", "blue", "green", "yellow")))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("color",
                        map("anyOf", list(map("enum", list("red", "blue")), map("enum", list("green", "yellow"))))))));
        // parent description preserved on enum merge
        assertEquals(map("type", "object", "properties", map("format", map("description", "Output format", "type", "string", "enum", list("text", "markdown")))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("format",
                        map("description", "Output format", "anyOf", list(map("const", "text"), map("const", "markdown")))))));
    }

    @Test
    void anyOfBestOption() {
        // object option preferred (score 3), Accepts hint from all option types
        assertEquals(map("type", "object", "properties", map("data",
                        map("type", "object", "properties", map("nested", map("type", "number")), "description", "Accepts: string | object"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("data",
                        map("anyOf", list(map("type", "string"), map("type", "object", "properties", map("nested", map("type", "number")))))))));
        // both scalar: first wins (strict >)
        assertEquals(map("type", "object", "properties", map("val", map("type", "string", "description", "Accepts: string | number"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("val",
                        map("anyOf", list(map("type", "string"), map("type", "number")))))));
        // parent + child description compose, then Accepts hint appended
        assertEquals(map("type", "string", "description", "parent (child) (Accepts: string | number)"),
                AntigravitySchemaCleaner.clean(map("anyOf", list(map("type", "string", "description", "child"), map("type", "number")), "description", "parent")));
        // empty anyOf untouched (not flattened, anyOf not an unsupported keyword)
        assertEquals(map("anyOf", list()), AntigravitySchemaCleaner.clean(map("anyOf", list())));
    }

    // ---- phase 2c: type arrays -------------------------------------------------------------------

    @Test
    void typeArrays() {
        assertEquals(map("type", "string", "description", "nullable"), AntigravitySchemaCleaner.clean(map("type", list("string", "null"))));
        assertEquals(map("type", "string", "description", "Accepts: string | number (nullable)"),
                AntigravitySchemaCleaner.clean(map("type", list("string", "number", "null"))));
        assertEquals(map("type", "string", "description", "Accepts: string | number"),
                AntigravitySchemaCleaner.clean(map("type", list("string", "number"))));
        assertEquals(map("type", "string", "description", "nullable"), AntigravitySchemaCleaner.clean(map("type", list("null"))));
        assertEquals(map("type", "string", "description", "d"), AntigravitySchemaCleaner.clean(map("type", list(), "description", "d")));
        // nullable root property dropped from required
        assertEquals(map("type", "object", "properties", map("a", map("type", "string", "description", "nullable"), "b", map("type", "number")), "required", list("b")),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("a", map("type", list("string", "null")), "b", map("type", "number")), "required", list("a", "b"))));
        // all required nullable -> required deleted
        assertEquals(map("type", "object", "properties", map("a", map("type", "string", "description", "nullable"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("a", map("type", list("string", "null"))), "required", list("a"))));
        // nested object required NOT filtered by nullable child (only root is)
        assertEquals(map("type", "object", "properties", map("outer",
                        map("type", "object", "properties", map("inner", map("type", "string", "description", "nullable")), "required", list("inner"))), "required", list("outer")),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("outer",
                        map("type", "object", "properties", map("inner", map("type", list("string", "null"))), "required", list("inner"))), "required", list("outer"))));
    }

    // ---- phase 3: unsupported keywords + x-* -----------------------------------------------------

    @Test
    void stripKeywords() {
        assertEquals(map("type", "object", "properties", map("a", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "$schema", "http://x", "$id", "id", "$comment", "c", "title", "T",
                        "propertyNames", map("pattern", "^x"), "properties", map("a", map("type", "string")))));
        // x-* stripped from a schema BODY, but a property NAMED x-* preserved
        assertEquals(map("type", "object", "properties", map("header", map("type", "string"), "plain", map("type", "number"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map(
                        "header", map("type", "string", "x-mcp-header", "Authorization", "x-foo", 1), "plain", map("type", "number")))));
        assertEquals(map("type", "object", "properties", map("x-custom", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("x-custom", map("type", "string")))));
        assertEquals(map("type", "object", "properties", map("a", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "definitions", map("Foo", map("type", "string")),
                        "$defs", map("Bar", map("type", "number")), "properties", map("a", map("type", "string")))));
    }

    // ---- phase 3b: required cleanup --------------------------------------------------------------

    @Test
    void requiredCleanup() {
        assertEquals(map("type", "object", "properties", map("a", map("type", "string")), "required", list("a")),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("a", map("type", "string")), "required", list("a", "ghost"))));
        assertEquals(map("type", "object", "properties", map("a", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("a", map("type", "string")), "required", list("ghost1", "ghost2"))));
        assertEquals(map("type", "object", "properties", map("a", map("type", "string"), "b", map("type", "number")), "required", list("a", "b")),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("a", map("type", "string"), "b", map("type", "number")), "required", list("a", "b"))));
    }

    // ---- phase 4: empty-schema placeholder -------------------------------------------------------

    @Test
    void emptyPlaceholder() {
        assertEquals(emptyObjectSchema(), AntigravitySchemaCleaner.clean(map("type", "object")));
        assertEquals(emptyObjectSchema(), AntigravitySchemaCleaner.clean(map("type", "object", "properties", map())));
        assertEquals(map("type", "object", "properties", map("a", map("type", "string"))),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("a", map("type", "string")))));
        assertEquals(map("type", "object", "properties", map("nested", emptyObjectSchema())),
                AntigravitySchemaCleaner.clean(map("type", "object", "properties", map("nested", map("type", "object")))));
        assertEquals(map(), AntigravitySchemaCleaner.clean(map()));
    }

    // ---- JS-semantics edges ----------------------------------------------------------------------

    @Test
    void edges() {
        assertNull(AntigravitySchemaCleaner.clean(null));
        assertEquals("a string", AntigravitySchemaCleaner.clean("a string"));
        assertEquals(42, AntigravitySchemaCleaner.clean(42));
        assertEquals(false, AntigravitySchemaCleaner.clean(false));
        assertEquals("", AntigravitySchemaCleaner.clean(""));
        assertEquals(list(
                        map("type", "object", "description", "See: A", "properties", ph(), "required", list("_placeholder")),
                        map("type", "string", "description", "nullable")),
                AntigravitySchemaCleaner.clean(list(map("$ref", "#/$defs/A"), map("type", list("string", "null")))));
        assertEquals(map(), AntigravitySchemaCleaner.clean(map()));
        assertEquals(map("type", "string"), AntigravitySchemaCleaner.clean(map("type", "string")));
    }

    // ---- deep / combined -------------------------------------------------------------------------

    @Test
    void combined() {
        Map<String, Object> input = map(
                "type", "object",
                "additionalProperties", false,
                "title", "Root",
                "properties", map(
                        "id", map("$ref", "#/$defs/Id"),
                        "kind", map("anyOf", list(map("const", "a"), map("const", "b"))),
                        "count", map("type", "integer", "minimum", 0, "maximum", 10, "enum", list(1, 2, 3)),
                        "tags", map("type", "array", "items", map("type", list("string", "null"))),
                        "meta", map("allOf", list(
                                map("properties", map("x", map("type", "string")), "required", list("x")),
                                map("properties", map("y", map("type", "number")))))),
                "required", list("id", "kind", "missing"));

        Map<String, Object> expected = map(
                "type", "object",
                "properties", map(
                        "id", map("type", "object", "description", "See: Id", "properties", ph(), "required", list("_placeholder")),
                        "kind", map("type", "string", "enum", list("a", "b")),
                        "count", map("type", "integer", "minimum", 0, "maximum", 10, "description", "Allowed: 1, 2, 3"),
                        "tags", map("type", "array", "items", map("type", "string", "description", "nullable")),
                        "meta", map("properties", map("x", map("type", "string"), "y", map("type", "number")), "required", list("x"))),
                "required", list("id", "kind"),
                "description", "No extra properties allowed");

        assertEquals(expected, AntigravitySchemaCleaner.clean(input));
    }

    // ---- mutate-vs-copy fidelity -----------------------------------------------------------------

    @Test
    void mutateVsCopy_matchesTs() {
        // object input: NOT mutated, output is a new tree (inputMutated:false / sameRef:false)
        Map<String, Object> input = map("type", "object", "properties", map("a", map("type", list("string", "null"))), "required", list("a"));
        Map<String, Object> snapshot = map("type", "object", "properties", map("a", map("type", list("string", "null"))), "required", list("a"));
        Object out = AntigravitySchemaCleaner.clean(input);
        assertEquals(snapshot, input, "input must not be mutated");
        assertNotSame(input, out, "object input must yield a new tree");

        // primitive input: returned as-is (sameRef:true)
        String prim = "x";
        assertSame(prim, AntigravitySchemaCleaner.clean(prim));
        assertNull(AntigravitySchemaCleaner.clean(null));
    }
}
