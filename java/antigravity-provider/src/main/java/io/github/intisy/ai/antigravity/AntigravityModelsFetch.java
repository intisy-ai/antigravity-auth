package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.ModelInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Model-map Task 2 / SP-E E-D: typed {@link ModelInfo} producer backing {@link
 * AntigravityProvider#models}, mirroring claude-code-auth's {@code ClaudeModelsFetch}. Java port of
 * the HOST-I/O half of antigravity-auth's TS {@code fetchAvailableModels} (see {@code
 * src/plugin/models-fetch.ts}) -- the MAPPING half is already ported as {@link
 * AntigravityCatalog#buildAntigravityCatalog}, which this class feeds with the raw upstream
 * (already-parsed) payload and then flattens into {@link ModelInfo}s.
 *
 * <p>Deliberately uses {@link io.github.intisy.ai.shared.manager.AccountManager#ensureAccess} (no
 * rotation/lane-claim side effects) and does NO project discovery/onboarding -- a discovery call
 * only reads whatever project id an account already has cached. Never throws: every failure path
 * (no account, refresh failure, network failure, non-2xx on every endpoint, an unparsable upstream
 * body) folds into an empty list -- {@link ModelCatalogProvider} has no HTTP envelope to carry an
 * error shape in.
 *
 * <p>{@link ModelInfo} carries only {@code id}/{@code name}/{@code context}/{@code output}: the
 * catalog's {@code modalities}/{@code variants}/{@code group} fields have no POJO home in the
 * typed SPI (disclosed gap, see the migration report) and are dropped here.
 */
final class AntigravityModelsFetch {

    private AntigravityModelsFetch() {
    }

    static List<ModelInfo> models(AntigravityBackend backend) {
        try {
            return doModels(backend);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModelInfo> doModels(AntigravityBackend backend) {
        Account account = firstEnabledAccount(backend);
        if (account == null) {
            return Collections.emptyList();
        }

        String access;
        try {
            access = backend.accounts.ensureAccess(account.id);
        } catch (RuntimeException e) {
            // Never log the token/refresh error detail here.
            return Collections.emptyList();
        }
        if (access == null || access.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // A light read only: whatever project id an account already has cached (managedProjectId
        // preferred, then the raw projectId). No project discovery/onboarding runs on this path.
        Map<String, Object> payload = AntigravityUpstream.fetchAvailableModels(backend, access, account);
        if (payload == null) {
            return Collections.emptyList();
        }

        Map<String, Object> catalog = AntigravityCatalog.buildAntigravityCatalog(payload);
        Object modelsObj = catalog.get("models");
        if (!(modelsObj instanceof Map)) {
            return Collections.emptyList();
        }

        // LinkedHashMap iteration order (AntigravityCatalog.buildAntigravityCatalog): the fixed
        // Auto entry, then the ranked agent models in ranking order, then the Gemini CLI free
        // pool -- so a ranked model's position among ranked entries is still the ranking order.
        List<ModelInfo> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) modelsObj).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> info = (Map<String, Object>) e.getValue();
            String name = JsCoercion.isTruthy(info.get("name")) ? String.valueOf(info.get("name")) : e.getKey();
            Object limitObj = info.get("limit");
            Map<?, ?> limit = limitObj instanceof Map ? (Map<?, ?>) limitObj : Collections.emptyMap();
            out.add(new ModelInfo(e.getKey(), name, intOf(limit.get("context")), intOf(limit.get("output"))));
        }
        return out;
    }

    private static int intOf(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
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
}
