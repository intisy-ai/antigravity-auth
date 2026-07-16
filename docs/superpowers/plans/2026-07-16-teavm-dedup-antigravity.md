# Antigravity-auth TeaVM De-dup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Java the single source of truth for antigravity-auth's request/response/transform logic; delete the duplicated TypeScript; republish v1.10.0 (npm + jar), byte-identical behavior.

**Architecture:** The Java port + TeaVM export already exist. This plan (1) ports the only two unported logic files, (2) promotes the stub-seamed TeaVM exports to production exports taking real host seams + adds an incremental stream-mapper factory, (3) rewires `javaHandle.ts` to call Java instead of the TS transforms, (4) live-verifies with a real account, (5) deletes the duplicated TS and freezes fixtures, (6) does the recipe tail (gradlew +x, submodule reconcile, republish).

**Tech Stack:** TypeScript + vitest (plugin); Java 8 + Gradle + JUnit (`java/antigravity-provider`); TeaVM (`java/antigravity-teavm` → `src/generated/antigravity-orchestrator.teavm.js`); esbuild bundling.

## Global Constraints

- **No behavior change.** Output must be byte-identical to today's TS path. Every deletion is gated behind a live real-account verification and a fixture freeze.
- **Never ship the stub seams.** `FIXED_RANDOM=0.5` + counter ids made `generateSyntheticProjectId` a constant → account correlation (CRITICAL-1). Production MUST inject real `Math.random` + `crypto.randomUUID` and real hasher/cache/store seams.
- **Comments: STRICT minimal.** Only genuinely non-obvious one-liners. No narration.
- **Lazy TeaVM load.** Keep the dynamic `import("../generated/antigravity-orchestrator.teavm.js")` memoized in `javaHandle.ts`; never import at plugin registration.
- **UTF-8 javac** is already in `java/build.gradle` — leave it. **`java/gradlew` must be mode 100755** (fix in Task 8).
- **OAuth secret** from `ANTIGRAVITY_CLIENT_SECRET` env ONLY; `client_id` public literal OK. Never log refresh/access tokens.
- **Branch `experimental`**; antigravity uses `sync-experimental-to-main.yml` → PUSH experimental (auto-merges to main); never ff/force main.
- **Publish (Task 8, tag) is USER-GATED** — explicit consent naming antigravity-auth before any `v*` tag.
- Prefer editing existing files; one concern per file; functions small.

---

### Task 1: Port `thinking-recovery.ts` + `recovery.ts:detectErrorType` to Java

**Files:**
- Create: `java/antigravity-provider/src/main/java/io/github/intisy/ai/antigravity/AntigravityThinkingRecovery.java`
- Modify: `java/antigravity-provider/src/main/java/io/github/intisy/ai/antigravity/AntigravityResponseParse.java` (add `detectErrorType`)
- Modify: `java/antigravity-provider/src/main/java/io/github/intisy/ai/antigravity/AntigravityProvider.java` (replace `NOOP_THINKING_RECOVERY` with the real impl)
- Test: `java/antigravity-provider/src/test/java/io/github/intisy/ai/antigravity/AntigravityThinkingRecoveryTest.java`
- Reference (port source): `src/plugin/thinking-recovery.ts`, `src/plugin/recovery.ts`

**Interfaces:**
- Consumes: the existing `AntigravityRequestPrep.ThinkingRecovery` interface (`analyzeConversationState(List<Object>) → Object`, `needsThinkingRecovery(Object) → boolean`, `closeToolLoopForThinking(List<Object>) → List<Object>`), the `JsonCodec` SPI.
- Produces: `AntigravityThinkingRecovery implements AntigravityRequestPrep.ThinkingRecovery` (constructed with `JsonCodec`), plus `looksLikeCompactedThinkingTurn`. `AntigravityResponseParse.detectErrorType(String message) → String` (returns e.g. `"thinking_block_order"` or `null`), used by the response transform to set `forceThinkingRecovery`.

- [ ] **Step 1: Write failing tests frozen against the TS behavior**

Port the assertions from `src/plugin/thinking-recovery.test.ts` and `src/plugin/recovery.test.ts` into `AntigravityThinkingRecoveryTest` (use `TestJsonCodec`). Cover: `analyzeConversationState` on (a) a clean turn, (b) a compacted thinking turn (thinking stripped, trailing tool results) → `needsThinkingRecovery` true; `closeToolLoopForThinking` closes the loop; `detectErrorType` maps the known upstream error strings (the `thinking_block_order` / "thinking block" messages in `recovery.ts`) to their type and everything else to `null`. Freeze the exact inputs/outputs the TS test used.

- [ ] **Step 2: Run tests, verify they fail**

Run: `cd java && ./gradlew :antigravity-provider:test --tests '*AntigravityThinkingRecoveryTest*'`
Expected: FAIL (class/method not found).

- [ ] **Step 3: Port the logic faithfully**

Translate `thinking-recovery.ts` (all exports + internal `stripAllThinkingBlocks`/`countTrailingToolResults`/`messageHasThinking`/`isToolResultMessage`) into `AntigravityThinkingRecovery` over `Map`/`List`/`String` + the `JsonCodec` SPI, keeping TeaVM-eligible constructs only (no reflection, no regex features TeaVM lacks — mirror the patterns in `AntigravityToolPairing`). Add `AntigravityResponseParse.detectErrorType`. In `AntigravityProvider`, replace the `NOOP_THINKING_RECOVERY` field with `new AntigravityThinkingRecovery(json)` so the jar SPI path gains real recovery too.

- [ ] **Step 4: Run tests, verify pass + no regressions**

Run: `cd java && ./gradlew :antigravity-provider:test`
Expected: PASS (new test + all 465+ existing).

- [ ] **Step 5: Commit**

```bash
git add java/antigravity-provider/src/main/java/io/github/intisy/ai/antigravity/AntigravityThinkingRecovery.java java/antigravity-provider/src/main/java/io/github/intisy/ai/antigravity/AntigravityResponseParse.java java/antigravity-provider/src/main/java/io/github/intisy/ai/antigravity/AntigravityProvider.java java/antigravity-provider/src/test/java/io/github/intisy/ai/antigravity/AntigravityThinkingRecoveryTest.java
git commit -m "feat(antigravity-java): port thinking-recovery + detectErrorType (real seam for jar + TeaVM)"
```

---

### Task 2: Production TeaVM exports — real-seam `prepareAntigravityRequest`, `newStreamMapper` factory, response-transform pieces

**Files:**
- Modify: `java/antigravity-teavm/src/main/java/io/github/intisy/ai/js/AntigravityProviderJs.java`
- Reference: the existing `handleAntigravityRequestAsync` real-seam pattern (`jsRandom`/`jsUuid`, lines ~772-835) and the stub `prepareAntigravityRequest` (lines ~529-598).

**Interfaces (Produces — the production JS surface `javaHandle.ts` will call):**
- `prepareAntigravityRequestProd(url, method, headersJson, body, accessToken, projectId, headerStyle, fingerprintJson, keepThinking, pluginSessionId, jsRandom, jsUuid, jsHasher, jsCacheLookup, jsSignatureStore) → String` (the prepared `{request,headers,body,streaming,effectiveModel,projectId,sessionId,headerStyle}` JSON). `thinkingRecovery` is now the internal `AntigravityThinkingRecovery` (Task 1), not a JS seam.
- `newStreamMapper(model, jsIds) → JSObject` with methods `handle(objJson: String) → String[]` and `finish() → String[]` — a stateful handle over one `AntigravityStreamMapper` instance.
- `cacheSignaturesFromResponse(responseJson, jsSignatureStore) → void` (export for `cacheThinkingSignaturesFromResponse`).
- Seam `@JSFunctor` types: `JsHasherFn { String hash(String); }`, `JsCacheLookupFn { String get(String sessionId, String text); }`, `JsSignatureStoreFns { String get(String); boolean has(String); void delete(String); }`, `JsIdsFns { String newMessageId(); String newToolId(); }`. Reuse existing `JsRandomFn`/`JsUuidFn`.

- [ ] **Step 1: Write the failing generate check**

There is no unit runner for TeaVM output; the gate is `generateJavaScript`. Add the new exports (Step 3) then Step 2 proves they transpile. (No separate failing test step — the JVM logic they call is already tested in `:antigravity-provider`.)

- [ ] **Step 2: Verify current generate is green (baseline)**

Run: `cd java && ./gradlew :antigravity-teavm:generateJavaScript`
Expected: PASS (baseline before edits).

- [ ] **Step 3: Add the production exports**

Add `prepareAntigravityRequestProd` mirroring the stub `prepareAntigravityRequest` but building `AntigravityRequestPrep.Deps` from the injected seams: `deps.random = () -> jsRandom.next()`, `deps.ids = () -> jsUuid.uuid().stringValue()`, `deps.hasher = input -> jsHasher.hash(input)`, `deps.cachedSignatureLookup = (s,t) -> jsCacheLookup.get(s,t)`, `deps.signatureStore` bridged to `jsSignatureStore` get/has/delete, `deps.thinkingRecovery = new AntigravityThinkingRecovery(json)`, `deps.logger = NOOP_LOGGER`, `deps.keepThinking = keepThinking`, `deps.pluginSessionId = pluginSessionId`, `deps.selectedHeaders = AntigravityFingerprint.buildFingerprintHeaders(fp)` (from `fingerprintJson`), `in.fingerprint = fp`. Add `newStreamMapper` returning an anonymous `JSObject` exposing `handle`/`finish` that delegate to a captured `AntigravityStreamMapper(json, idsFromJs, model)`; return `String[]` via `JSArray`/`String[]` per the module's existing array-return idiom. Add `cacheSignaturesFromResponse` calling `AntigravityStreamTransform.cacheThinkingSignaturesFromResponse` with the store seam.

- [ ] **Step 4: Verify generate is green**

Run: `cd java && ./gradlew :antigravity-teavm:generateJavaScript`
Expected: PASS (new exports transpile).

- [ ] **Step 5: Commit**

```bash
git add java/antigravity-teavm/src/main/java/io/github/intisy/ai/js/AntigravityProviderJs.java
git commit -m "feat(antigravity-teavm): production seam exports (prepare, stream-mapper factory, sig cache)"
```

---

### Task 3: Rewire `javaHandle.ts` to call the Java transforms

**Files:**
- Modify: `src/driver/javaHandle.ts`
- Create: `src/driver/javaStream.ts` (the thin `TransformStream` wrapper over `newStreamMapper` + the per-line response SSE driver)
- Test: `src/__tests__/handle-parity.test.ts` stays green throughout (it currently pins the Java path).

**Interfaces:**
- Consumes: the Task 2 exports via the memoized `loadOrchestrator()` import.
- Produces: `javaStream.ts` exporting `makeAnthropicStream(model, ids)` (a `TransformStream<Uint8Array, Uint8Array>` driving `newStreamMapper`) and `makeResponseTransformStream(...)` for `transformAntigravityResponse`'s streaming branch, both holding host buffers across chunks.

- [ ] **Step 1: Write a failing parity test for the Java prepare path**

Add a test asserting that `jsPreparer` output (now Java) equals the retained TS `prepareAntigravityRequest` output for a representative Gemini + Claude body (freeze the TS output as a fixture `src/__tests__/prepare-scenarios.expected.json`). This proves the seam switch is parity-preserving.

- [ ] **Step 2: Run it, verify it fails**

Run: `npx vitest run src/__tests__/handle-parity.test.ts`
Expected: FAIL (jsPreparer still TS / fixture absent).

- [ ] **Step 3: Switch `jsPreparer` to the Java prepare + wire the real seams**

In `javaHandle.ts`, replace the TS `prepareAntigravityRequest` call in `jsPreparer` with `prepareAntigravityRequestProd`, passing the real host seams: `jsHasher` = `crypto.createHash("sha256")…` (the `request.ts:112` hash), `jsCacheLookup` = `getCachedSignature` (cache.ts), `jsSignatureStore` = `defaultSignatureStore`, `keepThinking` = `getKeepThinking()`, `pluginSessionId` = `PLUGIN_SESSION_ID`, `fingerprintJson` = `JSON.stringify(account.meta.fingerprint)`, plus existing `jsRandom`/`jsUuid`. Retain the prepared `{request,init}` host-side exactly as now (the transport still runs the real `fetch`).

- [ ] **Step 4: Route the SERVE transform + anthropic bridge through Java**

In `materializeDecision` SERVE, replace `transformAntigravityResponse` with the Java-driven transform: for non-streaming, call the Java response-parse/transform pieces; for streaming, pipe the retained response body through `makeResponseTransformStream` (Java `transformSseLine` per line, host buffers). In `handleAnthropicMessagesViaJava`, replace `anthropicToGemini` with the Java export and `geminiToAnthropicStream` with `makeAnthropicStream` over `newStreamMapper` (host mints ids). Keep the classification/`x-hub-*` handling host-side verbatim.

- [ ] **Step 5: Build + run all tests**

Run: `npm run build && npx vitest run`
Expected: PASS (parity test + contract + suites).

- [ ] **Step 6: Commit**

```bash
git add src/driver/javaHandle.ts src/driver/javaStream.ts src/__tests__/
git commit -m "feat(antigravity): javaHandle calls Java prepare/transform/stream via real seams"
```

---

### Task 4: Live-verify GATE (controller-run, real account) — capture fixtures

**Files:**
- Create (scratchpad, not committed): `…/scratchpad/antigravity-live-verify.mjs`
- Create: `src/__tests__/handle-scenarios.expected.json` (committed regression fixture)

**This task is controller-run (needs a real account); not a subagent.**

- [ ] **Step 1: Build the real handler**: `npm run build`.
- [ ] **Step 2: Drive `dist/handler.js`** through the real config system (`HUB_CONFIG_DIR` temp dir, real account, flag ON) across: non-streaming Gemini request, **streaming** request (assert the SSE event sequence AND that bytes arrive incrementally, not buffered), `/v1/messages` Anthropic bridge, an Auto request, and no-account (503). Never print tokens.
- [ ] **Step 3:** Confirm each response matches the pre-rewire TS baseline (run once with flag OFF to capture, once ON to compare). Freeze the scenario outputs into `handle-scenarios.expected.json`.
- [ ] **Step 4: Commit the fixture**: `git add src/__tests__/handle-scenarios.expected.json && git commit -m "test(antigravity): freeze Java-path scenarios (live-verified)"`.

**GATE:** do not proceed to deletion (Tasks 5-7) until this is green with real streaming confirmed incremental.

---

### Task 5: Flip the flag on, delete flag machinery + the `index.ts` decision loop

**Files:**
- Modify: `src/driver/index.ts`, `src/plugin/config/schema.ts` (remove `use_java_orchestrator`)
- Delete: `src/__tests__/handle-dormancy.test.ts`
- Modify: `src/__tests__/handle-parity.test.ts` → Java-path regression against `handle-scenarios.expected.json`
- Modify: `src/driver/javaHandle.ts` (remove the dormancy counter)

- [ ] **Step 1:** Make `handle` an unconditional lazy delegate:
```javascript
async function handle(request, ctx) {
  const log = (ctx && ctx.log) || (() => {});
  maybeMaintainVersions(log);
  const { handleViaJavaOrchestrator } = await import("./javaHandle.js");
  return handleViaJavaOrchestrator(request, ctx);
}
```
- [ ] **Step 2:** Delete from `index.ts` the DELETE functions (audit Section D): `attemptModel`, `resolveProjectId`, `syntheticProjectFor`, `handleAnthropicMessages`, `soonestQuotaReset`, `modelFromRequest`, `isAutoModel`, `rewriteModelInUrl`, `resolveEffortVariant`+`requestedThinkingLevel`+`LEVEL_ORDER`, `isRateLimitStatus`, `buildAuth`, `endpointsFor`, `driftAccountVersions`, `errorResponse`, `useJavaOrchestrator`, and the now-unused imports. KEEP: `driver`, `manager`, `fetchModels`, `settingsGroups` (minus the Experimental group), `maybeMaintainVersions`, `AccountManager` construction. Remove the settings "Experimental" group + `use_java_orchestrator` from the config schema. Remove the dormancy counter from `javaHandle.ts`.
- [ ] **Step 3:** Convert `handle-parity.test.ts` to assert the Java path against the frozen fixture; delete `handle-dormancy.test.ts`.
- [ ] **Step 4:** `npm run build && npx vitest run` → PASS. Grep proves zero refs to `use_java_orchestrator`, `useJavaOrchestrator`, `HUB_ANTIGRAVITY_JAVA_HANDLE`, `__ANTIGRAVITY_JAVA_HANDLE`.
- [ ] **Step 5: Commit** `refactor(antigravity): handle unconditionally delegates to Java; remove flag + TS decision loop`.
- [ ] **Step 6:** Controller re-verifies live (Task 4 smoke) — behavior-neutral.

---

### Task 6: Delete the duplicated TS transform/logic modules

Split into three commits by cluster; after EACH, `npm run build && npx vitest run` green + re-run the live smoke.

**Files (delete or reduce to KEEP funcs per audit Section A):**
- [ ] **6a — transforms:** delete `src/plugin/transform/claude.ts`, `gemini.ts`, `cross-model-sanitizer.ts`, `model-resolver.ts`, `index.ts`; delete their `*.test.ts` (covered by Java tests, Section G). Fix importers to use the Java exports via a small `src/plugin/transform-java.ts` shim if any host code still needs them. Commit.
- [ ] **6b — request/bridge/streaming:** reduce `src/plugin/request.ts` to its KEEP funcs (`getPluginSessionId`, `requestInfoToUrlString`, `isGenerativeLanguageRequest`, `materializeGenerativeLanguageFetchInput`, `PLUGIN_SESSION_ID`) — delete `prepareAntigravityRequest`, `transformAntigravityResponse` bodies (now Java-driven from `javaHandle`/`javaStream`), `generateSyntheticProjectId`, and all internal transform helpers; delete `request-helpers.ts`; reduce `anthropic-bridge.ts` to nothing host-needed (delete if fully superseded); delete `core/streaming/transformer.ts` logic keeping only `createStreamingTransformer` (host) → move it into `javaStream.ts` or keep as the host shell; delete `auth.ts` (use `AntigravityHandleRouting.buildAuth` via export, or keep `parseRefreshParts`/`formatRefreshParts` as a thin re-export if `resolveProjectId`'s host cache still needs them — verify). Convert/delete tests per Section G. Commit.
- [ ] **6c — driver + misc:** delete `src/driver/lanes.ts` (superseded by `AntigravityLanes` via orchestrator); delete `recovery.ts`, `thinking-recovery.ts` (ported in Task 1); delete `fingerprint.ts` logic keeping `generateFingerprint`/`getSessionFingerprint` (host entropy) + route header-building through the Java export; delete `versions.ts` logic keeping `refreshVersions`/`getVersionList` (network) — route `driftVersion`/`pickVersion`/etc. through Java exports; delete `models-fetch.ts:buildAntigravityCatalog` (keep `fetchAvailableModels`); delete quota logic in `accounts-controller.ts` keeping the network/verify/controller glue → route through `AntigravityQuotaParser` exports. Convert/delete tests per Section G. Commit.

**Note:** any ported-but-unexported class needed here (audit H3: `cacheThinkingSignaturesFromResponse`, `versions` helpers, `quota-parser` helpers) must get its `@JSExport` added back in Task 2 — if discovered mid-6, add the export (small Task-2 amendment commit) rather than keeping the TS.

- [ ] After 6c: full `npm run build && npx vitest run` + `cd java && ./gradlew :antigravity-provider:test :antigravity-teavm:generateJavaScript` all green; controller live-smoke green.

---

### Task 7: `project.ts` host-cache + JVM SPI parity check

**Files:** `src/plugin/project.ts`, verify `AntigravityProvider.java`.

- [ ] **Step 1:** Keep `project.ts`'s `loadManagedProject`/`onboardManagedProject` (network) AND its per-refresh-token `projectContextResultCache`/`projectContextPendingCache` (host dedup state, audit H6) — these are I/O + host state, not duplicated logic. Delete only the pure decision bits now owned by `AntigravityProjectContext` (reached via the orchestrator seams). Confirm the `jsLoad`/`jsOnboard` seams in `javaHandle.ts` still feed the host cache.
- [ ] **Step 2:** Confirm `AntigravityProvider.handle` routes through `AntigravityHandleOrchestrator` (audit F — it does) and now uses the real `AntigravityThinkingRecovery` (Task 1). No divergent brain.
- [ ] **Step 3:** `npm run build && npx vitest run` green. Commit `refactor(antigravity): trim project.ts to host I/O; keep dedup cache`.

---

### Task 8: Recipe tail — gradlew +x, submodule reconcile, final review, republish (USER-GATED)

**Files:** `.gitmodules`, `core-auth` submodule pointer, `java/gradlew` mode, `package.json` version.

- [ ] **Step 1:** `git update-index --chmod=+x java/gradlew` (it is committed 100644; CI EACCES otherwise). Commit.
- [ ] **Step 2:** Advance the `core-auth` submodule to the `main` tip; verify strictly-additive: `git -C core-auth merge-base --is-ancestor <old> <new>`. Set `.gitmodules` `[submodule "core-auth"] branch = main`. Commit `chore(antigravity): reconcile core-auth submodule to main`.
- [ ] **Step 3:** Dispatch the final whole-branch code review (most capable model) over the full experimental diff; fix Critical/Important findings in ONE fix wave.
- [ ] **Step 4:** Push `experimental` (auto-merges to `main` via the sync workflow — do NOT ff/force main). Confirm the merge landed.
- [ ] **Step 5 (USER-GATED):** After explicit consent naming antigravity-auth, bump `package.json` to `2.…`→ **1.10.0** per the unified lineage, tag `v1.10.0`, push the tag → `publish.yml` publishes npm `antigravity-auth@1.10.0` + the jar asset on the same release. Verify both landed and versions match.
- [ ] **Step 6:** Controller final live-verify on the published build; update memory ([[teavm-dedup-recipe]], roadmap).

---

## Self-Review

- **Spec coverage:** parity audit → Phase-0 done inline (audit table); production seams → Task 2/3; streaming factory → Task 2/3; thinking-recovery port → Task 1; flag flip+remove → Task 5; TS deletion → Task 6; JVM SPI → Task 1/7; live gate → Task 4; recipe tail → Task 8. All spec sections covered.
- **Type consistency:** seam functor names (`JsHasherFn`, `JsCacheLookupFn`, `JsSignatureStoreFns`, `JsIdsFns`) used consistently across Task 2 (define) and Task 3 (call); `prepareAntigravityRequestProd`, `newStreamMapper`, `cacheSignaturesFromResponse` named identically where produced/consumed.
- **No placeholders:** the ports (Task 1) and deletions (Task 6) reference the exact audit dispositions + fixture-freeze tests; glue code (handle delegate, seam wiring) is given concretely.
- **Ordering:** ports before exports before rewire before live-gate before deletion — deletion never precedes a green live verification.
