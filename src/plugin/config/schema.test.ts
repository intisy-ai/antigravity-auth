import { describe, expect, it } from "vitest";

import { DEFAULT_CONFIG } from "./schema";

// Config defaults are the source of truth (the standalone assets/*.schema.json
// file was dropped when the model catalog went live, so the old "documents … in
// the JSON schema" tests were removed).
describe("cli_first config", () => {
  it("includes cli_first default in DEFAULT_CONFIG", () => {
    expect(DEFAULT_CONFIG).toHaveProperty("cli_first", false);
  });
});

describe("claude_prompt_auto_caching config", () => {
  it("includes claude_prompt_auto_caching default in DEFAULT_CONFIG", () => {
    expect(DEFAULT_CONFIG).toHaveProperty("claude_prompt_auto_caching", false);
  });
});
