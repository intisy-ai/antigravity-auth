import { describe, expect, it } from "vitest";
import { getNewestVersion, getVersionList } from "./versions.js";
import { loadOrchestrator } from "../driver/javaHandle.js";

const SEMVER = /^\d+\.\d+\.\d+$/;

function cmp(a: string, b: string): number {
  const pa = a.split(".").map(Number);
  const pb = b.split(".").map(Number);
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d;
  }
  return 0;
}

describe("versions pool", () => {
  it("exposes a non-empty, newest-first, valid-semver list", () => {
    const list = getVersionList();
    expect(list.length).toBeGreaterThan(0);
    for (const v of list) expect(v).toMatch(SEMVER);
    for (let i = 1; i < list.length; i++) expect(cmp(list[i - 1], list[i])).toBeGreaterThanOrEqual(0);
  });

  it("newest version is the max of the list", () => {
    const list = getVersionList();
    const max = list.reduce((a, b) => (cmp(b, a) > 0 ? b : a), list[0]);
    expect(getNewestVersion()).toBe(max);
  });
});

// pickVersion/driftVersion/nextVersionDriftDelay/driftAccountVersions run in Java. The fixed-random
// expected values below are the same frozen ground truth used by java/antigravity-provider's
// AntigravityVersionsTest / AntigravityHandleRoutingTest, reused here so both suites assert against one
// recorded ground truth.
const fixedRandom = (value: number) => () => value;
const POOL_JSON = JSON.stringify([
  "2.1.1", "2.0.4", "2.0.3", "2.0.2", "2.0.1",
  "1.23.2", "1.22.2", "1.21.9", "1.21.6", "1.20.6",
  "1.19.6", "1.18.4", "1.18.3",
]);

describe("versions parity: Java prod exports vs the frozen TS fixture", () => {
  it("pickVersionProd matches AntigravityVersionsTest's frozen picks", async () => {
    const orchestrator = await loadOrchestrator();
    expect(orchestrator.pickVersionProd(POOL_JSON, "", fixedRandom(0.0))).toBe("2.1.1");
    expect(orchestrator.pickVersionProd(POOL_JSON, "1.21.6", fixedRandom(0.0))).toBe("2.1.1");
    expect(orchestrator.pickVersionProd(POOL_JSON, "9.9.9", fixedRandom(0.0))).toBe("2.1.1");
    expect(orchestrator.pickVersionProd(POOL_JSON, "", fixedRandom(0.999999))).toBe("1.20.6");
  });

  it("driftAccountVersionsProd matches AntigravityHandleRoutingTest's frozen snapshot", async () => {
    const orchestrator = await loadOrchestrator();
    const now = 1700000000000;
    const accounts = [
      { id: "d1", meta: { fingerprint: { userAgent: "antigravity/1.18.3 (x)", version: "1.18.3" } } },
      { id: "d2", meta: { fingerprint: { userAgent: "antigravity/1.18.3 (x)", version: "1.18.3", nextVersionDriftAt: now - 1000 } } },
      { id: "d3", meta: {} }, // no fingerprint.userAgent -> skipped
    ];
    const drifts = JSON.parse(orchestrator.driftAccountVersionsProd(
      JSON.stringify(accounts), now, POOL_JSON, fixedRandom(0.5),
    ));
    expect(drifts).toHaveLength(2);
    expect(drifts[0]).toMatchObject({ accountId: "d1", scheduleOnly: true, nextVersionDriftAt: 1702116800000 });
    expect(drifts[1]).toMatchObject({
      accountId: "d2", scheduleOnly: false, userAgent: "antigravity/2.0.4 (x)",
      version: "2.0.4", versionPickedAt: now, nextVersionDriftAt: 1702116800000,
    });
  });
});
