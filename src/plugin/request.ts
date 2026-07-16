// Task 6a/6b: this file used to hold the whole TS request/response transform pipeline
// (prepareAntigravityRequest / transformAntigravityResponse + their internal helpers). That logic is
// now owned by the Java orchestrator (prepareAntigravityRequestProd / transformServeBodyProd /
// newResponseSseTransformer, called via driver/javaHandle.ts + driver/javaStream.ts). Both functions
// depended on the now-deleted src/plugin/transform/* barrel (isGemini3Model et al.), so they could not
// survive Task 6a's transform deletion; only the host I/O this file was ALSO responsible for remains:
// the stable plugin session id and the fetch-interception helpers that detect and materialize
// Generative Language API requests. Task 7b-2 closed the last residual noted here: the synthetic
// project id minter is now AntigravityRequestPrep.generateSyntheticProjectId (Java), reached via
// driver/javaHandle.ts's generateSyntheticProjectIdViaJava/resolveProjectIdViaJava (real jsRandom/jsUuid).
import crypto from "node:crypto";

const PLUGIN_SESSION_ID = `-${crypto.randomUUID()}`;

export function shouldCacheThinkingSignatures(model?: string): boolean {
  if (typeof model !== "string") return false;
  const lower = model.toLowerCase();

  return lower.includes("claude") || lower.includes("gemini-3");
}

/**
 * Synthetic thinking placeholder text used when keep_thinking=true but debug mode is off.
 * Injected via the same path as debug text (injectDebugThinking) to ensure consistent
 * signature caching and multi-turn handling.
 */
export const SYNTHETIC_THINKING_PLACEHOLDER = "[Thinking preserved]\n";

/**
 * Gets the stable session ID for this plugin instance.
 */
export function getPluginSessionId(): string {
  return PLUGIN_SESSION_ID;
}

/**
 * Resolve a fetch() URL from RequestInfo. OpenCode / AI SDK often calls fetch(Request, init)
 * instead of fetch(string, init); we must inspect the URL the same way in both cases.
 */
export function requestInfoToUrlString(input: RequestInfo): string | null {
  if (typeof input === "string") {
    return input;
  }
  if (typeof Request !== "undefined" && input instanceof Request) {
    return input.url;
  }
  if (typeof URL !== "undefined" && input instanceof URL) {
    return input.href;
  }
  return null;
}

/**
 * Detects requests headed to the Google Generative Language API (or Antigravity / Cloud Code PA)
 * so we can intercept and rewrite them.
 */
export function isGenerativeLanguageRequest(input: RequestInfo): boolean {
  const url = requestInfoToUrlString(input);
  if (!url) {
    return false;
  }
  return (
    url.includes("generativelanguage.googleapis.com") ||
    url.includes("cloudcode-pa")
  );
}

function mergeInitFromRequest(req: Request, init?: RequestInit): RequestInit {
  const next: RequestInit = { ...(init ?? {}) };
  next.method = init?.method ?? req.method;
  const headers = new Headers(req.headers);
  if (init?.headers) {
    new Headers(init.headers).forEach((value, key) => {
      headers.set(key, value);
    });
  }
  next.headers = headers;
  next.signal = init?.signal ?? req.signal;
  if (init?.referrer !== undefined) {
    next.referrer = init.referrer;
  }
  if (init?.referrerPolicy !== undefined) {
    next.referrerPolicy = init.referrerPolicy;
  }
  if (init?.mode !== undefined) {
    next.mode = init.mode;
  }
  if (init?.credentials !== undefined) {
    next.credentials = init.credentials;
  }
  if (init?.cache !== undefined) {
    next.cache = init.cache;
  }
  if (init?.redirect !== undefined) {
    next.redirect = init.redirect;
  }
  if (init?.integrity !== undefined) {
    next.integrity = init.integrity;
  }
  if (init?.keepalive !== undefined) {
    next.keepalive = init.keepalive;
  }
  return next;
}

/**
 * OpenCode / AI SDK often calls fetch(Request, init) with the JSON body on the Request only.
 * The Java-driven prepare path reads init.body as a string; materialize so sanitization always runs.
 */
export async function materializeGenerativeLanguageFetchInput(
  input: RequestInfo,
  init?: RequestInit,
): Promise<{ input: RequestInfo; init?: RequestInit }> {
  if (typeof Request === "undefined" || !(input instanceof Request)) {
    return { input, init };
  }
  if (!isGenerativeLanguageRequest(input)) {
    return { input, init };
  }
  if (init !== undefined && init.body != null) {
    return { input, init };
  }
  const method = (init?.method ?? input.method ?? "GET").toUpperCase();
  if (method === "GET" || method === "HEAD") {
    return { input: input.url, init: mergeInitFromRequest(input, init) };
  }
  try {
    const bodyText = await input.clone().text();
    const nextInit = mergeInitFromRequest(input, init);
    nextInit.body = bodyText;
    return { input: input.url, init: nextInit };
  } catch {
    return { input, init };
  }
}
