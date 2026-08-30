// Verifies buildCatalogViaJava (javaHandle.ts, calling AntigravityProviderJs's buildCatalog export ->
// AntigravityCatalog.buildAntigravityCatalog) produces the frozen output
// (catalog-scenarios.expected.json) for a representative fetchAvailableModels payload: effort-variant
// grouping, deprecated/image-generation exclusion, the Gemini CLI pool, and default-model group-remapping.
import { describe, it, expect } from "vitest";
import { buildCatalogViaJava } from "../driver/javaHandle.js";
import scenarios from "./catalog-scenarios.expected.json";

describe("catalog parity: Java buildCatalog vs the frozen TS fixture", () => {
  for (const [name, sc] of Object.entries(scenarios)) {
    it(`${name}: buildCatalogViaJava byte-matches the frozen TS fixture`, async () => {
      const result = await buildCatalogViaJava(sc.payload);
      expect(result).toEqual(sc.expected);
    });
  }
});
