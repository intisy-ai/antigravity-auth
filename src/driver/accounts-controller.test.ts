// @ts-nocheck
import { describe, it, expect } from "vitest";
import { accountHasQuota } from "./accounts-controller.js";

describe("accountHasQuota (antigravity)", () => {
  it("true when any pool has remaining fraction > 0", () => {
    expect(accountHasQuota({ meta: { cachedQuota: { Gemini: { remainingFraction: 0.4 }, Claude: { remainingFraction: 0 } } } })).toBe(true);
  });
  it("false when all pools exhausted", () => {
    expect(accountHasQuota({ meta: { cachedQuota: { Gemini: { remainingFraction: 0 } } } })).toBe(false);
  });
  it("false when quota unknown", () => {
    expect(accountHasQuota({ meta: {} })).toBe(false);
    expect(accountHasQuota({})).toBe(false);
  });
});
