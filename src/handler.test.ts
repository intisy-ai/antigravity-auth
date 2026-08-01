// @ts-nocheck
import { describe, it, expect } from "vitest";
import { def, defs } from "./handler.js";

describe("handler: two providers sharing one account pool", () => {
  it("exposes antigravity + gemini-cli, both on the antigravity account pool", () => {
    expect(defs.map((d) => d.id)).toEqual(["antigravity", "gemini-cli"]);
    expect(defs.every((d) => d.accountPool === "antigravity")).toBe(true);
  });

  it("keeps def as the antigravity provider for direct importers", () => {
    expect(def.id).toBe("antigravity");
    expect(def.accountPool).toBe("antigravity");
  });

  it("carries a distinct label per provider so Cairn renders two rows", () => {
    const byId = Object.fromEntries(defs.map((d) => [d.id, d.label]));
    expect(byId["antigravity"]).not.toBe(byId["gemini-cli"]);
  });
});
