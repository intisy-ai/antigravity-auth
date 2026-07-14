package io.github.intisy.ai.antigravity;

/**
 * Small hand-rolled helper reproducing the JS runtime truthiness this port's TS sources rely on
 * implicitly ({@code if (x)}, {@code x || fallback}, {@code x ?? fallback}) -- kept
 * package-private and shared by every ported class rather than duplicated. Copied from
 * claude-code-auth's {@code io.github.intisy.ai.claude.JsCoercion} (package adjusted); antigravity
 * T7a's sources never need JS {@code Number()}/{@code parseFloat}/{@code parseInt} coercion (no
 * header-string parsing in this slice), so only {@link #isTruthy} is carried over.
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
}
