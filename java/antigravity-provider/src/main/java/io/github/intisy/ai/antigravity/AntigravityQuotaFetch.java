package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.QuotaBar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed {@link AccountQuota} producer backing {@link AntigravityProvider#quota}. Reuses {@link
 * AntigravityUpstream#fetchAvailableModels} for the transport (the SAME upstream call {@link
 * AntigravityModelsFetch} makes) and {@link AntigravityQuotaParser} for the pure
 * aggregation/status logic; this class is only the per-account iterate/persist/assemble glue.
 *
 * <p>Unlike {@link AntigravityModelsFetch} (single first-enabled-account discovery), this fetches
 * quota for EVERY enabled account and persists each one's aggregated per-family quota to {@code
 * meta.cachedQuota} so the dashboard has a durable cache even between live fetches. Never throws:
 * a failure fetching one account's quota folds into an {@link AccountQuota} with an {@code "error"}
 * status and NO bars for that account only -- it never drops the account or aborts the whole
 * response ({@link AccountQuota} is exactly the shape that lets a bar-less/errored account still
 * be represented, per the core-proxy SPI's design).
 */
final class AntigravityQuotaFetch {

    private AntigravityQuotaFetch() {
    }

    static List<AccountQuota> quota(AntigravityBackend backend) {
        try {
            return doQuota(backend);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    private static List<AccountQuota> doQuota(AntigravityBackend backend) {
        List<AccountQuota> out = new ArrayList<>();
        for (Account account : backend.accountStore.list(AntigravityBackend.PROVIDER_ID)) {
            if (account.enabled == Boolean.FALSE) continue;
            out.add(entryFor(backend, account));
        }
        return out;
    }

    // Never rethrows -- any failure for THIS account (refresh, network, non-2xx, malformed body)
    // folds into an error AccountQuota so one bad account never breaks the whole quota() result.
    private static AccountQuota entryFor(AntigravityBackend backend, Account account) {
        try {
            return doEntryFor(backend, account);
        } catch (Throwable e) {
            return errorEntry(account);
        }
    }

    @SuppressWarnings("unchecked")
    private static AccountQuota doEntryFor(AntigravityBackend backend, Account account) {
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

        String status = AntigravityQuotaParser.antigravityStatus(statusInput(account, perFamily), System.currentTimeMillis());
        return new AccountQuota(account.id, account.email, status, barsFor(perFamily));
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

    // {family -> {remainingFraction, resetTime}} -> one QuotaBar per family; resetTime is kept as
    // the RAW upstream string (QuotaBar.resetTime is a String, not an epoch-ms Long) so no
    // precision/format is invented here that the wire shape didn't already have.
    private static List<QuotaBar> barsFor(Map<String, Object> perFamily) {
        if (perFamily == null || perFamily.isEmpty()) return Collections.emptyList();
        List<QuotaBar> bars = new ArrayList<>();
        for (Map.Entry<String, Object> e : perFamily.entrySet()) {
            Object famObj = e.getValue();
            Map<?, ?> fam = famObj instanceof Map ? (Map<?, ?>) famObj : null;
            Object remainingFraction = fam != null ? fam.get("remainingFraction") : null;
            Object resetTime = fam != null ? fam.get("resetTime") : null;
            double fraction = remainingFraction instanceof Number ? ((Number) remainingFraction).doubleValue() : 0;
            bars.add(new QuotaBar(e.getKey(), fraction, resetTime != null ? String.valueOf(resetTime) : null));
        }
        return bars;
    }

    private static AccountQuota errorEntry(Account account) {
        return new AccountQuota(account.id, account.email, "error", Collections.<QuotaBar>emptyList());
    }
}
