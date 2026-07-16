package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quota-display Task 2: the shared {@code fetchAvailableModels} upstream transport, extracted out
 * of {@link AntigravityModelsFetch} (behaviour-preserving refactor -- see that class's original
 * {@code sendToFirstSuccessfulEndpoint}/{@code requestBody}/{@code projectIdFromMeta}) so both
 * models-discovery ({@code GET /v1/models}) and quota-discovery ({@code GET /v1/quota},
 * {@link AntigravityQuotaFetch}) reuse ONE POST-over-endpoint-fallback implementation instead of
 * two copies.
 */
final class AntigravityUpstream {

    static final String FETCH_MODELS_PATH = "/v1internal:fetchAvailableModels";

    private AntigravityUpstream() {
    }

    /**
     * POSTs {@code fetchAvailableModels} over the endpoint fallback list with the account's
     * headers + cached projectId; returns the PARSED payload {@code Map}, or {@code null} on
     * all-endpoint failure / an unparsable body. Never throws -- every failure path folds into
     * {@code null}, matching the original {@code AntigravityModelsFetch.doFetch} behaviour.
     */
    static Map<String, Object> fetchAvailableModels(AntigravityBackend backend, String access, Account account) {
        String projectId = projectIdFromMeta(account.meta);
        String body = requestBody(backend, projectId);

        HttpResponse resp = sendToFirstSuccessfulEndpoint(backend, access, body);
        if (resp == null) {
            return null;
        }

        Object parsed;
        try {
            parsed = backend.json.parse(resp.body);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) parsed;
        return payload;
    }

    static HttpResponse sendToFirstSuccessfulEndpoint(AntigravityBackend backend, String access, String body) {
        for (String base : AntigravityHandleRouting.endpointsFor("antigravity")) {
            HttpRequest req = new HttpRequest();
            req.method = "POST";
            req.url = base + FETCH_MODELS_PATH;
            req.headers = AntigravityHostSeams.HostProjectLoader.loadHeaders(access);
            req.body = body;
            try {
                HttpResponse resp = backend.http.send(req);
                if (resp.status / 100 == 2) {
                    return resp;
                }
            } catch (RuntimeException e) {
                // try the next endpoint fallback
            }
        }
        return null;
    }

    // {"project":"<id>"} when a project id is known, else {} (matches the TS
    // `projectId ? {project} : {}` guard) -- built via the same JsonCodec used everywhere else in
    // this backend rather than hand-assembled JSON.
    static String requestBody(AntigravityBackend backend, String projectId) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (projectId != null && !projectId.trim().isEmpty()) {
            map.put("project", projectId);
        }
        return backend.json.stringify(map);
    }

    @SuppressWarnings("unchecked")
    static String projectIdFromMeta(Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        Object managed = meta.get("managedProjectId");
        if (managed instanceof String && !((String) managed).trim().isEmpty()) {
            return (String) managed;
        }
        Object raw = meta.get("projectId");
        return raw instanceof String && !((String) raw).trim().isEmpty() ? (String) raw : null;
    }
}
