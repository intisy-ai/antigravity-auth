package io.github.intisy.ai.antigravity;

import java.util.List;
import java.util.Map;

/**
 * Small hand-rolled helper reproducing the JS runtime truthiness this port's TS sources rely on
 * implicitly ({@code if (x)}, {@code x || fallback}, {@code x ?? fallback}) -- kept
 * package-private and shared by every ported class rather than duplicated. Copied from
 * claude-code-auth's {@code io.github.intisy.ai.claude.JsCoercion} (package adjusted). T7b extends
 * it with the {@code ||}/{@code ??} operand helpers and the {@code isPlainObject} predicate the
 * transform-layer tree walks need (still no {@code Number()}/{@code parseFloat} string coercion --
 * that slice never parses header strings).
 */
final class JsCoercion {

    private JsCoercion() {
    }

    // Matches JS's `if (x)` truthiness: false for null/undefined, false, "", 0/-0/NaN; every
    // other value (including empty objects/arrays, which this port represents as Map/List) is
    // truthy.
    static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d != 0.0 && !Double.isNaN(d);
        }
        return true;
    }

    // JS `a ?? fallback` (nullish coalescing): the JSON tree represents both `undefined` (absent
    // map key) and `null` as Java null, so only null falls through to the fallback -- `false`, `0`
    // and `""` are kept.
    static Object nullish(Object value, Object fallback) {
        return value != null ? value : fallback;
    }

    // JS `a || b || ...`: returns the first truthy operand, else the LAST operand (JS returns the
    // final value when every operand is falsy -- callers rely on that for their default fallback).
    static Object firstTruthy(Object... values) {
        if (values.length == 0) return null;
        for (Object v : values) {
            if (isTruthy(v)) return v;
        }
        return values[values.length - 1];
    }

    // JS `typeof x === "object" && x !== null && !Array.isArray(x)`: in this port a plain object is
    // a Map (arrays are Lists, which are not Maps).
    static boolean isPlainObject(Object v) {
        return v instanceof Map;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object v) {
        return (List<Object>) v;
    }
}
