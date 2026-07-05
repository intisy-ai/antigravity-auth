export interface DebugPolicyInput {
  configDebug: boolean
  configDebugTui: boolean
  envDebugFlag?: string
  envDebugTuiFlag?: string
}

export interface DebugPolicy {
  debugLevel: number
  debugEnabled: boolean
  debugTuiEnabled: boolean
}

export function isTruthyFlag(flag?: string): boolean {
  return flag === "1" || flag?.toLowerCase() === "true"
}

export function parseDebugLevel(flag: string): number {
  const trimmed = flag.trim()
  if (trimmed === "2" || trimmed === "verbose") return 2
  if (trimmed === "1" || trimmed === "true") return 1
  return 0
}

export function deriveDebugPolicy(input: DebugPolicyInput): DebugPolicy {
  const envDebugFlag = input.envDebugFlag ?? ""
  const debugLevel = input.configDebug
    ? envDebugFlag === "2" || envDebugFlag === "verbose"
      ? 2
      : 1
    : parseDebugLevel(envDebugFlag)
  const debugEnabled = debugLevel >= 1
  const debugTuiEnabled = debugEnabled && (input.configDebugTui || isTruthyFlag(input.envDebugTuiFlag))

  return {
    debugLevel,
    debugEnabled,
    debugTuiEnabled,
  }
}

export function formatErrorForLog(error: unknown): string {
  if (error instanceof Error) {
    return error.stack ?? error.message
  }
  try {
    return JSON.stringify(error)
  } catch {
    return String(error)
  }
}

export function truncateTextForLog(text: string, maxChars: number): string {
  if (text.length <= maxChars) {
    return text
  }
  return `${text.slice(0, maxChars)}... (truncated ${text.length - maxChars} chars)`
}
