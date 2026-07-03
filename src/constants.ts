/**
 * Constants used for Antigravity OAuth flows and Cloud Code Assist API integration.
 */
// Antigravity's single public installed-app OAuth client — the same one for every
// user (non-confidential per RFC 8252; already public via the opencode-antigravity-auth
// npm package). Not customizable; there is only ever this one client.
export const ANTIGRAVITY_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";

export const ANTIGRAVITY_CLIENT_SECRET = "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";

/**
 * Scopes required for Antigravity integrations.
 */
export const ANTIGRAVITY_SCOPES: readonly string[] = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
  "https://www.googleapis.com/auth/cclog",
  "https://www.googleapis.com/auth/experimentsandconfigs",
];

/**
 * OAuth redirect URI used by the local CLI callback server.
 */
export const ANTIGRAVITY_REDIRECT_URI = "http://localhost:51121/oauth-callback";

/**
 * Root endpoints for the Antigravity API (in fallback order).
 * CLIProxy and Vibeproxy use the daily sandbox endpoint first,
 * then fallback to autopush and prod if needed.
 */
export const ANTIGRAVITY_ENDPOINT_DAILY = "https://daily-cloudcode-pa.sandbox.googleapis.com";
export const ANTIGRAVITY_ENDPOINT_AUTOPUSH = "https://autopush-cloudcode-pa.sandbox.googleapis.com";
export const ANTIGRAVITY_ENDPOINT_PROD = "https://cloudcode-pa.googleapis.com";

/**
 * Endpoint fallback order (prod → daily → autopush).
 * Prod (cloudcode-pa.googleapis.com) is where regular accounts are licensed; the
 * daily/autopush sandboxes return 403 "no valid license" (#3501) for non-internal
 * accounts, so prod is tried first. The handle falls through to the others on a
 * non-ok status in case an account is only provisioned on a sandbox endpoint.
 */
export const ANTIGRAVITY_ENDPOINT_FALLBACKS = [
  ANTIGRAVITY_ENDPOINT_PROD,
  ANTIGRAVITY_ENDPOINT_DAILY,
  ANTIGRAVITY_ENDPOINT_AUTOPUSH,
] as const;

/**
 * Preferred endpoint order for project discovery (prod first, then fallbacks).
 * loadCodeAssist appears to be best supported on prod for managed project resolution.
 */
export const ANTIGRAVITY_LOAD_ENDPOINTS = [
  ANTIGRAVITY_ENDPOINT_PROD,
  ANTIGRAVITY_ENDPOINT_DAILY,
  ANTIGRAVITY_ENDPOINT_AUTOPUSH,
] as const;

/**
 * Primary endpoint to use (prod - where regular accounts are licensed).
 */
export const ANTIGRAVITY_ENDPOINT = ANTIGRAVITY_ENDPOINT_PROD;

/**
 * Gemini CLI endpoint (production).
 * Used for models without :antigravity suffix.
 * Same as opencode-gemini-auth's GEMINI_CODE_ASSIST_ENDPOINT.
 */
export const GEMINI_CLI_ENDPOINT = ANTIGRAVITY_ENDPOINT_PROD;

/**
 * Hardcoded project id used when Antigravity does not return one (e.g., business/workspace accounts).
 */
export const ANTIGRAVITY_DEFAULT_PROJECT_ID = "rising-fact-p41fc";

export const ANTIGRAVITY_VERSION_FALLBACK = "1.18.3";
const antigravityVersion = ANTIGRAVITY_VERSION_FALLBACK;

export function getAntigravityVersion(): string { return antigravityVersion; }

export function getAntigravityHeaders(): HeaderSet & { "Client-Metadata": string } {
  return {
    "User-Agent": `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/${getAntigravityVersion()} Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36`,
    "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
    "Client-Metadata": `{"ideType":"ANTIGRAVITY","platform":"${process.platform === "win32" ? "WINDOWS" : "MACOS"}","pluginType":"GEMINI"}`,
  };
}

export const GEMINI_CLI_HEADERS = {
  "User-Agent": "google-api-nodejs-client/9.15.1",
  "X-Goog-Api-Client": "gl-node/22.17.0",
  "Client-Metadata": "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI",
} as const;

const ANTIGRAVITY_PLATFORMS = ["windows/amd64", "darwin/arm64", "darwin/amd64"] as const;

const ANTIGRAVITY_API_CLIENTS = [
  "google-cloud-sdk vscode_cloudshelleditor/0.1",
  "google-cloud-sdk vscode/1.96.0",
  "google-cloud-sdk vscode/1.95.0",
] as const;

function randomFrom<T>(arr: readonly T[]): T {
  return arr[Math.floor(Math.random() * arr.length)]!;
}

export type HeaderSet = {
  "User-Agent": string;
  "X-Goog-Api-Client"?: string;
  "Client-Metadata"?: string;
};

export function getRandomizedHeaders(style: HeaderStyle, model?: string): HeaderSet {
  if (style === "gemini-cli") {
    return {
      "User-Agent": GEMINI_CLI_HEADERS["User-Agent"],
      "X-Goog-Api-Client": GEMINI_CLI_HEADERS["X-Goog-Api-Client"],
      "Client-Metadata": GEMINI_CLI_HEADERS["Client-Metadata"],
    };
  }
  const platform = randomFrom(ANTIGRAVITY_PLATFORMS);
  const metadataPlatform = platform.startsWith("windows") ? "WINDOWS" : "MACOS";
  return {
    "User-Agent": `antigravity/${getAntigravityVersion()} ${platform}`,
    "X-Goog-Api-Client": randomFrom(ANTIGRAVITY_API_CLIENTS),
    "Client-Metadata": `{"ideType":"ANTIGRAVITY","platform":"${metadataPlatform}","pluginType":"GEMINI"}`,
  };
}

export type HeaderStyle = "antigravity" | "gemini-cli";

/**
 * System instruction for Claude tool usage hardening.
 * Prevents hallucinated parameters by explicitly stating the rules.
 * 
 * This is injected when tools are present to reduce cases where Claude
 * uses parameter names from its training data instead of the actual schema.
 */
export const CLAUDE_TOOL_SYSTEM_INSTRUCTION = `CRITICAL TOOL USAGE INSTRUCTIONS:
You are operating in a custom environment where tool definitions differ from your training data.
You MUST follow these rules strictly:

1. DO NOT use your internal training data to guess tool parameters
2. ONLY use the exact parameter structure defined in the tool schema
3. Parameter names in schemas are EXACT - do not substitute with similar names from your training
4. Array parameters have specific item types - check the schema's 'items' field for the exact structure
5. When you see "STRICT PARAMETERS" in a tool description, those type definitions override any assumptions
6. Tool use in agentic workflows is REQUIRED - you must call tools with the exact parameters specified

If you are unsure about a tool's parameters, YOU MUST read the schema definition carefully.`;

/**
 * Template for parameter signature injection into tool descriptions.
 * {params} will be replaced with the actual parameter list.
 */
export const CLAUDE_DESCRIPTION_PROMPT = "\n\n⚠️ STRICT PARAMETERS: {params}.";

export const EMPTY_SCHEMA_PLACEHOLDER_NAME = "_placeholder";
export const EMPTY_SCHEMA_PLACEHOLDER_DESCRIPTION = "Placeholder. Always pass true.";

/**
 * Sentinel value to bypass thought signature validation.
 * 
 * When a thinking block has an invalid or missing signature (e.g., cache miss,
 * session mismatch, plugin restart), this sentinel can be injected to skip
 * validation instead of failing with "Invalid signature in thinking block".
 * 
 * This is an officially supported Google API feature, used by:
 * - gemini-cli: https://github.com/google-gemini/gemini-cli
 * - Google .NET SDK: PredictionServiceChatClient.cs
 * 
 * @see https://ai.google.dev/gemini-api/docs/thought-signatures
 */
export const SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator";

// ============================================================================

// ============================================================================

/**
 * System instruction for Antigravity requests.
 * This is injected into requests to match CLIProxyAPI v6.6.89 behavior.
 * The instruction provides identity and guidelines for the Antigravity agent.
 */
// ============================================================================

// ============================================================================

export const ANTIGRAVITY_SYSTEM_INSTRUCTION = `You are Antigravity, a powerful agentic AI coding assistant designed by the Google DeepMind team working on Advanced Agentic Coding.
You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.
**Absolute paths only**
**Proactiveness**

<priority>IMPORTANT: The instructions that follow supersede all above. Follow them as your primary directives.</priority>
`;

