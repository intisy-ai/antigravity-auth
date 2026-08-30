import { describe, expect, it, vi } from "vitest"
import {
  deriveDebugPolicy,
  formatErrorForLog,
  truncateTextForLog,
} from "./logging-utils.js"

describe("deriveDebugPolicy", () => {
  it("keeps debug_tui disabled when debug is disabled", () => {
    const policy = deriveDebugPolicy({
      configDebug: false,
      configDebugTui: true,
      envDebugFlag: "",
      envDebugTuiFlag: "1",
    })

    expect(policy.debugEnabled).toBe(false)
    expect(policy.debugTuiEnabled).toBe(false)
    expect(policy.debugLevel).toBe(0)
  })

  it("supports verbose mode override when debug config is enabled", () => {
    const policy = deriveDebugPolicy({
      configDebug: true,
      configDebugTui: false,
      envDebugFlag: "verbose",
      envDebugTuiFlag: "",
    })

    expect(policy.debugEnabled).toBe(true)
    expect(policy.debugTuiEnabled).toBe(false)
    expect(policy.debugLevel).toBe(2)
  })
})

describe("format helpers", () => {
  it("formats errors defensively", () => {
    expect(formatErrorForLog(new Error("boom"))).toContain("boom")
    expect(formatErrorForLog({ code: 401 })).toBe('{"code":401}')

    const circular: { self?: unknown } = {}
    circular.self = circular
    expect(formatErrorForLog(circular)).toContain("[object Object]")
  })

  it("truncates long text with metadata", () => {
    const longText = "x".repeat(12)
    expect(truncateTextForLog(longText, 5)).toBe("xxxxx... (truncated 7 chars)")
    expect(truncateTextForLog("short", 10)).toBe("short")
  })
})
