package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsModule;
import io.github.intisy.ai.tsemit.TsNullable;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * antigravity-auth's JavaScript module surface, typed for a TypeScript consumer.
 *
 * @implNote Declares the shape {@code AntigravityProviderJs} actually exports; it is never
 * implemented, only emitted. The export class speaks JSPromise, JSString and eleven JSO functors,
 * none of which mean anything to a TypeScript caller, so every value crosses as JSON text.
 *
 * <p>{@code AntigravityProviderJs} carries 61 exports and this declares 16 of them, which is a
 * decision rather than an omission. The other 45 exist to prove the provider core transpiles, are
 * driven only from the Java-side harness, and have no TypeScript caller; declaring them would claim
 * a consumer surface nobody consumes and would then have to be kept in step with a proof.
 *
 * <p>A functor the standard {@code java.util.function} types can say is written with one of them;
 * the seven that reach past what those can say are declared beside this module and annotated
 * {@code TsFn}, which emits an interface as a TypeScript function type. Those seven were ten
 * {@code TsRaw} escapes until bayonet 1.8.0 grew the annotation.
 */
@TsModule
public interface AntigravityProviderSurface {

    /**
     * The per-family quota aggregate the account view shows, from what the upstream reported per
     * model.
     *
     * @param modelsJson the upstream model list, as a JSON object keyed by model id
     * @return the worst remaining fraction and earliest reset per family, as JSON
     */
    String aggregateQuota(String modelsJson);

    /**
     * When one account can next serve.
     *
     * @param accountJson the account, as JSON
     * @param now the current time, in epoch milliseconds
     * @return the epoch-millisecond time, or infinity for an account out of rotation
     */
    double antigravityAvailableAt(String accountJson, double now);

    /**
     * The quota bars one account's cached quota renders as.
     *
     * @param accountJson the account, as JSON
     * @return the bars as a JSON array, or null when the account has no cached quota
     */
    @TsNullable(asNull = true)
    String antigravityQuota(String accountJson);

    /**
     * What the account view shows as one account's state.
     *
     * @param accountJson the account, as JSON
     * @param now the current time, in epoch milliseconds
     * @return the status word
     */
    String antigravityStatus(String accountJson, double now);

    /**
     * The model catalog this provider offers, from what the upstream lists.
     *
     * @param payloadJson the upstream model payload, as JSON
     * @return the catalog, as JSON
     */
    String buildCatalog(String payloadJson);

    /**
     * The platform enum the upstream project endpoints validate their request metadata against.
     *
     * @param platform the host platform name, as the runtime reports it
     * @param arch the host architecture, as the runtime reports it
     * @return the enum value, or the unspecified one for a platform the upstream does not name
     */
    String detectCodeAssistPlatform(String platform, String arch);

    /**
     * Which accounts are due a User-Agent version drift, and what to write on each.
     *
     * @param accountsJson the accounts to consider, as a JSON array
     * @param now the current time, in epoch milliseconds
     * @param versionListJson the versions to pick from, as a JSON array
     * @param jsRandom the host's entropy, so an account's drift is not baked into the bundle
     * @return the mutations in order, as a JSON array, for the host to apply
     */
    String driftAccountVersionsProd(String accountsJson, double now, String versionListJson,
                                    Supplier<Double> jsRandom);

    /**
     * A project id for an account that has no discovered managed project.
     *
     * @param jsRandom the host's entropy
     * @param jsUuid the host's id minting
     * @return the synthetic project id
     */
    String generateSyntheticProjectIdProd(Supplier<Double> jsRandom, Supplier<String> jsUuid);

    /**
     * Runs the whole attempt loop: resolve the model, walk the accounts, discover project context,
     * classify each outcome, and decide what the host serves.
     *
     * @param inputsJson the request's url, method, headers, body and model, as a JSON object
     * @param configJson the platform, architecture and optional fixed clock, as a JSON object
     * @param jsExec the host's transport, taking an account id and a prepared request and answering
     *               with the attempt's outcome as JSON
     * @param jsAcquire the host's account rotation, taking a lane and answering with the account it
     *                  picked as JSON, or null when none is free
     * @param jsAccountOps what the loop tells the host about each account it used
     * @param jsProjectLoader the host's managed-project fetch
     * @param jsProjectOnboarder the host's managed-project onboarding
     * @param jsPreparer the host's request preparation, answering with an opaque reference plus the
     *                   transform parameters as JSON, or null when preparing threw
     * @param autoCandidatesJson the leaderboard's ranking, which stays the host's, as a JSON array
     * @param jsRandom the host's entropy
     * @param jsUuid the host's id minting
     * @return the decision as JSON: which attempt to serve, or a synthetic response to answer with
     */
    CompletionStage<String> handleAntigravityRequestAsync(
            String inputsJson,
            String configJson,
            BiFunction<String, String, CompletionStage<String>> jsExec,
            AntigravityAcquireFn jsAcquire,
            AntigravityAccountOpsShape jsAccountOps,
            AntigravityProjectLoadFn jsProjectLoader,
            AntigravityProjectOnboardFn jsProjectOnboarder,
            AntigravityPrepareFn jsPreparer,
            String autoCandidatesJson,
            Supplier<Double> jsRandom,
            Supplier<String> jsUuid);

    /**
     * Opens one upstream stream's decode into canonical IR events.
     *
     * @param model the model to stamp on every event
     * @param jsIds the host's id minting
     * @return the handle to feed the stream's chunks through
     */
    AntigravityIrStreamMapperHandle newIrStreamMapper(String model, AntigravityStreamIdsShape jsIds);

    /**
     * Opens one streamed response's transform.
     *
     * @param signatureSessionKey the key thinking signatures are stored under for this session
     * @param debugText the thinking placeholder to inject, or empty for none
     * @param cacheSignatures whether this model's thinking signatures are worth storing
     * @param jsSignatureStore the host's signature store
     * @param jsCacheSignature the host's on-disk signature-cache write
     * @param jsImageSink the host's image writer, answering with a markdown link or null
     * @param jsThoughtDedup the host's already-shown thought hashes, or null for a model that does
     *                       not reconnect mid-thought
     * @return the handle to feed the response's lines through
     */
    AntigravitySseTransformHandle newResponseSseTransformer(
            String signatureSessionKey,
            String debugText,
            boolean cacheSignatures,
            AntigravitySignatureStoreShape jsSignatureStore,
            AntigravityCacheSignatureFn jsCacheSignature,
            AntigravityImageSinkFn jsImageSink,
            @TsNullable AntigravityThoughtDedupShape jsThoughtDedup);

    /**
     * The client version a new account presents, weighted toward the newest.
     *
     * @param versionListJson the versions to pick from, as a JSON array
     * @param min the lowest version worth picking, or empty for no floor
     * @param jsRandom the host's entropy
     * @return the chosen version
     */
    String pickVersionProd(String versionListJson, String min, Supplier<Double> jsRandom);

    /**
     * One request, prepared for one account and endpoint: url, headers, body, and what the response
     * transform will need.
     *
     * @param url the request url
     * @param method the request method
     * @param headersJson the caller's headers, as a JSON object
     * @param body the request body
     * @param accessToken the account's access token
     * @param projectId the project the request is billed to
     * @param headerStyle which header set this endpoint expects
     * @param fingerprintJson the account's device fingerprint, as JSON, or the JSON null
     * @param keepThinking whether thinking blocks stay in the request
     * @param pluginSessionId the host's session id, which keys the signature cache
     * @param endpointOverride the endpoint this attempt must use, or empty for the default
     * @param claudeToolHardening whether Claude tool schemas are hardened
     * @param claudePromptAutoCaching whether prompt caching markers are added
     * @param cliFirst whether the free CLI quota lane is tried first
     * @param jsRandom the host's entropy
     * @param jsUuid the host's id minting
     * @param jsHasher the host's sha256, returning full hex
     * @param jsCacheLookup the host's signature-cache read
     * @param jsSignatureStore the host's signature store
     * @return the prepared request as JSON: url, headers, body, and the transform parameters
     */
    String prepareAntigravityRequestProd(
            String url, String method, String headersJson, String body,
            String accessToken, String projectId, String headerStyle, String fingerprintJson,
            boolean keepThinking, String pluginSessionId, String endpointOverride,
            boolean claudeToolHardening, boolean claudePromptAutoCaching, boolean cliFirst,
            Supplier<Double> jsRandom, Supplier<String> jsUuid,
            Function<String, String> jsHasher,
            AntigravityCacheLookupFn jsCacheLookup,
            AntigravitySignatureStoreShape jsSignatureStore);

    /**
     * The project id one account's requests are billed to, discovering and minting one where the
     * account has none yet.
     *
     * @param accountJson the account, as JSON
     * @param access the account's access token
     * @param jsRandom the host's entropy
     * @param jsUuid the host's id minting
     * @param jsProjectLoader the host's managed-project fetch
     * @param jsProjectOnboarder the host's managed-project onboarding
     * @param configJson the platform and architecture, as a JSON object
     * @return the id and what to persist on the account, as JSON
     */
    CompletionStage<String> resolveProjectIdProd(
            String accountJson,
            String access,
            Supplier<Double> jsRandom,
            Supplier<String> jsUuid,
            AntigravityProjectLoadFn jsProjectLoader,
            AntigravityProjectOnboardFn jsProjectOnboarder,
            String configJson);

    /**
     * The already-decoded IR request, with this provider's thinking budget resolved, encoded for
     * the upstream.
     *
     * @param irJson the decoded IR request, as JSON
     * @param model the model the request will be served as
     * @return the upstream request body, as JSON
     */
    String resolveThinkingBudgetAndEncodeGemini(String irJson, String model);

    /**
     * The buffered half of the served response's transform.
     *
     * @param bodyText the upstream body
     * @param status the upstream status
     * @param headersJson the upstream headers, as a JSON object
     * @param requestedModel the model the caller asked for, or empty
     * @param debugText the thinking placeholder to inject, or empty for none
     * @param jsImageSink the host's image writer, answering with a markdown link or null
     * @return the status, headers and body to answer with, as JSON
     */
    String transformServeBodyProd(
            String bodyText, int status, String headersJson, String requestedModel,
            String debugText,
            AntigravityImageSinkFn jsImageSink);
}
