package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsFn;
import io.github.intisy.ai.tsemit.TsUnion;

import java.util.concurrent.CompletionStage;

/** The host's managed-project fetch. */
@TsFn
public interface AntigravityProjectLoadFn {

    /**
     * What the upstream says about one account's managed project.
     *
     * @param accessToken the account's access token
     * @param projectId the project to ask about, or empty to let the upstream choose
     * @param proxy the outbound proxy, or empty for a direct connection
     * @return the payload as JSON, or null when every endpoint failed
     */
    @TsUnion({"string", "null"})
    CompletionStage<String> load(String accessToken, String projectId, String proxy);
}
