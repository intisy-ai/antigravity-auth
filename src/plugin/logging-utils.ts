/** What the debug policy is derived from: the plugin's own config, and the environment. */
export interface DebugPolicyInput {
  /** Whether the config asks for debug logging. */
  configDebug: boolean
  /** Whether the config asks for those logs in the terminal panel too. */
  configDebugTui: boolean
  /** What the environment says about debug logging, which can raise the level. */
  envDebugFlag?: string
  /** What the environment says about the terminal panel. */
  envDebugTuiFlag?: string
}

/** What debug logging this process actually does. */
export interface DebugPolicy {
  /** 0 for off, 1 for on, 2 for verbose. */
  debugLevel: number
  /** Whether anything is logged at all. */
  debugEnabled: boolean
  /** Whether those logs also reach the terminal panel. */
  debugTuiEnabled: boolean
}

/**
 * Whether an environment flag reads as on.
 *
 * @param flag - the flag's value, which may be unset
 * @returns true when it names one of the affirmative spellings
 */
export function isTruthyFlag(flag?: string): boolean {
  return flag === "1" || flag?.toLowerCase() === "true"
}

/**
 * The debug level one environment flag asks for.
 *
 * @param flag - the flag's value
 * @returns 2 for verbose, 1 for on, 0 for anything else
 */
export function parseDebugLevel(flag: string): number {
  const trimmed = flag.trim()
  if (trimmed === "2" || trimmed === "verbose") return 2
  if (trimmed === "1" || trimmed === "true") return 1
  return 0
}

/**
 * What debug logging this process does, from the config and the environment together.
 *
 * @param input - the config's own answer and the environment's
 * @returns the resolved policy
 */
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

/**
 * One error, as a log line.
 *
 * @param error - whatever was thrown
 * @returns its stack when it has one, else the best text available
 */
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

/**
 * Text cut to a length a log line can carry, saying how much was dropped.
 *
 * @param text - the text to log
 * @param maxChars - how much of it to keep
 * @returns the text, truncated with a count when it was too long
 */
export function truncateTextForLog(text: string, maxChars: number): string {
  if (text.length <= maxChars) {
    return text
  }
  return `${text.slice(0, maxChars)}... (truncated ${text.length - maxChars} chars)`
}
