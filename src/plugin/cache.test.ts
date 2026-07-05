import { beforeEach, describe, expect, it, vi } from "vitest";

import { cacheSignature, getCachedSignature } from "./cache";

describe("Signature Cache", () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  describe("cacheSignature", () => {
    it("caches a signature for session and text", () => {
      cacheSignature("session1", "thinking text", "sig123");
      const result = getCachedSignature("session1", "thinking text");
      expect(result).toBe("sig123");
    });

    it("does nothing when sessionId is empty", () => {
      cacheSignature("", "text", "sig");
      expect(getCachedSignature("", "text")).toBeUndefined();
    });

    it("does nothing when text is empty", () => {
      cacheSignature("session", "", "sig");
      expect(getCachedSignature("session", "")).toBeUndefined();
    });

    it("does nothing when signature is empty", () => {
      cacheSignature("session", "text", "");
      expect(getCachedSignature("session", "text")).toBeUndefined();
    });

    it("stores multiple signatures per session", () => {
      cacheSignature("session1", "text1", "sig1");
      cacheSignature("session1", "text2", "sig2");

      expect(getCachedSignature("session1", "text1")).toBe("sig1");
      expect(getCachedSignature("session1", "text2")).toBe("sig2");
    });

    it("stores signatures for different sessions independently", () => {
      cacheSignature("session1", "text", "sig1");
      cacheSignature("session2", "text", "sig2");

      expect(getCachedSignature("session1", "text")).toBe("sig1");
      expect(getCachedSignature("session2", "text")).toBe("sig2");
    });
  });

  describe("getCachedSignature", () => {
    it("returns undefined when session not found", () => {
      expect(getCachedSignature("unknown", "text")).toBeUndefined();
    });

    it("returns undefined when text not found in session", () => {
      cacheSignature("session", "known-text", "sig");
      expect(getCachedSignature("session", "unknown-text")).toBeUndefined();
    });

    it("returns undefined when sessionId is empty", () => {
      expect(getCachedSignature("", "text")).toBeUndefined();
    });

    it("returns undefined when text is empty", () => {
      expect(getCachedSignature("session", "")).toBeUndefined();
    });

    it("returns undefined when signature is expired", () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(0));

      cacheSignature("session", "text", "sig");


      vi.setSystemTime(new Date(3600001));

      expect(getCachedSignature("session", "text")).toBeUndefined();
    });

    it("returns signature when not expired", () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(0));

      cacheSignature("session", "text", "sig");


      vi.setSystemTime(new Date(3599999));

      expect(getCachedSignature("session", "text")).toBe("sig");
    });
  });

  describe("cache eviction", () => {
    it("evicts entries when at capacity", () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(0));

      // Fill cache with 100 entries (MAX_ENTRIES_PER_SESSION)
      for (let i = 0; i < 100; i++) {
        vi.setSystemTime(new Date(i * 1000)); // stagger timestamps
        cacheSignature("session", `text-${i}`, `sig-${i}`);
      }


      vi.setSystemTime(new Date(100 * 1000));


      cacheSignature("session", "new-text", "new-sig");


      expect(getCachedSignature("session", "new-text")).toBe("new-sig");


      expect(getCachedSignature("session", "text-0")).toBeUndefined();
    });
  });
});
