package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsFn;
import io.github.intisy.ai.tsemit.TsNullable;

/** The host's request preparation, which answers with an opaque handle or refuses the endpoint. */
@TsFn
public interface AntigravityPrepareFn {

    /**
     * One request, prepared for one account and endpoint.
     *
     * @param url the request url
     * @param bodyText the request body
     * @param method the request method
     * @param headersJson the caller's headers, as a JSON object
     * @param access the account's access token
     * @param projectId the project the request is billed to
     * @param endpoint the endpoint this attempt uses
     * @param headerStyle which header set that endpoint expects
     * @param accountJson the whole account, as JSON
     * @return an opaque handle plus the transform parameters as JSON, or null when preparing threw
     */
    @TsNullable(asNull = true)
    String prepare(String url, String bodyText, String method, String headersJson, String access,
                   String projectId, String endpoint, String headerStyle, String accountJson);
}
