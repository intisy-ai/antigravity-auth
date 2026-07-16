package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quota-display Task 2: {@code GET /v1/quota} per-account quota fetch for the example-server
 * dashboard, mirroring claude-code-auth's {@code ClaudeUsageFetch}. Reuses {@link
 * AntigravityUpstream#fetchAvailableModels} for the transport (the SAME upstream call
 * {@link AntigravityModelsFetch} makes) and {@link AntigravityQuotaParser} for the pure
 * aggregation/status logic -- this class is only the per-account iterate/persist/assemble glue.
 *
 * <p>Unlike {@link AntigravityModelsFetch} (single first-enabled-account discovery), this fetches
 * quota for EVERY enabled account and persists each one's aggregated per-family quota to {@code
 * meta.cachedQuota} so the dashboard has a durable cache even between live fetches. Never throws:
 * a failure fetching one account's quota folds into an {@code error} entry for that account only
 * -- it never aborts the whole response.
 */
final class AntigravityQuotaFetch {

    private AntigravityQuotaFetch() {
    }

    static HttpResponse fetch(AntigravityBackend backend, HandlerCtx ctx) {
        try {
            return doFetch(backend);
        } catch (Throwable e) {
            // Never throw out of a provider handle() path -- any unexpected failure folds into
            // the same api_error shape an upstream failure would. Never include e.getMessage()
            // here: it could echo back a header/token fragment from a lower-level failure.
            return AntigravityProvider.errorResponse(502, "api_error", "quota fetch failed");
        }
    }

    private static HttpResponse doFetch(AntigravityBackend backend) {
        List<Object> entries = new ArrayList<>();
        for (Account account : backend.accountStore.list(AntigravityBackend.PROVIDER_ID)) {
            if (account.enabled == Boolean.FALSE) continue;
            entries.add(entryFor(backend, account));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accounts", entries);

        HttpResponse out = new HttpResponse();
        out.status = 200;
        out.headers = new LinkedHashMap<>();
        out.headers.put("content-type", "application/json");
        out.body = backend.json.stringify(body);
        return out;
    }

    // Never rethrows -- any failure for THIS account (refresh, network, non-2xx, malformed body)
    // folds into an error entry so one bad account never breaks the whole /v1/quota response.
    private static Map<String, Object> entryFor(AntigravityBackend backend, Account account) {
        try {
            return doEntryFor(backend, account);
        } catch (Throwable e) {
            return errorEntry(account);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> doEntryFor(AntigravityBackend backend, Account account) {
        String access;
        try {
            access = backend.accounts.ensureAccess(account.id);
        } catch (RuntimeException e) {
            return errorEntry(account);
        }
        if (access == null || access.trim().isEmpty()) {
            return errorEntry(account);
        }

        Map<String, Object> payload = AntigravityUpstream.fetchAvailableModels(backend, access, account);
        if (payload == null) {
            return errorEntry(account);
        }

        Object modelsObj = payload.get("models");
        Map<String, Object> models = modelsObj instanceof Map ? (Map<String, Object>) modelsObj : null;
        Map<String, Object> perFamily = AntigravityQuotaParser.aggregateQuotaFamilies(models);

        persistCachedQuota(backend, account.id, perFamily);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", account.id);
        if (account.email != null) entry.put("email", account.email);
        entry.put("status", AntigravityQuotaParser.antigravityStatus(statusInput(account, perFamily), System.currentTimeMillis()));
        entry.put("quota", buildQuotaList(perFamily));
        return entry;
    }

    // The plain-Map shape AntigravityQuotaParser.antigravityStatus expects, built from the just
    // (re)aggregated perFamily -- NOT the account's own (possibly stale) meta.cachedQuota, so the
    // status reflects this live fetch rather than whatever was persisted before it.
    private static Map<String, Object> statusInput(Account account, Map<String, Object> perFamily) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("enabled", account.enabled);
        input.put("coolingDownUntil", account.coolingDownUntil);
        input.put("rateLimitResetTimes", account.rateLimitResetTimes);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cachedQuota", perFamily);
        input.put("meta", meta);
        return input;
    }

    private static void persistCachedQuota(AntigravityBackend backend, String accountId, Map<String, Object> perFamily) {
        backend.accounts.mutate(accountId, acc -> {
            if (acc.meta == null) acc.meta = new LinkedHashMap<>();
            acc.meta.put("cachedQuota", perFamily);
        });
    }

    // {family -> {remainingFraction, resetTime}} -> [{label, remainingFraction, resetTime}], with
    // resetTime normalized from its raw ISO string to an epoch-ms Long (or null when unparsable) --
    // the display shape the dashboard consumes. null when there is no aggregated quota at all.
    private static List<Map<String, Object>> buildQuotaList(Map<String, Object> perFamily) {
        if (perFamily == null) return null;
        List<Map<String, Object>> quota = new ArrayList<>();
        for (Map.Entry<String, Object> e : perFamily.entrySet()) {
            Object famObj = e.getValue();
            Map<?, ?> fam = famObj instanceof Map ? (Map<?, ?>) famObj : null;
            Object remainingFraction = fam != null ? fam.get("remainingFraction") : null;
            Object resetTime = fam != null ? fam.get("resetTime") : null;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", e.getKey());
            entry.put("remainingFraction", remainingFraction instanceof Number ? remainingFraction : null);
            entry.put("resetTime", normalizeResetTime(resetTime));
            quota.add(entry);
        }
        return quota;
    }

    private static Long normalizeResetTime(Object resetTime) {
        if (resetTime == null) return null;
        double epochMs = AntigravityQuotaParser.parseDateToEpochMillis(String.valueOf(resetTime));
        return Double.isNaN(epochMs) ? null : (long) epochMs;
    }

    private static Map<String, Object> errorEntry(Account account) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", account.id);
        if (account.email != null) entry.put("email", account.email);
        entry.put("status", "error");
        entry.put("quota", null);
        return entry;
    }
}
