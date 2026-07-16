package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model-map Task 2: {@code GET /v1/models} discovery fetch for the example-server dashboard,
 * mirroring claude-code-auth's {@code ClaudeModelsFetch}. Java port of the HOST-I/O half of
 * antigravity-auth's TS {@code fetchAvailableModels} (see {@code src/plugin/models-fetch.ts}) --
 * the MAPPING half is already ported as {@link AntigravityCatalog#buildAntigravityCatalog}, which
 * this class feeds with the raw upstream (already-parsed) payload.
 *
 * <p>Deliberately uses {@link io.github.intisy.ai.shared.manager.AccountManager#ensureAccess} (no
 * rotation/lane-claim side effects) and does NO project discovery/onboarding -- a discovery call
 * only reads whatever project id an account already has cached. Never throws: every failure path
 * (no account, refresh failure, network failure, non-2xx on every endpoint, an unparsable
 * upstream body) folds into the same synthetic error shape the messages path already uses.
 */
final class AntigravityModelsFetch {

    // Distinct wording from the orchestrator's own no-account message (this is a discovery call,
    // not a chat turn) but the same synthetic invalid_request_error/x-hub-chat-error shape.
    private static final String NO_ACCOUNT_MESSAGE = "No enabled antigravity account — seed one first.";

    private AntigravityModelsFetch() {
    }

    static HttpResponse fetch(AntigravityBackend backend, HandlerCtx ctx) {
        try {
            return doFetch(backend);
        } catch (Throwable e) {
            // Never throw out of a provider handle() path -- fold any unexpected failure into the
            // same api_error shape an upstream failure would take. No token/message detail here.
            return AntigravityProvider.errorResponse(502, "api_error", "models fetch failed");
        }
    }

    private static HttpResponse doFetch(AntigravityBackend backend) {
        Account account = firstEnabledAccount(backend);
        if (account == null) {
            return noAccountError();
        }

        String access;
        try {
            access = backend.accounts.ensureAccess(account.id);
        } catch (RuntimeException e) {
            // Never log the token/refresh error detail -- just the fact that refresh failed.
            return AntigravityProvider.errorResponse(502, "api_error", "token refresh failed");
        }
        if (access == null || access.trim().isEmpty()) {
            return AntigravityProvider.errorResponse(502, "api_error", "token refresh failed");
        }

        // A light read only: whatever project id an account already has cached (managedProjectId
        // preferred, then the raw projectId). No project discovery/onboarding runs on this path --
        // when neither is present the request body simply omits "project" (matches the TS
        // `projectId ? {project} : {}` guard). Shared with AntigravityQuotaFetch via
        // AntigravityUpstream instead of two separate copies of this transport.
        Map<String, Object> payload = AntigravityUpstream.fetchAvailableModels(backend, access, account);
        if (payload == null) {
            return AntigravityProvider.errorResponse(502, "api_error", "fetchAvailableModels failed");
        }

        Map<String, Object> catalog = AntigravityCatalog.buildAntigravityCatalog(payload);

        HttpResponse out = new HttpResponse();
        out.status = 200;
        out.headers = new LinkedHashMap<>();
        out.headers.put("content-type", "application/json");
        out.body = backend.json.stringify(catalog);
        return out;
    }

    private static Account firstEnabledAccount(AntigravityBackend backend) {
        List<Account> accounts = backend.accountStore.list(AntigravityBackend.PROVIDER_ID);
        for (Account a : accounts) {
            if (a.enabled != Boolean.FALSE) {
                return a;
            }
        }
        return null;
    }

    private static HttpResponse noAccountError() {
        HttpResponse response = AntigravityProvider.errorResponse(400, "invalid_request_error", NO_ACCOUNT_MESSAGE);
        response.headers.put("x-hub-chat-error", "1");
        return response;
    }
}
