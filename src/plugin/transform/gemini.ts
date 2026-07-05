/**
 * Gemini-specific Request Transformations
 * 
 * Handles Gemini model-specific request transformations including:
 * - Thinking config (camelCase keys, thinkingLevel for Gemini 3)
 * - Tool normalization (function/custom format)
 * - Schema transformation (JSON Schema -> Gemini Schema format)
 */

import type { RequestPayload, ThinkingConfig, ThinkingTier, GoogleSearchConfig } from "./types";

/**
 * Transform a JSON Schema to Gemini-compatible format.
 * Based on @google/genai SDK's processJsonSchema() function.
 * 
 * Key transformations:
 * - Converts type values to uppercase (object -> OBJECT)
 * - Removes unsupported fields like additionalProperties, $schema
 * - Recursively processes nested schemas (properties, items, anyOf, etc.)
 * 
 * @param schema - A JSON Schema object or primitive value
 * @returns Gemini-compatible schema
 * 
 * Fields that Gemini API rejects and must be removed from schemas.
 * Antigravity uses strict protobuf-backed JSON validation.
 */
const UNSUPPORTED_SCHEMA_FIELDS = new Set([
  "additionalProperties",
  "$schema",
  "$id",
  "$comment",
  "$ref",
  "$defs",
  "definitions",
  "const",
  "contentMediaType",
  "contentEncoding",
  "if",
  "then",
  "else",
  "not",
  "patternProperties",
  "unevaluatedProperties",
  "unevaluatedItems",
  "dependentRequired",
  "dependentSchemas",
  "propertyNames",
  "minContains",
  "maxContains",
]);

export function toGeminiSchema(schema: unknown): unknown {

  if (!schema || typeof schema !== "object" || Array.isArray(schema)) {
    return schema;
  }

  const inputSchema = schema as Record<string, unknown>;
  const result: Record<string, unknown> = {};


  const propertyNames = new Set<string>();
  if (inputSchema.properties && typeof inputSchema.properties === "object") {
    for (const propName of Object.keys(inputSchema.properties as Record<string, unknown>)) {
      propertyNames.add(propName);
    }
  }

  for (const [key, value] of Object.entries(inputSchema)) {

    if (UNSUPPORTED_SCHEMA_FIELDS.has(key)) {
      continue;
    }

    if (key === "type" && typeof value === "string") {

      result[key] = value.toUpperCase();
    } else if (key === "properties" && typeof value === "object" && value !== null) {

      const props: Record<string, unknown> = {};
      for (const [propName, propSchema] of Object.entries(value as Record<string, unknown>)) {
        props[propName] = toGeminiSchema(propSchema);
      }
      result[key] = props;
    } else if (key === "items" && typeof value === "object") {

      result[key] = toGeminiSchema(value);
    } else if ((key === "anyOf" || key === "oneOf" || key === "allOf") && Array.isArray(value)) {

      result[key] = value.map((item) => toGeminiSchema(item));
    } else if (key === "enum" && Array.isArray(value)) {

      result[key] = value;
    } else if (key === "default" || key === "examples") {

      result[key] = value;
    } else if (key === "required" && Array.isArray(value)) {

      if (propertyNames.size > 0) {
        const validRequired = value.filter((prop) =>
          typeof prop === "string" && propertyNames.has(prop)
        );
        if (validRequired.length > 0) {
          result[key] = validRequired;
        }

      } else {

        result[key] = value;
      }
    } else {
      result[key] = value;
    }
  }


  if (result.type === "ARRAY" && !result.items) {
    result.items = { type: "STRING" };
  }

  return result;
}

/**
 * Check if a model is a Gemini model (not Claude).
 */
export function isGeminiModel(model: string): boolean {
  const lower = model.toLowerCase();
  return lower.includes("gemini") && !lower.includes("claude");
}

/**
 * Check if a model is Gemini 3 (uses thinkingLevel string).
 */
export function isGemini3Model(model: string): boolean {
  return model.toLowerCase().includes("gemini-3");
}

/**
 * Check if a model is Gemini 2.5 (uses numeric thinkingBudget).
 */
export function isGemini25Model(model: string): boolean {
  return model.toLowerCase().includes("gemini-2.5");
}

/**
 * Check if a model is an image generation model.
 * Image models don't support thinking and require imageConfig.
 */
export function isImageGenerationModel(model: string): boolean {
  const lower = model.toLowerCase();
  return (
    lower.includes("image") ||
    lower.includes("imagen")
  );
}

/**
 * Build Gemini 3 thinking config with thinkingLevel string.
 */
export function buildGemini3ThinkingConfig(
  includeThoughts: boolean,
  thinkingLevel: ThinkingTier,
): ThinkingConfig {
  return {
    includeThoughts,
    thinkingLevel,
  };
}

/**
 * Build Gemini 2.5 thinking config with numeric thinkingBudget.
 */
export function buildGemini25ThinkingConfig(
  includeThoughts: boolean,
  thinkingBudget?: number,
): ThinkingConfig {
  return {
    includeThoughts,
    ...(typeof thinkingBudget === "number" && thinkingBudget > 0 ? { thinkingBudget } : {}),
  };
}

/**
 * Image generation config for Gemini image models.
 * 
 * Supported aspect ratios: "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"
 */
export interface ImageConfig {
  aspectRatio?: string;
}

/**
 * Valid aspect ratios for image generation.
 */
const VALID_ASPECT_RATIOS = ["1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"];

/**
 * Build image generation config for Gemini image models.
 * 
 * Configuration is read from environment variables:
 * - OPENCODE_IMAGE_ASPECT_RATIO: Aspect ratio (e.g., "16:9", "4:3")
 * 
 * Defaults to 1:1 aspect ratio if not specified.
 * 
 * Note: Resolution setting is not currently supported by the Antigravity API.
 */
export function buildImageGenerationConfig(): ImageConfig {

  const aspectRatio = process.env.OPENCODE_IMAGE_ASPECT_RATIO || "1:1";

  if (VALID_ASPECT_RATIOS.includes(aspectRatio)) {
    return { aspectRatio };
  }

  console.warn(`[gemini] Invalid aspect ratio "${aspectRatio}". Using default "1:1". Valid values: ${VALID_ASPECT_RATIOS.join(", ")}`);


  return { aspectRatio: "1:1" };
}

/**
 * Normalize tools for Gemini models.
 * Ensures tools have proper function-style format.
 * 
 * @returns Debug info about tool normalization
 */
export function normalizeGeminiTools(
  payload: RequestPayload,
): { toolDebugMissing: number; toolDebugSummaries: string[] } {
  let toolDebugMissing = 0;
  const toolDebugSummaries: string[] = [];

  if (!Array.isArray(payload.tools)) {
    return { toolDebugMissing, toolDebugSummaries };
  }

  payload.tools = (payload.tools as unknown[]).map((tool: unknown, toolIndex: number) => {
    const t = tool as Record<string, unknown>;


    if (t.googleSearch || t.googleSearchRetrieval) {
      return t;
    }

    const newTool = { ...t };

    const schemaCandidates = [
      (newTool.function as Record<string, unknown> | undefined)?.input_schema,
      (newTool.function as Record<string, unknown> | undefined)?.parameters,
      (newTool.function as Record<string, unknown> | undefined)?.inputSchema,
      (newTool.custom as Record<string, unknown> | undefined)?.input_schema,
      (newTool.custom as Record<string, unknown> | undefined)?.parameters,
      newTool.parameters,
      newTool.input_schema,
      newTool.inputSchema,
    ].filter(Boolean);

    const placeholderSchema: Record<string, unknown> = {
      type: "OBJECT",
      properties: {
        _placeholder: {
          type: "BOOLEAN",
          description: "Placeholder. Always pass true.",
        },
      },
      required: ["_placeholder"],
    };

    let schema = schemaCandidates[0] as Record<string, unknown> | undefined;
    const schemaObjectOk = schema && typeof schema === "object" && !Array.isArray(schema);
    if (!schemaObjectOk) {
      schema = placeholderSchema;
      toolDebugMissing += 1;
    } else {

      schema = toGeminiSchema(schema) as Record<string, unknown>;
    }

    const nameCandidate =
      newTool.name ||
      (newTool.function as Record<string, unknown> | undefined)?.name ||
      (newTool.custom as Record<string, unknown> | undefined)?.name ||
      `tool-${toolIndex}`;

    // Always update function.input_schema with transformed schema
    if (newTool.function && schema) {
      (newTool.function as Record<string, unknown>).input_schema = schema;
    }

    // Always update custom.input_schema with transformed schema
    if (newTool.custom && schema) {
      (newTool.custom as Record<string, unknown>).input_schema = schema;
    }


    if (!newTool.custom && newTool.function) {
      const fn = newTool.function as Record<string, unknown>;
      newTool.custom = {
        name: fn.name || nameCandidate,
        description: fn.description,
        input_schema: schema,
      };
    }


    if (!newTool.custom && !newTool.function) {
      newTool.custom = {
        name: nameCandidate,
        description: newTool.description,
        input_schema: schema,
      };

      if (!newTool.parameters && !newTool.input_schema && !newTool.inputSchema) {
        newTool.parameters = schema;
      }
    }

    if (newTool.custom && !(newTool.custom as Record<string, unknown>).input_schema) {
      (newTool.custom as Record<string, unknown>).input_schema = {
        type: "OBJECT",
        properties: {},
      };
      toolDebugMissing += 1;
    }

    toolDebugSummaries.push(
      `idx=${toolIndex}, hasCustom=${!!newTool.custom}, customSchema=${!!(newTool.custom as Record<string, unknown> | undefined)?.input_schema}, hasFunction=${!!newTool.function}, functionSchema=${!!(newTool.function as Record<string, unknown> | undefined)?.input_schema}`,
    );


    if (newTool.custom) {
      delete newTool.custom;
    }

    return newTool;
  });

  return { toolDebugMissing, toolDebugSummaries };
}

/**
 * Apply all Gemini-specific transformations to a request payload.
 */
export interface GeminiTransformOptions {
  /** The effective model name (resolved) */
  model: string;
  /** Tier-based thinking budget (from model suffix, for Gemini 2.5) */
  tierThinkingBudget?: number;
  /** Tier-based thinking level (from model suffix, for Gemini 3) */
  tierThinkingLevel?: ThinkingTier;
  /** Normalized thinking config from user settings */
  normalizedThinking?: { includeThoughts?: boolean; thinkingBudget?: number };
  /** Google Search configuration */
  googleSearch?: GoogleSearchConfig;
}

export interface GeminiTransformResult {
  toolDebugMissing: number;
  toolDebugSummaries: string[];
  /** Number of function declarations after wrapping */
  wrappedFunctionCount: number;
  /** Number of passthrough tools (googleSearch, googleSearchRetrieval, codeExecution) */
  passthroughToolCount: number;
}

/**
 * Apply all Gemini-specific transformations.
 */
export function applyGeminiTransforms(
  payload: RequestPayload,
  options: GeminiTransformOptions,
): GeminiTransformResult {
  const { model, tierThinkingBudget, tierThinkingLevel, normalizedThinking, googleSearch } = options;

  // 1. Apply thinking config if needed
  if (normalizedThinking) {
    let thinkingConfig: ThinkingConfig;

    if (tierThinkingLevel && isGemini3Model(model)) {

      thinkingConfig = buildGemini3ThinkingConfig(
        normalizedThinking.includeThoughts ?? true,
        tierThinkingLevel,
      );
    } else {

      const thinkingBudget = tierThinkingBudget ?? normalizedThinking.thinkingBudget;
      thinkingConfig = buildGemini25ThinkingConfig(
        normalizedThinking.includeThoughts ?? true,
        thinkingBudget,
      );
    }

    const generationConfig = (payload.generationConfig ?? {}) as Record<string, unknown>;
    generationConfig.thinkingConfig = thinkingConfig;
    payload.generationConfig = generationConfig;
  }

  // 2. Apply Google Search (Grounding) if enabled

  if (googleSearch && googleSearch.mode === 'auto') {
    const tools = (payload.tools as unknown[]) || [];
    if (!payload.tools) {
      payload.tools = tools;
    }


    (payload.tools as any[]).push({
      googleSearch: {},
    });
  }

  // 3. Normalize tools
  const result = normalizeGeminiTools(payload);

  // 4. Wrap tools in functionDeclarations format (fixes #203, #206)

  // Must be: [{ functionDeclarations: [{ name, description, parameters }] }]
  const wrapResult = wrapToolsAsFunctionDeclarations(payload);

  return {
    ...result,
    wrappedFunctionCount: wrapResult.wrappedFunctionCount,
    passthroughToolCount: wrapResult.passthroughToolCount,
  };
}

export interface WrapToolsResult {
  wrappedFunctionCount: number;
  passthroughToolCount: number;
}

/**
 * Wrap tools array in Gemini's required functionDeclarations format.
 * 
 * Gemini/Antigravity API expects:
 *   { tools: [{ functionDeclarations: [{ name, description, parameters }] }] }
 * 
 * NOT:
 *   { tools: [{ function: {...}, parameters: {...} }] }
 * 
 * The wrapper-level 'parameters' field causes:
 *   "Unknown name 'parameters' at 'request.tools[0]'"
 */
/**
 * Detect if a tool is a web search tool in any of the supported formats:
 * - Claude/Anthropic: { type: "web_search_20250305" } or { name: "web_search" }
 * - Gemini native: { googleSearch: {} } or { googleSearchRetrieval: {} }
 */
function isWebSearchTool(tool: Record<string, unknown>): boolean {
  // 1. Gemini native format
  if (tool.googleSearch || tool.googleSearchRetrieval) {
    return true;
  }

  // 2. Claude/Anthropic format: { type: "web_search_20250305" }
  if (tool.type === "web_search_20250305") {
    return true;
  }

  // 3. Simple name-based format: { name: "web_search" | "google_search" }
  const name = tool.name as string | undefined;
  if (name === "web_search" || name === "google_search") {
    return true;
  }

  return false;
}

export function wrapToolsAsFunctionDeclarations(payload: RequestPayload): WrapToolsResult {
  if (!Array.isArray(payload.tools) || payload.tools.length === 0) {
    return { wrappedFunctionCount: 0, passthroughToolCount: 0 };
  }

  const functionDeclarations: Array<{
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  }> = [];

  const passthroughTools: unknown[] = [];
  let hasWebSearchTool = false;

  for (const tool of payload.tools as Array<Record<string, unknown>>) {

    if (tool.googleSearch || tool.googleSearchRetrieval || tool.codeExecution) {
      passthroughTools.push(tool);
      continue;
    }


    if (isWebSearchTool(tool)) {
      hasWebSearchTool = true;
      continue; // Will be added as { googleSearch: {} } at the end
    }

    if (tool.functionDeclarations) {
      if (Array.isArray(tool.functionDeclarations)) {
        for (const decl of tool.functionDeclarations as Array<Record<string, unknown>>) {
          functionDeclarations.push({
            name: String(decl.name || `tool-${functionDeclarations.length}`),
            description: String(decl.description || ""),
            parameters: (decl.parameters as Record<string, unknown>) || { type: "OBJECT", properties: {} },
          });
        }
      }
      continue;
    }

    const fn = tool.function as Record<string, unknown> | undefined;
    const custom = tool.custom as Record<string, unknown> | undefined;

    const name = String(
      tool.name ||
      fn?.name ||
      custom?.name ||
      `tool-${functionDeclarations.length}`
    );

    const description = String(
      tool.description ||
      fn?.description ||
      custom?.description ||
      ""
    );

    const schema = (
      fn?.input_schema ||
      fn?.parameters ||
      fn?.inputSchema ||
      custom?.input_schema ||
      custom?.parameters ||
      tool.parameters ||
      tool.input_schema ||
      tool.inputSchema ||
      { type: "OBJECT", properties: {} }
    ) as Record<string, unknown>;

    functionDeclarations.push({
      name,
      description,
      parameters: schema,
    });
  }

  const finalTools: unknown[] = [];

  if (functionDeclarations.length > 0) {
    finalTools.push({ functionDeclarations });
  }

  finalTools.push(...passthroughTools);


  if (hasWebSearchTool && functionDeclarations.length === 0) {
    finalTools.push({ googleSearch: {} });
  } else if (hasWebSearchTool && functionDeclarations.length > 0) {

    console.warn(
      "[gemini] web_search tool detected but cannot be combined with function declarations. " +
      "Use the explicit google_search() tool call instead."
    );
  }

  payload.tools = finalTools;

  return {
    wrappedFunctionCount: functionDeclarations.length,
    passthroughToolCount: passthroughTools.length + (hasWebSearchTool && functionDeclarations.length === 0 ? 1 : 0),
  };
}

interface ContentTurn {
  role: string;
  parts: any[];
  [key: string]: unknown;
}

/**
 * Separate parts into functionCall/functionResponse parts and other parts.
 */
function separateParts(parts: any[]): {
  functionCallParts: any[];
  functionResponseParts: any[];
  otherParts: any[];
} {
  const functionCallParts: any[] = [];
  const functionResponseParts: any[] = [];
  const otherParts: any[] = [];

  for (const part of parts) {
    if (!part || typeof part !== "object") {
      otherParts.push(part);
      continue;
    }
    if (part.functionCall || part.function_call) {
      functionCallParts.push(part);
    } else if (part.functionResponse || part.function_response) {
      functionResponseParts.push(part);
    } else {
      otherParts.push(part);
    }
  }

  return { functionCallParts, functionResponseParts, otherParts };
}

/**
 * Some Gemini / Antigravity backends reject a single model turn that contains
 * multiple parallel functionCall parts, even when the following user turn has
 * matching functionResponses. Expand to strict call → response → call → response.
 */
export function expandMultiFunctionCallModelTurns(contents: any[]): any[] {
  if (!Array.isArray(contents) || contents.length === 0) {
    return contents;
  }

  const result: any[] = [];

  for (let i = 0; i < contents.length; i++) {
    const turn = contents[i];
    if (!turn || typeof turn !== "object" || turn.role !== "model" || !Array.isArray(turn.parts)) {
      result.push(turn);
      continue;
    }

    const fcParts = turn.parts.filter(
      (p: any) => p && typeof p === "object" && (p.functionCall || p.function_call),
    );
    if (fcParts.length <= 1) {
      result.push(turn);
      continue;
    }

    const next = contents[i + 1];
    if (!next || next.role !== "user" || !Array.isArray(next.parts)) {
      result.push(turn);
      continue;
    }

    const frParts = next.parts.filter(
      (p: any) => p && typeof p === "object" && (p.functionResponse || p.function_response),
    );
    if (frParts.length !== fcParts.length) {
      result.push(turn);
      continue;
    }

    const otherParts = turn.parts.filter(
      (p: any) => !(p && typeof p === "object" && (p.functionCall || p.function_call)),
    );

    for (let j = 0; j < fcParts.length; j++) {
      const modelParts = j === 0 && otherParts.length > 0 ? [...otherParts, fcParts[j]] : [fcParts[j]];
      result.push({ ...turn, role: "model", parts: modelParts });
      result.push({ role: "user", parts: [frParts[j]] });
    }

    i++;
  }

  return result;
}

/**
 * Sanitize Gemini conversation contents to enforce strict turn ordering.
 *
 * Gemini API rules:
 * 1. Strict user/model alternation (no consecutive same-role turns)
 * 2. functionCall parts must be in "model" role turns
 * 3. functionResponse parts must be in "user" role turns
 * 4. Conversation must start with "user"
 * 5. A function_call turn must come immediately after a user turn or
 *    after a function_response turn
 *
 * This function:
 * - Normalizes roles ("assistant" to "model")
 * - Moves misplaced functionCall/functionResponse parts to correct roles
 * - Merges consecutive same-role turns
 * - Inserts empty filler turns to maintain alternation
 * - Ensures conversation starts with "user"
 */
export function sanitizeGeminiContents(contents: any[]): any[] {
  if (!Array.isArray(contents) || contents.length === 0) {
    return contents;
  }

  contents = expandMultiFunctionCallModelTurns(contents);


  const mergeTextParts = (parts: any[]) => {
    const merged = [];
    let currentText = "";
    
    for (const part of parts) {
      if (part && typeof part.text === "string") {
        currentText += (currentText ? "\n\n" : "") + part.text;
      } else {
        if (currentText) {
          merged.push({ text: currentText });
          currentText = "";
        }
        merged.push(part);
      }
    }
    
    if (currentText) {
      merged.push({ text: currentText });
    }
    
    return merged;
  };


  const normalized: ContentTurn[] = [];

  for (const content of contents) {
    if (!content || typeof content !== "object") continue;

    let role = content.role as string;
    const parts = Array.isArray(content.parts) ? mergeTextParts([...content.parts]) : [];

    if (parts.length === 0) continue;


    if (role === "assistant") {
      role = "model";
    }

    const { functionCallParts, functionResponseParts, otherParts } = separateParts(parts);

    if (role === "model") {

      const modelParts = [...otherParts, ...functionCallParts];
      if (modelParts.length > 0) {
        normalized.push({ ...content, role: "model", parts: modelParts });
      }
      if (functionResponseParts.length > 0) {
        normalized.push({ role: "user", parts: functionResponseParts });
      }
    } else {

      if (functionCallParts.length > 0) {
        normalized.push({ role: "model", parts: functionCallParts });
      }
      if (functionResponseParts.length > 0) {
        normalized.push({ role: "user", parts: functionResponseParts });
      }
      if (otherParts.length > 0) {
        normalized.push({ ...content, role: "user", parts: otherParts });
      }
    }
  }

  if (normalized.length === 0) {
    return contents;
  }


  const merged: ContentTurn[] = [normalized[0]!];

  for (let i = 1; i < normalized.length; i++) {
    const current = normalized[i]!;
    const previous = merged[merged.length - 1]!;

    const hasFunc = (parts: any[]) => parts.some(p => p.functionCall || p.functionResponse || p.function_call || p.function_response);
    const prevFunc = hasFunc(previous.parts);
    const currFunc = hasFunc(current.parts);

    if (current.role === previous.role && prevFunc === currFunc) {
      previous.parts = [...previous.parts, ...current.parts];
    } else {
      merged.push(current);
    }
  }


  if (merged[0]!.role !== "user") {
    merged.unshift({ role: "user", parts: [{ text: "acknowledged" }] });
  }


  const result: ContentTurn[] = [merged[0]!];

  for (let i = 1; i < merged.length; i++) {
    const current = merged[i]!;
    const previous = result[result.length - 1]!;

    if (current.role === previous.role) {
      const fillerRole = current.role === "model" ? "user" : "model";
      result.push({ role: fillerRole, parts: [{ text: "acknowledged" }] });
    }
    result.push(current);
  }


  return result.map(turn => ({
    ...turn,
    parts: mergeTextParts(turn.parts)
  }));
}

/**
 * Fix Gemini tool call/response pairing.
 *
 * Ensures every functionCall in a model turn has a matching functionResponse
 * in the immediately following user turn. If not, injects a placeholder
 * response to prevent the API from rejecting the request.
 */
export function fixGeminiToolPairing(contents: any[]): any[] {
  if (!Array.isArray(contents) || contents.length === 0) {
    return contents;
  }

  const result = contents.map((c: any) => ({
    ...c,
    parts: Array.isArray(c.parts) ? [...c.parts] : [],
  }));


  const callsByModelIdx = new Map<number, { name: string; id?: string }[]>();

  for (let i = 0; i < result.length; i++) {
    const turn = result[i];
    if (turn.role !== "model") continue;

    const calls: { name: string; id?: string }[] = [];
    for (const part of turn.parts) {
      const fc = part?.functionCall || part?.function_call;
      if (!fc) continue;
      calls.push({ name: fc.name || "unknown", id: fc.id || undefined });
    }

    if (calls.length > 0) {
      callsByModelIdx.set(i, calls);
    }
  }

  for (const [modelIdx, calls] of callsByModelIdx.entries()) {
    const nextIdx = modelIdx + 1;
    if (nextIdx >= result.length) {
      const responseParts = calls.map((call) => ({
        functionResponse: {
          name: call.name,
          ...(call.id ? { id: call.id } : {}),
          response: { result: "[pending]" },
        },
      }));
      result.push({ role: "user", parts: responseParts });
      continue;
    }

    const nextTurn = result[nextIdx];
    if (nextTurn.role !== "user") continue;

    const existingResponseNames = new Set<string>();
    const existingResponseIds = new Set<string>();

    for (const part of nextTurn.parts) {
      const fr = part?.functionResponse || part?.function_response;
      if (!fr) continue;
      if (fr.name) existingResponseNames.add(fr.name);
      if (fr.id) existingResponseIds.add(fr.id);
    }

    for (const call of calls) {
      const hasMatchById = call.id && existingResponseIds.has(call.id);
      const hasMatchByName = existingResponseNames.has(call.name);

      if (!hasMatchById && !hasMatchByName) {
        nextTurn.parts.push({
          functionResponse: {
            name: call.name,
            ...(call.id ? { id: call.id } : {}),
            response: { result: "[no response received]" },
          },
        });
      }
    }
  }

  return result;
}
