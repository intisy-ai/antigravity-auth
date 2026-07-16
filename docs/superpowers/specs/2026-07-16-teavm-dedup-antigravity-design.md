# Antigravity-auth TeaVM De-dup Design

**Date:** 2026-07-16
**Provider:** antigravity-auth (branch `experimental`)
**Recipe:** the proven per-provider TeaVM de-dup (stub-auth v1.4.0, claude-code-auth v2.3.0 done before this)

## Goal

Make Java the single source of truth for antigravity-auth's request/response/transform logic:
delete the duplicated TypeScript so no piece of logic exists in both Java and TS, keep it fully
usable from both the JS plugin (TeaVM→ESM) and the JVM jar, with no performance loss (streaming
stays incremental). Republish on the unified `v*` lineage as **v1.10.0** (npm + jar).

## Why this one is "the big one"

claude-code-auth was native Anthropic passthrough — the only duplicated thing was the decision
loop, which the Java orchestrator already owned. antigravity is the opposite: **~8,810 lines of
transform/request/bridge logic live in BOTH Java and TS**, and the currently-wired Java orchestrator
delegates all of it back to TS via seams (`javaHandle.ts` calls `prepareAntigravityRequest`,
`transformAntigravityResponse`, `anthropicToGemini`, `geminiToAnthropicStream`, the project loaders).
So the code exists twice. This de-dup moves the transform boundary into Java and deletes the TS copy.

### What already exists (the head start)

- **33 Java classes** in `java/antigravity-provider/` covering the whole pipeline: `AntigravityRequestPrep`,
  `AntigravityResponseTransform`, `GeminiTransforms`, `ClaudeTransforms`, `CrossModelSanitizer`,
  `AntigravityModelResolver`, `AntigravityFormatBridge`, `AntigravityStreamMapper`,
  `AntigravityStreamTransform`, `AntigravitySchemaCleaner`, `AntigravityThinkingBlocks/Config`,
  `AntigravityToolPairing`, `AntigravityResponseParse`, `AntigravityProjectContext`,
  `AntigravityModelsFetch/Catalog/Upstream`, `AntigravityVersions`, `AntigravityFingerprint`,
  `AntigravityAuth`, `AntigravityLanes`, `AntigravityRequestKeys/Signatures`, plus
  `AntigravityHandleOrchestrator` + `AntigravityHandleRouting`.
- **The TeaVM export surface** (`AntigravityProviderJs`) already `@JSExport`s all of them, and
  `generateJavaScript` is green (`src/generated/antigravity-orchestrator.teavm.js` exists).
- **`AntigravityStreamMapper` is already a chunk-wise state machine**: `handle(geminiObj) →
  List<String>` (SSE strings) + `finish() → List<String>`; its javadoc states it is built to be
  driven by a TS `new TransformStream({transform, flush})` shell. The hardest part of the de-dup
  (streaming fidelity with no perf loss) is therefore already solved on the Java side.
- **`javaHandle.ts`** — the flag-gated delegation shell — is fully written and wires the orchestrator
  decision loop; it just still calls the TS transforms as "host I/O".
- **The flag** `use_java_orchestrator` (+ env `HUB_ANTIGRAVITY_JAVA_HANDLE`) and the
  `handle-parity.test.ts` / `handle-dormancy.test.ts` tests exist, mirroring claude.

### The catch (the real work)

The transform exports in `AntigravityProviderJs` are **transpilability proofs wired with
deterministic stub seams** — `FIXED_RANDOM=0.5`, `counterIds`, `STUB_HASHER`, `NO_CACHE`,
`NOOP_LOGGER`, identity JSON parser, a no-op signature store, a no-op thinking-recovery, and
`keepThinking=false` hardcoded. They prove the code compiles under TeaVM; they do NOT produce
production-parity output. Making the TS delegate to them requires **production export variants that
accept the real host seams from JS** (the same pattern the async orchestrator entry already uses for
`jsRandom`/`jsUuid`), plus a chunk-wise stream-mapper factory export for the streaming wrapper.

## The de-dup boundary (the principle)

Same rule accepted for stub and claude: **logic lives once, in Java; the host keeps only I/O that is
platform-specific by nature and therefore not "the same code twice".** After this de-dup, the TS that
remains is exclusively:

- `fetch` + IP-proxy transport (`jsExec`), already in `javaHandle.ts`.
- `AccountManager` / `proxyManager` glue (`jsAcquire`, `jsAccountOps`), already there.
- The **signature store disk I/O** and **signature cache storage** (read/write) — the *logic* (key
  derivation, what to cache) is Java (`AntigravityRequestKeys`/`AntigravityRequestSignatures`); only
  the persistence is a TS seam.
- The **thin `TransformStream` wrapper** that feeds upstream SSE chunks to the Java stream mapper and
  enqueues its returned SSE strings — Web-Streams plumbing, not mapping logic.
- Config file reads, the dynamic-import loader, the `Request`/`Response` construction, and the
  `chatError` + `Date.toLocaleString` formatting already host-side in `materializeDecision`.

Everything else in `src/plugin/` and `src/driver/lanes.ts` is duplicated logic and gets deleted.

## Streaming boundary decision (chosen)

**Thin TS stream wrapper over the Java `AntigravityStreamMapper`.** A small TS `TransformStream`
parses upstream SSE into Gemini objects, calls the Java mapper's `handle(obj)` per object and
enqueues each returned SSE string, and calls `finish()` in `flush`. Java owns 100% of the mapping
logic; TS keeps only the Web-Streams plumbing. No buffering, no latency regression, no duplicated
logic. (Rejected: buffering the whole stream — a real perf regression on long generations; leaving
the mapper in TS — leaves duplication.)

## Parity audit disposition (Phase 0 — determines the exact task list)

Before any deletion, every file in `src/plugin/` and `src/driver/` is classified as exactly one of:

- **DELETE** — logic duplicated in Java AND already TeaVM-exported with a production-seam variant
  wired. The bulk: `transform/*`, the logic bodies of `request.ts`, `anthropic-bridge.ts`,
  `models-fetch.ts`, `versions.ts`, `fingerprint.ts`, `auth.ts`, `lanes.ts`.
- **PORT-THEN-DELETE** — pure logic that currently exists ONLY in TS and is injected as a seam (the
  audit must confirm which; the prime suspect is `thinking-recovery.ts`, injected as the orchestrator's
  `ThinkingRecovery` seam and with no `AntigravityThinkingRecovery.java` in the tree). Port to Java,
  add the export, then delete the TS.
- **KEEP** — genuine host I/O with no logic to duplicate (transport, account glue, signature-store
  disk I/O, the stream wrapper, config reads, the loader). Stays TS.

The audit output is a per-file table with the disposition and, for DELETE/PORT items, the exact Java
class + export function that supersedes it. This table IS the plan's task list.

## Component plan

1. **Promote the stub-seamed transform exports to production exports.** For each export the TS will
   call (`prepareAntigravityRequest`, `transformAntigravityResponse` [or its pieces], `anthropicToGemini`,
   the stream-mapper factory), add a variant taking the real seams as `@JSFunctor` params from JS:
   entropy (`jsRandom`/`jsUuid` — already proven), the conversation-key `hasher`, the signature
   `cachedSignatureLookup` + `signatureStore`, `keepThinking` (from config), the `logger`, and the
   `imageSink`. Model on `handleAntigravityRequestAsync`'s existing real-seam injection.
2. **Add the chunk-wise stream-mapper factory export.** `newStreamMapper(model, jsIds) → { handle(objJson)
   → string[], finish() → string[] }` (a `@JSExport` returning a `JSObject` with two methods), so the
   TS `TransformStream` can drive it incrementally. Ids minted host-side (`crypto.randomUUID`).
3. **Rewire `javaHandle.ts` seams** to call the Java exports instead of `../plugin/*`: `jsPreparer` →
   Java `prepareAntigravityRequest`; `materializeDecision`'s `SERVE` transform → Java
   `transformAntigravityResponse` + the stream wrapper; the anthropic bridge's `anthropicToGemini` +
   `geminiToAnthropicStream` → Java. Keep the dynamic `import()` lazy (no startup TeaVM eval).
4. **Flip the flag on by default, verify live, then delete the flag** (as in claude): `handle`
   becomes an unconditional lazy delegate to `handleViaJavaOrchestrator`; remove `useJavaOrchestrator`,
   `DEFAULT_USE_JAVA_ORCHESTRATOR`, the `HUB_ANTIGRAVITY_JAVA_HANDLE` read, the settings "Experimental"
   group, the dormancy counter, and the whole TS decision path in `index.ts` (the `attemptModel`
   loop, `handleAnthropicMessages`, `resolveEffortVariant`, etc. — whatever the audit marks DELETE).
5. **Delete the duplicated TS** per the audit; convert `handle-parity.test.ts` to a Java-path
   regression frozen against a committed fixture (capture N scenarios from the current Java path, then
   assert against them); delete `handle-dormancy.test.ts`; convert each deleted module's unit tests to
   fixture regressions over the Java export (or delete if the Java class already has equivalent
   coverage in `java/.../src/test`).
6. **JVM SPI parity:** confirm `AntigravityProvider.handle` (the jar's `Provider` impl) routes through
   `AntigravityHandleOrchestrator` — not a divergent path — so the jar and the plugin share one brain.

## Verification strategy

- **Live gate (before any deletion):** host-side real-account smoke driving the built
  `dist/handler.js` through the real config system (`HUB_CONFIG_DIR` temp dir) with the flag ON —
  covering a non-streaming Gemini request, a streaming request (assert SSE event sequence + that it
  streams incrementally, not buffered), the Anthropic-messages bridge (`/v1/messages`), an Auto
  request, and no-account (503). Never print tokens. Only after this is green do we delete.
- **Fixture regressions:** the parity test freezes the Java-path output for the scenario set;
  transform-level tests freeze Java-export output against captured TS output for representative
  payloads (streaming SSE, tool pairing, thinking blocks, schema cleaning).
- **Re-verify live** after deletion (same smoke) — the deletion must be behavior-neutral.
- `npm run build && npx vitest run` fully green at each task; the Java suites
  (`./gradlew :antigravity-provider:test`) green after any Java change.

## Recipe tail (carry-overs)

- **`gradlew` +x:** `java/gradlew` is committed mode `100644` — fix with
  `git update-index --chmod=+x java/gradlew` or CI `npm run build` fails EACCES.
- **UTF-8:** already present in `java/build.gradle` (`options.encoding='UTF-8'`) ✓ — antigravity is
  text-heavy so this matters; leave it.
- **Sync workflow:** antigravity uses `sync-experimental-to-main.yml` — PUSH `experimental`
  (auto-merges to `main`); do NOT ff/force `main`.
- **core-auth submodule:** reconcile to the `main` tip (verify strictly-additive with `merge-base
  --is-ancestor`) + `.gitmodules branch=main` (currently unset for core-auth).
- **Publish:** unified `v*` `publish.yml` publishes npm + jar from one tag. Republish **v1.10.0**.
  **Per-repo public publish requires explicit user consent naming antigravity-auth** — do not tag
  until given.
- **OAuth secret:** `ANTIGRAVITY_CLIENT_SECRET` from env ONLY; never bake it. `client_id` public
  literal is fine. Never log refresh/access tokens.

## Risks

- **Production seam wiring is the delicate part.** The stub exports pass; the *seams* (hasher, signature
  cache, thinking recovery, keepThinking, entropy) are what determine parity. The live gate + fixture
  freeze must exercise them before deletion — a passing transpile proves nothing about output parity.
- **`thinking-recovery.ts` may need a full Java port** (if the audit confirms it's TS-only logic), which
  is the largest single new-Java item. If porting proves high-risk, the fallback that still satisfies
  "no duplication" is to keep it as a single-sourced TS seam (it exists once, injected into Java) — a
  decision to make explicit during the audit, not silently.
- **Streaming fidelity:** the SSE byte sequence (event order, `signature_delta`, index as integer,
  key order) must match; the fixture freeze on a captured stream is the guard.
- **Size:** ~8,810 LOC deletion surface + production-export wiring + fixture capture ⇒ multi-session.

## Out of scope

- The provider-management UI features 3–4 (config editor, OAuth login) and the all-apps proxy-mgmt UI
  (separate roadmap sub-projects).
- Any behavior change: this is a pure de-dup; output must be byte-identical to today's TS path.
- stub-auth's build.gradle CI gap (already published 1.0.0; unrelated).
