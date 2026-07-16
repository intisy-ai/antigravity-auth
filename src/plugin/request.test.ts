import { describe, it, expect } from "vitest";
import crypto from "node:crypto";
import {
  getPluginSessionId,
  isGenerativeLanguageRequest,
  materializeGenerativeLanguageFetchInput,
} from "./request";
import { loadOrchestrator } from "../driver/javaHandle.js";

describe("request.ts", () => {
  describe("getPluginSessionId", () => {
    it("returns consistent session ID across calls", () => {
      const id1 = getPluginSessionId();
      const id2 = getPluginSessionId();
      expect(id1).toBe(id2);
      expect(id1).toBeTruthy();
    });
  });

  // Task 7b-2: generateSyntheticProjectId is deleted from TS; the same shape/uniqueness
  // properties now assert against the Java prod export (AntigravityRequestPrep.generateSyntheticProjectId).
  describe("generateSyntheticProjectIdProd (Java, via generateSyntheticProjectIdViaJava)", () => {
    it("generates a string in expected format", async () => {
      const orchestrator = await loadOrchestrator();
      const id = orchestrator.generateSyntheticProjectIdProd(() => Math.random(), () => crypto.randomUUID());
      expect(id).toMatch(/^[a-z]+-[a-z]+-[a-z0-9]{5}$/);
    });

    it("generates unique IDs on each call", async () => {
      const orchestrator = await loadOrchestrator();
      const ids = new Set<string>();
      for (let i = 0; i < 10; i++) {
        ids.add(orchestrator.generateSyntheticProjectIdProd(() => Math.random(), () => crypto.randomUUID()));
      }
      expect(ids.size).toBe(10);
    });
  });

  describe("isGenerativeLanguageRequest", () => {
    it("returns true for generativelanguage.googleapis.com URLs", () => {
      expect(isGenerativeLanguageRequest("https://generativelanguage.googleapis.com/v1/models")).toBe(true);
    });

    it("returns true for Antigravity daily-cloudcode internal endpoints", () => {
      expect(
        isGenerativeLanguageRequest(
          "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:streamGenerateContent?alt=sse",
        ),
      ).toBe(true);
    });

    it("returns false for other URLs", () => {
      expect(isGenerativeLanguageRequest("https://api.anthropic.com/v1/messages")).toBe(false);
    });

    it("returns false for non-string inputs", () => {
      expect(isGenerativeLanguageRequest({} as any)).toBe(false);
      expect(isGenerativeLanguageRequest(new Request("https://example.com"))).toBe(false);
    });

    it("returns true for Request whose URL is a Cloud Code PA endpoint", () => {
      expect(
        isGenerativeLanguageRequest(
          new Request(
            "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:streamGenerateContent?alt=sse",
            { method: "POST", body: "{}" },
          ),
        ),
      ).toBe(true);
    });
  });

  describe("materializeGenerativeLanguageFetchInput", () => {
    it("copies Request body into init when init.body is missing (generative URL)", async () => {
      const url =
        "https://daily-cloudcode-pa.sandbox.googleapis.com/v1/models/antigravity-gemini-3.1-pro:streamGenerateContent";
      const json = JSON.stringify({ contents: [{ role: "user", parts: [{ text: "hi" }] }] });
      const req = new Request(url, {
        method: "POST",
        body: json,
        headers: { "Content-Type": "application/json" },
      });
      const { input, init } = await materializeGenerativeLanguageFetchInput(req, {});
      expect(input).toBe(url);
      expect(init?.body).toBe(json);
    });

    it("does not read body for non-generative Request", async () => {
      const req = new Request("https://api.example.com/v1/chat", { method: "POST", body: "{}" });
      const { input, init } = await materializeGenerativeLanguageFetchInput(req, {});
      expect(input).toBe(req);
      expect(init?.body).toBeUndefined();
    });
  });
});
