// Live model discovery: ask cloudcode-pa which models the account can actually use
// (v1internal:fetchAvailableModels). Catalog assembly from the response now lives in Java
// (AntigravityCatalog.buildAntigravityCatalog, called via driver/javaHandle.ts's buildCatalogViaJava).

import { ANTIGRAVITY_ENDPOINT_FALLBACKS, getAntigravityHeaders } from "../constants.js";

interface FetchedModelInfo {
  displayName?: string;
  maxTokens?: number;
  maxOutputTokens?: number;
  supportsImages?: boolean;
  supportsThinking?: boolean;
}

interface FetchAvailableModelsPayload {
  models?: Record<string, FetchedModelInfo>;
  defaultAgentModelId?: string;
  agentModelSorts?: Array<{ groups?: Array<{ modelIds?: string[] }> }>;
  deprecatedModelIds?: Record<string, unknown>;
  imageGenerationModelIds?: string[];
}

/**
 * Calls v1internal:fetchAvailableModels for the account's project. Retries
 * directly if the (per-account) proxy is unreachable, mirroring the request
 * handle, so a dead proxy never silently empties the catalog.
 */
export async function fetchAvailableModels(
  accessToken: string,
  projectId: string,
  proxy: string | undefined,
  log: (message: string) => void,
): Promise<FetchAvailableModelsPayload | null> {
  const baseInit = {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}`, ...getAntigravityHeaders() },
    body: JSON.stringify(projectId ? { project: projectId } : {}),
  };

  for (const baseEndpoint of ANTIGRAVITY_ENDPOINT_FALLBACKS) {
    const url = `${baseEndpoint}/v1internal:fetchAvailableModels`;
    try {
      let response: Response;
      try {
        response = await fetch(url, { ...baseInit, proxy } as RequestInit & { proxy?: string });
      } catch (proxyError) {
        if (!proxy) throw proxyError;
        log("fetchAvailableModels via proxy failed, retrying directly: " + String(proxyError));
        response = await fetch(url, baseInit as RequestInit);
      }
      if (!response.ok) continue;
      return (await response.json()) as FetchAvailableModelsPayload;
    } catch (error) {
      log("fetchAvailableModels failed at " + baseEndpoint + ": " + String(error));
      continue;
    }
  }
  return null;
}
