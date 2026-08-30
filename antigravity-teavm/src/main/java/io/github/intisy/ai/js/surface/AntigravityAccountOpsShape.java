package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * What the orchestrator tells the host about the accounts it is rotating through.
 *
 * @implNote Grouped into one object rather than passed as seven functions, because the transpiled
 * side invokes them by name on the underlying JS object. Every account crosses as JSON text, since
 * the host's account store already serialises it.
 */
@TsInterface
public interface AntigravityAccountOpsShape {

    /**
     * When a lane's soonest account comes back into rotation.
     *
     * @param lane the lane being asked about
     * @return the epoch-millisecond time as JSON, or {@code "null"} when nothing is waiting
     */
    String nextAvailableAt(String lane);

    /**
     * One attempt failed for a reason that is not a rate limit.
     *
     * @param accountId the account that failed
     * @param lane the lane it failed on
     * @param attempt which attempt this was, counting from one
     * @param message what went wrong
     */
    void reportError(String accountId, String lane, int attempt, String message);

    /**
     * One attempt hit the upstream rate limit.
     *
     * @param accountId the account that was limited
     * @param lane the lane it was limited on
     * @param resetMs when the limit resets, in epoch milliseconds
     */
    void reportRateLimit(String accountId, String lane, double resetMs);

    /**
     * One attempt served the request.
     *
     * @param accountId the account that served it
     */
    void reportSuccess(String accountId);

    /**
     * An attempt failed in a way that implicates the outbound IP rather than the account.
     *
     * @param accountId the account the attempt used
     * @param ipSuspected whether the proxy address is the likely cause
     */
    void reportProxyRateLimit(String accountId, boolean ipSuspected);

    /**
     * Every account the host currently holds.
     *
     * @return the accounts, as a JSON array
     */
    String list();

    /**
     * Persists the fields the orchestrator changed on one account.
     *
     * @param accountId the account to write back
     * @param updatedAccountJson the whole account after the change, as JSON
     */
    void mutate(String accountId, String updatedAccountJson);
}
