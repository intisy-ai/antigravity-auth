import { describe, expect, it } from "vitest";
import { getNewestVersion, getVersionList } from "./versions.js";

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
