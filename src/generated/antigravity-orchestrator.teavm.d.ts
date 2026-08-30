// Generated from Java sources. Do not edit.

/**
 * One streamed response's line-by-line transform, held open across its lines.
 *
 * @remarks
 * Stateful, because the thought and sent buffers and the debug-injected flag persist for
 * the whole response. The host owns the framing that splits the bytes into lines; every decision
 * about a line is made here.
 */
export interface AntigravitySseTransformHandle {
  /**
   * What the host should emit in place of one upstream line.
   *
   * @param line - the upstream SSE line, without its terminator
   * @returns the line to emit
   */
  handle(line: string): string;
}

/**
 * One upstream stream's decode into canonical IR events, held open across its chunks.
 *
 * @remarks
 * Stateful, because a Gemini SSE chunk boundary falls anywhere and the mapper carries the
 * partial line plus the ids it has minted so far. The host holds one per response and never reuses
 * it.
 */
export interface AntigravityIrStreamMapperHandle {
  /**
   * The IR events left over once the upstream closes.
   *
   * @returns the events, as a JSON array
   */
  finish(): string;
  /**
   * The IR events one raw chunk yields.
   *
   * @param chunk - the upstream SSE text, which may end mid-line
   * @returns the events, as a JSON array
   */
  handle(chunk: string): string;
}

/**
 * The host's id minting, which the stream mapper uses rather than baking entropy into the bundle.
 *
 * @remarks
 * Two methods rather than one taking a kind, because the host mints them with different
 * prefixes and a kind argument would move that decision into the transpiled side.
 */
export interface AntigravityStreamIdsShape {
  /**
   * A fresh id for one assistant message.
   *
   * @returns the message id
   */
  newMessageId(): string;
  /**
   * A fresh id for one tool call.
   *
   * @returns the tool-call id
   */
  newToolId(): string;
}

/**
 * The host's set of thinking-text hashes already shown, which survives an SSE reconnect.
 *
 * @remarks
 * Held by the host rather than the stream transformer because a reconnect builds a new
 * transformer for the same session, and a per-transformer set would show every thought twice.
 */
export interface AntigravityThoughtDedupShape {
  /**
   * Records that a thought has been shown.
   *
   * @param hash - the thinking text's hash
   */
  add(hash: string): void;
  /**
   * Whether a thought has already been shown.
   *
   * @param hash - the thinking text's hash
   * @returns true when it was shown before
   */
  has(hash: string): boolean;
}

/**
 * The host's thinking-signature store, as the request preparer and the response transform reach it.
 *
 * @remarks
 * The host store is keyed by one string and holds a text/signature pair, so `get`
 * answers with that pair as JSON while `set` takes the two halves apart. Both spellings are
 * the store's own, not a shape invented for this boundary.
 */
export interface AntigravitySignatureStoreShape {
  /**
   * Drops whatever one key holds.
   *
   * @param key - the signature key
   */
  delete(key: string): void;
  /**
   * The signature pair held under one key.
   *
   * @param key - the signature key
   * @returns the `text`/`signature` pair as JSON, or null when nothing is held
   */
  get(key: string): string | null;
  /**
   * Whether a key holds a signature.
   *
   * @param key - the signature key
   * @returns true when one is held
   */
  has(key: string): boolean;
  /**
   * Records the signature a thinking block was returned with.
   *
   * @param sessionKey - the key to hold it under
   * @param text - the thinking text the signature covers
   * @param signature - the signature the upstream returned
   */
  set(sessionKey: string, text: string, signature: string): void;
}

/**
 * The per-family quota aggregate the account view shows, from what the upstream reported per
 * model.
 *
 * @param modelsJson - the upstream model list, as a JSON object keyed by model id
 * @returns the worst remaining fraction and earliest reset per family, as JSON
 */
export declare function aggregateQuota(modelsJson: string): string;
/**
 * When one account can next serve.
 *
 * @param accountJson - the account, as JSON
 * @param now - the current time, in epoch milliseconds
 * @returns the epoch-millisecond time, or infinity for an account out of rotation
 */
export declare function antigravityAvailableAt(accountJson: string, now: number): number;
/**
 * The quota bars one account's cached quota renders as.
 *
 * @param accountJson - the account, as JSON
 * @returns the bars as a JSON array, or null when the account has no cached quota
 */
export declare function antigravityQuota(accountJson: string): string | null;
/**
 * What the account view shows as one account's state.
 *
 * @param accountJson - the account, as JSON
 * @param now - the current time, in epoch milliseconds
 * @returns the status word
 */
export declare function antigravityStatus(accountJson: string, now: number): string;
/**
 * The model catalog this provider offers, from what the upstream lists.
 *
 * @param payloadJson - the upstream model payload, as JSON
 * @returns the catalog, as JSON
 */
export declare function buildCatalog(payloadJson: string): string;
/**
 * The platform enum the upstream project endpoints validate their request metadata against.
 *
 * @param platform - the host platform name, as the runtime reports it
 * @param arch - the host architecture, as the runtime reports it
 * @returns the enum value, or the unspecified one for a platform the upstream does not name
 */
export declare function detectCodeAssistPlatform(platform: string, arch: string): string;
/**
 * Which accounts are due a User-Agent version drift, and what to write on each.
 *
 * @param accountsJson - the accounts to consider, as a JSON array
 * @param now - the current time, in epoch milliseconds
 * @param versionListJson - the versions to pick from, as a JSON array
 * @param jsRandom - the host's entropy, so an account's drift is not baked into the bundle
 * @returns the mutations in order, as a JSON array, for the host to apply
 */
export declare function driftAccountVersionsProd(accountsJson: string, now: number, versionListJson: string, jsRandom: (() => number)): string;
/**
 * A project id for an account that has no discovered managed project.
 *
 * @param jsRandom - the host's entropy
 * @param jsUuid - the host's id minting
 * @returns the synthetic project id
 */
export declare function generateSyntheticProjectIdProd(jsRandom: (() => number), jsUuid: (() => string)): string;
/**
 * Runs the whole attempt loop: resolve the model, walk the accounts, discover project context,
 * classify each outcome, and decide what the host serves.
 *
 * @param inputsJson - the request's url, method, headers, body and model, as a JSON object
 * @param configJson - the platform, architecture and optional fixed clock, as a JSON object
 * @param jsExec - the host's transport, taking an account id and a prepared request and answering
 * with the attempt's outcome as JSON
 * @param jsAcquire - the host's account rotation, taking a lane and answering with the account it
 * picked as JSON, or null when none is free
 * @param jsAccountOps - what the loop tells the host about each account it used
 * @param jsProjectLoader - the host's managed-project fetch
 * @param jsProjectOnboarder - the host's managed-project onboarding
 * @param jsPreparer - the host's request preparation, answering with an opaque reference plus the
 * transform parameters as JSON, or null when preparing threw
 * @param autoCandidatesJson - the leaderboard's ranking, which stays the host's, as a JSON array
 * @param jsRandom - the host's entropy
 * @param jsUuid - the host's id minting
 * @returns the decision as JSON: which attempt to serve, or a synthetic response to answer with
 */
export declare function handleAntigravityRequestAsync(inputsJson: string, configJson: string, jsExec: ((a: string, b: string) => Promise<string>), jsAcquire: ((lane: string) => Promise<string | null>), jsAccountOps: AntigravityAccountOpsShape, jsProjectLoader: ((accessToken: string, projectId: string, proxy: string) => Promise<string | null>), jsProjectOnboarder: ((accessToken: string, tierId: string, projectId: string, proxy: string) => Promise<string | null>), jsPreparer: ((url: string, bodyText: string, method: string, headersJson: string, access: string, projectId: string, endpoint: string, headerStyle: string, accountJson: string) => string | null), autoCandidatesJson: string, jsRandom: (() => number), jsUuid: (() => string)): Promise<string>;
/**
 * Opens one upstream stream's decode into canonical IR events.
 *
 * @param model - the model to stamp on every event
 * @param jsIds - the host's id minting
 * @returns the handle to feed the stream's chunks through
 */
export declare function newIrStreamMapper(model: string, jsIds: AntigravityStreamIdsShape): AntigravityIrStreamMapperHandle;
/**
 * Opens one streamed response's transform.
 *
 * @param signatureSessionKey - the key thinking signatures are stored under for this session
 * @param debugText - the thinking placeholder to inject, or empty for none
 * @param cacheSignatures - whether this model's thinking signatures are worth storing
 * @param jsSignatureStore - the host's signature store
 * @param jsCacheSignature - the host's on-disk signature-cache write
 * @param jsImageSink - the host's image writer, answering with a markdown link or null
 * @param jsThoughtDedup - the host's already-shown thought hashes, or null for a model that does
 * not reconnect mid-thought
 * @returns the handle to feed the response's lines through
 */
export declare function newResponseSseTransformer(signatureSessionKey: string, debugText: string, cacheSignatures: boolean, jsSignatureStore: AntigravitySignatureStoreShape, jsCacheSignature: ((sessionKey: string, text: string, signature: string) => void), jsImageSink: ((mimeType: string, base64Data: string) => string | null), jsThoughtDedup: AntigravityThoughtDedupShape | null): AntigravitySseTransformHandle;
/**
 * The client version a new account presents, weighted toward the newest.
 *
 * @param versionListJson - the versions to pick from, as a JSON array
 * @param min - the lowest version worth picking, or empty for no floor
 * @param jsRandom - the host's entropy
 * @returns the chosen version
 */
export declare function pickVersionProd(versionListJson: string, min: string, jsRandom: (() => number)): string;
/**
 * One request, prepared for one account and endpoint: url, headers, body, and what the response
 * transform will need.
 *
 * @param url - the request url
 * @param method - the request method
 * @param headersJson - the caller's headers, as a JSON object
 * @param body - the request body
 * @param accessToken - the account's access token
 * @param projectId - the project the request is billed to
 * @param headerStyle - which header set this endpoint expects
 * @param fingerprintJson - the account's device fingerprint, as JSON, or the JSON null
 * @param keepThinking - whether thinking blocks stay in the request
 * @param pluginSessionId - the host's session id, which keys the signature cache
 * @param endpointOverride - the endpoint this attempt must use, or empty for the default
 * @param claudeToolHardening - whether Claude tool schemas are hardened
 * @param claudePromptAutoCaching - whether prompt caching markers are added
 * @param cliFirst - whether the free CLI quota lane is tried first
 * @param jsRandom - the host's entropy
 * @param jsUuid - the host's id minting
 * @param jsHasher - the host's sha256, returning full hex
 * @param jsCacheLookup - the host's signature-cache read
 * @param jsSignatureStore - the host's signature store
 * @returns the prepared request as JSON: url, headers, body, and the transform parameters
 */
export declare function prepareAntigravityRequestProd(url: string, method: string, headersJson: string, body: string, accessToken: string, projectId: string, headerStyle: string, fingerprintJson: string, keepThinking: boolean, pluginSessionId: string, endpointOverride: string, claudeToolHardening: boolean, claudePromptAutoCaching: boolean, cliFirst: boolean, jsRandom: (() => number), jsUuid: (() => string), jsHasher: ((value: string) => string), jsCacheLookup: ((sessionId: string, text: string) => string | null), jsSignatureStore: AntigravitySignatureStoreShape): string;
/**
 * The project id one account's requests are billed to, discovering and minting one where the
 * account has none yet.
 *
 * @param accountJson - the account, as JSON
 * @param access - the account's access token
 * @param jsRandom - the host's entropy
 * @param jsUuid - the host's id minting
 * @param jsProjectLoader - the host's managed-project fetch
 * @param jsProjectOnboarder - the host's managed-project onboarding
 * @param configJson - the platform and architecture, as a JSON object
 * @returns the id and what to persist on the account, as JSON
 */
export declare function resolveProjectIdProd(accountJson: string, access: string, jsRandom: (() => number), jsUuid: (() => string), jsProjectLoader: ((accessToken: string, projectId: string, proxy: string) => Promise<string | null>), jsProjectOnboarder: ((accessToken: string, tierId: string, projectId: string, proxy: string) => Promise<string | null>), configJson: string): Promise<string>;
/**
 * The already-decoded IR request, with this provider's thinking budget resolved, encoded for
 * the upstream.
 *
 * @param irJson - the decoded IR request, as JSON
 * @param model - the model the request will be served as
 * @returns the upstream request body, as JSON
 */
export declare function resolveThinkingBudgetAndEncodeGemini(irJson: string, model: string): string;
/**
 * The buffered half of the served response's transform.
 *
 * @param bodyText - the upstream body
 * @param status - the upstream status
 * @param headersJson - the upstream headers, as a JSON object
 * @param requestedModel - the model the caller asked for, or empty
 * @param debugText - the thinking placeholder to inject, or empty for none
 * @param jsImageSink - the host's image writer, answering with a markdown link or null
 * @returns the status, headers and body to answer with, as JSON
 */
export declare function transformServeBodyProd(bodyText: string, status: number, headersJson: string, requestedModel: string, debugText: string, jsImageSink: ((mimeType: string, base64Data: string) => string | null)): string;

/**
 * What the orchestrator tells the host about the accounts it is rotating through.
 *
 * @remarks
 * Grouped into one object rather than passed as seven functions, because the transpiled
 * side invokes them by name on the underlying JS object. Every account crosses as JSON text, since
 * the host's account store already serialises it.
 */
export interface AntigravityAccountOpsShape {
  /**
   * Every account the host currently holds.
   *
   * @returns the accounts, as a JSON array
   */
  list(): string;
  /**
   * Persists the fields the orchestrator changed on one account.
   *
   * @param accountId - the account to write back
   * @param updatedAccountJson - the whole account after the change, as JSON
   */
  mutate(accountId: string, updatedAccountJson: string): void;
  /**
   * When a lane's soonest account comes back into rotation.
   *
   * @param lane - the lane being asked about
   * @returns the epoch-millisecond time as JSON, or `"null"` when nothing is waiting
   */
  nextAvailableAt(lane: string): string;
  /**
   * One attempt failed for a reason that is not a rate limit.
   *
   * @param accountId - the account that failed
   * @param lane - the lane it failed on
   * @param attempt - which attempt this was, counting from one
   * @param message - what went wrong
   */
  reportError(accountId: string, lane: string, attempt: number, message: string): void;
  /**
   * An attempt failed in a way that implicates the outbound IP rather than the account.
   *
   * @param accountId - the account the attempt used
   * @param ipSuspected - whether the proxy address is the likely cause
   */
  reportProxyRateLimit(accountId: string, ipSuspected: boolean): void;
  /**
   * One attempt hit the upstream rate limit.
   *
   * @param accountId - the account that was limited
   * @param lane - the lane it was limited on
   * @param resetMs - when the limit resets, in epoch milliseconds
   */
  reportRateLimit(accountId: string, lane: string, resetMs: number): void;
  /**
   * One attempt served the request.
   *
   * @param accountId - the account that served it
   */
  reportSuccess(accountId: string): void;
}

