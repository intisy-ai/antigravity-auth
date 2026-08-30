import { timeoutFetch } from "@intisy-ai/basekit/auth";

// Antigravity version pool for the User-Agent. A real user runs one of many released versions (mostly
// recent ones) and auto-updates over time, so a single hardcoded version is an obvious fingerprint.
// This module owns the pool itself (curated fallback plus runtime refresh from the public release
// feed); the entropy-bearing pick/drift decisions (weighted-newer pick, per-account drift, jittered
// scheduling) live in Java (AntigravityVersions / AntigravityHandleRouting.driftAccountVersions),
// called via the TeaVM prod exports `pickVersionProd` and `driftAccountVersionsProd`.

// Curated fallback (newest-first) of real Antigravity releases. Used before/if the live refresh fails
// so the pool is never empty or stale-to-one-value.
const FALLBACK_VERSIONS = [
  "2.1.1", "2.0.4", "2.0.3", "2.0.2", "2.0.1",
  "1.23.2", "1.22.2", "1.21.9", "1.21.6", "1.20.6",
  "1.19.6", "1.18.4", "1.18.3",
];

// Public release mirror (tag_name = "v2.1.1", …). GitHub API, no auth needed.
const RELEASES_URL = "https://api.github.com/repos/BOTOOM/google-antigravity-bin-arch/releases";
const REFRESH_TTL_MS = 6 * 60 * 60 * 1000;   // re-fetch at most every 6h per process

let versionList = FALLBACK_VERSIONS.slice();
let lastFetchAt = 0;
let fetching = false;

const SEMVER = /^\d+\.\d+\.\d+$/;

function cmpSemver(a: string, b: string): number {
  const pa = String(a).split(".").map(Number);
  const pb = String(b).split(".").map(Number);
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d;
  }
  return 0;
}

// merge fetched + fallback, keep valid semver, dedupe, sort newest-first
function normalize(versions: string[]): string[] {
  const set = new Set<string>();
  for (const v of versions) if (SEMVER.test(v)) set.add(v);
  for (const v of FALLBACK_VERSIONS) set.add(v);
  return [...set].sort((a, b) => cmpSemver(b, a));
}

export function getVersionList() {
  return versionList;
}

// Pure and deterministic (no entropy), kept in TS rather than routed through Java: its only consumer
// (constants.ts's getAntigravityHeaders, used synchronously across ~7 call sites) would otherwise need
// an async ripple (or a circular import back into driver/javaHandle.ts) for a one-line,
// zero-correlation-risk accessor. The entropy-bearing pick/drift live in Java exclusively.
export function getNewestVersion() {
  return versionList[0] || FALLBACK_VERSIONS[0];
}

// Best-effort, throttled, non-blocking refresh of the pool from the release feed.
// Never throws; on any failure the existing (curated) list stays in place.
export async function refreshVersions(log?: (message: string) => void) {
  const now = Date.now();
  if (fetching || now - lastFetchAt < REFRESH_TTL_MS) return;
  fetching = true;
  lastFetchAt = now;
  try {
    const res = await timeoutFetch(RELEASES_URL, {
      headers: { Accept: "application/vnd.github+json", "User-Agent": "antigravity-auth" },
    }, 8000);
    if (!res.ok) return;
    const data = await res.json();
    if (!Array.isArray(data)) return;
    const fetched = data
      .map((r: { tag_name?: string }) => String(r && r.tag_name || "").replace(/^v/, "").trim())
      .filter((v: string) => SEMVER.test(v));
    if (fetched.length) {
      versionList = normalize(fetched);
      if (log) log("antigravity versions refreshed: " + versionList.slice(0, 5).join(", ") + " (" + versionList.length + " total)");
    }
  } catch { /* keep existing list */ } finally { fetching = false; }
}
