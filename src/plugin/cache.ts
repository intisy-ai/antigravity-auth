import { createHash } from "node:crypto";
import { SignatureCache, createSignatureCache } from "./cache/signature-cache";
import type { SignatureCacheConfig } from "./config";

interface SignatureEntry {
  signature: string;
  timestamp: number;
}


const signatureCache = new Map<string, Map<string, SignatureEntry>>();


const SIGNATURE_CACHE_TTL_MS = 60 * 60 * 1000;


const MAX_ENTRIES_PER_SESSION = 100;

// 16 hex chars = 64-bit key space; keeps memory bounded while making collisions extremely unlikely.
const SIGNATURE_TEXT_HASH_HEX_LEN = 16;


let diskCache: SignatureCache | null = null;

/**
 * Construct (or, when disabled, keep inert) the disk-backed signature cache from config.
 * Called once at driver startup (driver/index.ts) after config is loaded. `createSignatureCache`
 * itself returns null when `config.enabled` is false (or config is absent), so a disabled/missing
 * config leaves `diskCache` null and cacheSignature/getCachedSignature fall back to memory-only,
 * i.e. inert.
 */
export function initSignatureCache(config: SignatureCacheConfig | undefined): void {
  diskCache = createSignatureCache(config);
}

/** Test-only accessor for the constructed instance (or null when disabled/uninitialized). */
export function getDiskCacheForTesting(): SignatureCache | null {
  return diskCache;
}

/**
 * Hashes text content into a stable, Unicode-safe key.
 *
 * Uses SHA-256 over UTF-8 bytes and truncates to keep memory usage bounded.
 */
function hashText(text: string): string {
  return createHash("sha256").update(text, "utf8").digest("hex").slice(0, SIGNATURE_TEXT_HASH_HEX_LEN);
}

/**
 * Create a disk cache key from sessionId and textHash.
 */
function makeDiskKey(sessionId: string, textHash: string): string {
  return `${sessionId}:${textHash}`;
}

/**
 * Caches a thinking signature for a given session and text.
 * Used for Claude models that require signed thinking blocks in multi-turn conversations.
 * Also writes to disk cache if enabled.
 */
export function cacheSignature(sessionId: string, text: string, signature: string): void {
  if (!sessionId || !text || !signature) return;

  const textHash = hashText(text);


  let sessionMemCache = signatureCache.get(sessionId);
  if (!sessionMemCache) {
    sessionMemCache = new Map();
    signatureCache.set(sessionId, sessionMemCache);
  }


  if (sessionMemCache.size >= MAX_ENTRIES_PER_SESSION) {
    const now = Date.now();
    for (const [key, entry] of sessionMemCache.entries()) {
      if (now - entry.timestamp > SIGNATURE_CACHE_TTL_MS) {
        sessionMemCache.delete(key);
      }
    }

    if (sessionMemCache.size >= MAX_ENTRIES_PER_SESSION) {
      const entries = Array.from(sessionMemCache.entries())
        .sort((a, b) => a[1].timestamp - b[1].timestamp);
      const toRemove = entries.slice(0, Math.floor(MAX_ENTRIES_PER_SESSION / 4));
      for (const [key] of toRemove) {
        sessionMemCache.delete(key);
      }
    }
  }

  sessionMemCache.set(textHash, { signature, timestamp: Date.now() });


  if (diskCache) {
    const diskKey = makeDiskKey(sessionId, textHash);
    diskCache.store(diskKey, signature);
  }
}

/**
 * Retrieves a cached signature for a given session and text.
 * Checks memory first, then falls back to disk cache.
 * Returns undefined if not found or expired.
 */
export function getCachedSignature(sessionId: string, text: string): string | undefined {
  if (!sessionId || !text) return undefined;

  const textHash = hashText(text);


  const sessionMemCache = signatureCache.get(sessionId);
  if (sessionMemCache) {
    const entry = sessionMemCache.get(textHash);
    if (entry) {

      if (Date.now() - entry.timestamp > SIGNATURE_CACHE_TTL_MS) {
        sessionMemCache.delete(textHash);
      } else {
        return entry.signature;
      }
    }
  }


  if (diskCache) {
    const diskKey = makeDiskKey(sessionId, textHash);
    const diskValue = diskCache.retrieve(diskKey);
    if (diskValue) {

      let memCache = signatureCache.get(sessionId);
      if (!memCache) {
        memCache = new Map();
        signatureCache.set(sessionId, memCache);
      }
      memCache.set(textHash, { signature: diskValue, timestamp: Date.now() });
      return diskValue;
    }
  }

  return undefined;
}

export { SignatureCache, createSignatureCache } from "./cache/signature-cache";
export type { SignatureCacheConfig } from "./config";
