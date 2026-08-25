package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class AntigravityFingerprintTest {

    private final JsonCodec json = new TestJsonCodec();

    @Test
    void platformToDisplayName() {
        assertEquals("WINDOWS", AntigravityFingerprint.platformToDisplayName("win32"));
        assertEquals("MACOS", AntigravityFingerprint.platformToDisplayName("darwin"));
        assertEquals("MACOS", AntigravityFingerprint.platformToDisplayName("linux"));
    }

    @Test
    void buildFingerprintHeaders_null() {
        assertEquals("{}", json.stringify(AntigravityFingerprint.buildFingerprintHeaders(null)));
    }

    @Test
    void buildFingerprintHeaders_fp() {
        Map<String, Object> fp = map("userAgent", "antigravity/1.2.3 win32/x64");
        assertEquals("{\"User-Agent\":\"antigravity/1.2.3 win32/x64\"}",
                json.stringify(AntigravityFingerprint.buildFingerprintHeaders(fp)));
    }
}
