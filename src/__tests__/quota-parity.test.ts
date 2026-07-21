// @ts-nocheck
// Verifies the AntigravityQuotaParser exports (via AntigravityProviderJs: antigravityStatus/
// antigravityAvailableAt/antigravityQuota, plus aggregateQuota's internal familyLabel classification)
// produce the frozen output (quota-scenarios.expected.json) for representative accounts (disabled,
// verification-required, cooling-down, active/exhausted quota, lane-rate-limited, and no-quota-yet).
import { describe, it, expect } from "vitest";
import { loadOrchestrator } from "../driver/javaHandle.js";
import fixture from "./quota-scenarios.expected.json";

describe("quota parity: Java quota-view exports vs the frozen TS fixture", () => {
  it("antigravityStatus/antigravityAvailableAt/antigravityQuota byte-match for every representative account", async () => {
    const orchestrator = await loadOrchestrator();
    for (const [name, account] of Object.entries(fixture.accounts)) {
      const expected = fixture.results[name];
      const status = orchestrator.antigravityStatus(JSON.stringify(account), fixture.now);
      const availableAt = orchestrator.antigravityAvailableAt(JSON.stringify(account), fixture.now);
      const quotaJson = orchestrator.antigravityQuota(JSON.stringify(account));
      const quota = quotaJson == null ? null : JSON.parse(quotaJson);

      expect(status).toBe(expected.status);
      expect(availableAt === Infinity ? "Infinity" : availableAt).toEqual(expected.availableAt);
      expect(quota).toEqual(expected.quota);
    }
  });

  it("aggregateQuota's internal familyLabel classification matches the frozen fixture", async () => {
    const orchestrator = await loadOrchestrator();
    const models = {
      "antigravity-claude-sonnet-4-6": { quotaInfo: { remainingFraction: 0.5, resetTime: "2026-07-18T00:00:00.000Z" } },
      "gpt-oss-120b": { quotaInfo: { remainingFraction: 0.5, resetTime: "2026-07-18T00:00:00.000Z" } },
      "gemini-3-pro": { quotaInfo: { remainingFraction: 0.5, resetTime: "2026-07-18T00:00:00.000Z" } },
      "some-other-model": { quotaInfo: { remainingFraction: 0.5, resetTime: "2026-07-18T00:00:00.000Z" } },
    };
    const result = JSON.parse(orchestrator.aggregateQuota(JSON.stringify(models)));
    // "some-other-model" carries no recognized family (familyLabel -> null) and is dropped, matching
    // fixture.familyLabels.unknown === null.
    expect(Object.keys(result).sort()).toEqual(["Claude", "GPT-OSS", "Gemini"]);
    expect(fixture.familyLabels).toEqual({ claude: "Claude", gptOss: "GPT-OSS", gemini: "Gemini", unknown: null });
  });
});
