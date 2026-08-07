// One upstream account serves two lanes, and one fetch returns both lanes' models. Filing
// them all under whichever lane did the fetch is what made antigravity report twice its real
// model count while gemini-cli reported none.
import { describe, it, expect } from "vitest";
import { laneOf, catalogForLane } from "../driver/index.js";

const CATALOG = {
  models: {
    "antigravity-auto": { name: "Auto" },
    "antigravity-claude-sonnet-4-6": { name: "Claude Sonnet 4.6" },
    "antigravity-gemini-3-flash-agent": { name: "Gemini 3 Flash Agent" },
    "gemini-2.5-pro": { name: "Gemini 2.5 Pro" },
    "gemini-3-pro-preview": { name: "Gemini 3 Pro" },
  },
  ranking: ["antigravity-claude-sonnet-4-6", "gemini-3-pro-preview", "antigravity-gemini-3-flash-agent", "gemini-2.5-pro"],
  defaultModelId: "antigravity-claude-sonnet-4-6",
};

describe("model lanes", () => {
  it("reads a metered model by its own prefix and everything else as the free pool", () => {
    expect(laneOf("antigravity-claude-sonnet-4-6")).toBe("antigravity");
    // A gemini model the metered lane serves is still antigravity's, because it carries the prefix.
    expect(laneOf("antigravity-gemini-3-flash-agent")).toBe("antigravity");
    expect(laneOf("gemini-2.5-pro")).toBe("gemini-cli");
  });

  it("gives each lane only its own models", () => {
    expect(Object.keys(catalogForLane(CATALOG, "antigravity").models))
      .toEqual(["antigravity-auto", "antigravity-claude-sonnet-4-6", "antigravity-gemini-3-flash-agent"]);
    expect(Object.keys(catalogForLane(CATALOG, "gemini-cli").models))
      .toEqual(["gemini-2.5-pro", "gemini-3-pro-preview"]);
  });

  it("keeps each lane's ranking in the catalog's order, without the other lane's entries", () => {
    expect(catalogForLane(CATALOG, "gemini-cli").ranking).toEqual(["gemini-3-pro-preview", "gemini-2.5-pro"]);
    expect(catalogForLane(CATALOG, "antigravity").ranking)
      .toEqual(["antigravity-claude-sonnet-4-6", "antigravity-gemini-3-flash-agent"]);
  });

  // A default belonging to the other lane would point at a model this provider cannot serve.
  it("drops a default model that belongs to the other lane", () => {
    expect(catalogForLane(CATALOG, "antigravity").defaultModelId).toBe("antigravity-claude-sonnet-4-6");
    expect(catalogForLane(CATALOG, "gemini-cli").defaultModelId).toBeUndefined();
  });

  // Returning an empty catalog would overwrite a good cache with nothing; null leaves it alone.
  it("reports nothing rather than an empty catalog when a lane has no models", () => {
    expect(catalogForLane({ models: { "gemini-2.5-pro": {} }, ranking: ["gemini-2.5-pro"] }, "antigravity")).toBeNull();
    expect(catalogForLane(null, "antigravity")).toBeNull();
  });
});
