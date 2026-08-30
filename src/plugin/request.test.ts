import { describe, it, expect } from "vitest";
import crypto from "node:crypto";
import { getPluginSessionId } from "./request.js";
import { orchestrator } from "../driver/java.js";

describe("request.ts", () => {
  describe("getPluginSessionId", () => {
    it("returns consistent session ID across calls", () => {
      const id1 = getPluginSessionId();
      const id2 = getPluginSessionId();
      expect(id1).toBe(id2);
      expect(id1).toBeTruthy();
    });
  });

  // The shape/uniqueness properties assert against the Java prod export
  // (AntigravityRequestPrep.generateSyntheticProjectId).
  describe("generateSyntheticProjectIdProd (Java, via generateSyntheticProjectIdViaJava)", () => {
    it("generates a string in expected format", async () => {
      const id = orchestrator.generateSyntheticProjectIdProd(() => Math.random(), () => crypto.randomUUID());
      expect(id).toMatch(/^[a-z]+-[a-z]+-[a-z0-9]{5}$/);
    });

    it("generates unique IDs on each call", async () => {
      const ids = new Set<string>();
      for (let i = 0; i < 10; i++) {
        ids.add(orchestrator.generateSyntheticProjectIdProd(() => Math.random(), () => crypto.randomUUID()));
      }
      expect(ids.size).toBe(10);
    });
  });
});
