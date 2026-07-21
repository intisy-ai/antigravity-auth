import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { cacheSignature, getCachedSignature, getDiskCacheForTesting, initSignatureCache } from "./cache";

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

  // The disk-backed SignatureCache module (cache/signature-cache.ts) is constructed by
  // initSignatureCache; when config disables it `diskCache` stays null.
  describe("initSignatureCache (disk-backed signature cache wiring)", () => {
    // Hermetic disk dir: SignatureCache resolves its cache file from XDG_CONFIG_HOME/APPDATA
    // at construct time, so point both at a fresh temp dir per test. Without this the cache
    // reads/writes the REAL shared config dir, making memoryEntries/dirty non-deterministic
    // under the parallel full-suite run (and polluting the user's real config).
    let prevXdg;
    let prevAppData;
    let tmpConfigDir;
    beforeEach(() => {
      prevXdg = process.env.XDG_CONFIG_HOME;
      prevAppData = process.env.APPDATA;
      tmpConfigDir = mkdtempSync(join(tmpdir(), "antigravity-sigcache-"));
      process.env.XDG_CONFIG_HOME = tmpConfigDir;
      process.env.APPDATA = tmpConfigDir;
    });
    afterEach(() => {
      // Always shut down any constructed instance (stops its background timers) and reset the
      // module's diskCache handle back to null so later tests in this file see the prior (inert)
      // behavior again.
      getDiskCacheForTesting()?.shutdown();
      initSignatureCache(undefined);
      if (prevXdg === undefined) delete process.env.XDG_CONFIG_HOME; else process.env.XDG_CONFIG_HOME = prevXdg;
      if (prevAppData === undefined) delete process.env.APPDATA; else process.env.APPDATA = prevAppData;
      try { rmSync(tmpConfigDir, { recursive: true, force: true }); } catch {}
    });

    it("stays inert (diskCache null) when config is undefined, matches the prior never-constructed behavior", () => {
      initSignatureCache(undefined);
      expect(getDiskCacheForTesting()).toBeNull();
    });

    it("stays inert (diskCache null) when signature_cache.enabled is false", () => {
      initSignatureCache({ enabled: false, memory_ttl_seconds: 3600, disk_ttl_seconds: 172800, write_interval_seconds: 60 });
      expect(getDiskCacheForTesting()).toBeNull();
    });

    it("constructs a real SignatureCache instance when enabled", () => {
      initSignatureCache({ enabled: true, memory_ttl_seconds: 3600, disk_ttl_seconds: 172800, write_interval_seconds: 3600 });
      const cache = getDiskCacheForTesting();
      expect(cache).not.toBeNull();
      expect(cache!.getStats().diskEnabled).toBe(true);
    });

    it("cacheSignature writes through to the constructed disk cache when enabled", () => {
      initSignatureCache({ enabled: true, memory_ttl_seconds: 3600, disk_ttl_seconds: 172800, write_interval_seconds: 3600 });
      const cache = getDiskCacheForTesting()!;
      // Use a unique key so a real on-disk cache file left over from a prior run (this module's
      // cache path is the real shared config dir, by design -- one process-wide cache) can't make
      // this entry pre-exist; compare before/after counts rather than assuming an empty cache.
      const uniqueSession = `disk-session-${Date.now()}-${Math.random()}`;
      const before = cache.getStats().memoryEntries;

      cacheSignature(uniqueSession, "disk thinking text", "disk-sig");

      // store() landed on the constructed instance (not just the module's own in-memory Map).
      expect(cache.getStats().memoryEntries).toBe(before + 1);
      expect(cache.getStats().dirty).toBe(true);
    });

    it("cacheSignature never touches a disk cache when disabled (inert)", () => {
      initSignatureCache({ enabled: false, memory_ttl_seconds: 3600, disk_ttl_seconds: 172800, write_interval_seconds: 3600 });
      expect(getDiskCacheForTesting()).toBeNull();

      // Must not throw even though diskCache is null, and the in-memory (session Map) tier still works.
      cacheSignature("inert-session", "inert text", "inert-sig");
      expect(getCachedSignature("inert-session", "inert text")).toBe("inert-sig");
      expect(getDiskCacheForTesting()).toBeNull();
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
