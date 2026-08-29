import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { DEFAULT_CONFIG } from "./config"

// Capture what the logger hands to core's shared writeLog. debug.ts also reaches into core for
// getAppConfigDir (app-aware config dir resolution), stubbed here since these tests don't care
// which directory it resolves to.
const { writeLogMock } = vi.hoisted(() => ({ writeLogMock: vi.fn() }))
vi.mock("@intisy-ai/basekit", () => ({ makeWriteLog: () => writeLogMock, getAppConfigDir: () => "/tmp/antigravity-logger-test-configdir" }))

// debug.ts touches storage on init; stub it so the test never writes files.
const { ensureGitignoreSyncMock } = vi.hoisted(() => ({ ensureGitignoreSyncMock: vi.fn() }))
vi.mock("./storage", () => ({ ensureGitignoreSync: ensureGitignoreSyncMock }))

describe("logger routing (core makeWriteLog)", () => {
  beforeEach(() => {
    vi.resetModules()
    writeLogMock.mockReset()
    ensureGitignoreSyncMock.mockReset()
  })

  afterEach(async () => {
    const { initializeDebug } = await import("./debug")
    initializeDebug(DEFAULT_CONFIG)
  })

  it("suppresses debug lines when file debug is disabled", async () => {
    const { initializeDebug } = await import("./debug")
    const { createLogger } = await import("./logger")

    initializeDebug({ ...DEFAULT_CONFIG, debug: false, debug_tui: true })
    createLogger("request").debug("thinking-resolution", { status: 429 })

    expect(writeLogMock).not.toHaveBeenCalled()
  })

  it("writes debug lines through core when file debug is enabled", async () => {
    const { initializeDebug } = await import("./debug")
    const { createLogger } = await import("./logger")

    initializeDebug({ ...DEFAULT_CONFIG, debug: true, log_dir: "/tmp/opencode-antigravity-logger-tests" })
    createLogger("request").debug("thinking-resolution", { status: 429 })

    expect(writeLogMock).toHaveBeenCalledWith('[debug] request: thinking-resolution {"status":429}', false)
  })

  it("always writes info/warn/error; error flags isError=true", async () => {
    const { createLogger } = await import("./logger")
    const log = createLogger("request")

    log.info("started")
    log.warn("slow")
    log.error("boom")

    expect(writeLogMock).toHaveBeenCalledWith("[info] request: started", false)
    expect(writeLogMock).toHaveBeenCalledWith("[warn] request: slow", false)
    expect(writeLogMock).toHaveBeenCalledWith("[error] request: boom", true)
  })
})
