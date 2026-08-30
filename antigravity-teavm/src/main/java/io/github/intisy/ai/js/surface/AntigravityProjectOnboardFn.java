package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsFn;
import io.github.intisy.ai.tsemit.TsUnion;

import java.util.concurrent.CompletionStage;

/** The host's managed-project provisioning. */
@TsFn
public interface AntigravityProjectOnboardFn {

    /**
     * Provisions a managed project for one account.
     *
     * @param accessToken the account's access token
     * @param tierId the tier to provision under
     * @param projectId the project to provision, or empty to let the upstream choose
     * @param proxy the outbound proxy, or empty for a direct connection
     * @return the provisioned project id as JSON, or null when it did not complete
     */
    @TsUnion({"string", "null"})
    CompletionStage<String> onboard(String accessToken, String tierId, String projectId, String proxy);
}
