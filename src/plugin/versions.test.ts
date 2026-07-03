import { describe, expect, it } from "vitest";
import { getNewestVersion, getVersionList, pickVersion, driftVersion, nextVersionDriftDelay } from "./versions.js";

const DAY = 24 * 60 * 60 * 1000;

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

  it("pickVersion always returns a version from the list", () => {
    const list = new Set(getVersionList());
    for (let i = 0; i < 50; i++) expect(list.has(pickVersion())).toBe(true);
  });

  it("is weighted toward newer versions", () => {
    const newest = getNewestVersion();
    const oldest = getVersionList()[getVersionList().length - 1];
    let newestCount = 0, oldestCount = 0;
    for (let i = 0; i < 2000; i++) {
      const v = pickVersion();
      if (v === newest) newestCount++;
      if (v === oldest) oldestCount++;
    }
    expect(newestCount).toBeGreaterThan(oldestCount);
  });

  it("pickVersion(min) never returns older than min", () => {
    const list = getVersionList();
    const min = list[Math.floor(list.length / 2)];
    for (let i = 0; i < 50; i++) expect(cmp(pickVersion(min), min)).toBeGreaterThanOrEqual(0);
  });

  it("driftVersion never downgrades", () => {
    const list = getVersionList();
    for (const current of list) {
      for (let i = 0; i < 20; i++) expect(cmp(driftVersion(current), current)).toBeGreaterThanOrEqual(0);
    }
  });

  it("driftVersion with no current falls back to a valid pick", () => {
    expect(driftVersion("")).toMatch(SEMVER);
  });
});

describe("nextVersionDriftDelay (staggering)", () => {
  it("legacy (no version) migrates within the first week", () => {
    for (let i = 0; i < 200; i++) {
      const d = nextVersionDriftDelay(false);
      expect(d).toBeGreaterThanOrEqual(0);
      expect(d).toBeLessThanOrEqual(7 * DAY);
    }
  });

  it("versioned accounts re-drift on a 2–5 week window", () => {
    for (let i = 0; i < 200; i++) {
      const d = nextVersionDriftDelay(true);
      expect(d).toBeGreaterThanOrEqual(14 * DAY);
      expect(d).toBeLessThanOrEqual(35 * DAY);
    }
  });

  it("produces scattered (not identical) delays across accounts", () => {
    const delays = new Set(Array.from({ length: 50 }, () => nextVersionDriftDelay(true)));
    expect(delays.size).toBeGreaterThan(40);   // essentially all distinct -> no lockstep
  });
});
