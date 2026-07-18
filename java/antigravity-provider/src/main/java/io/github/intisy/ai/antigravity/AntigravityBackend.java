package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.jvm.backend.clock.SystemClock;
import io.github.intisy.ai.jvm.backend.http.UrlConnectionHttpClient;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.random.SecureRandomAdapter;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.manager.AccountManager;
import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.oauth.OAuthConfig;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;

import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-assembled backend: builds the {@code :jvm} SPI implementations + a real {@code
 * AccountManager} lazily, memoized per {@code configDir}/{@link Store} so repeated {@code
 * handle()}/capability calls on the same provider instance reuse one {@code Store}/{@code
 * AccountManager} rather than re-opening the accounts file store on every request (mirrors {@code
 * io.github.intisy.ai.jvm.AiJava#accountManager}, which this provider cannot call directly -- a
 * {@code Provider} is handed only {@code HandlerCtx}, never an {@code AiJava} instance).
 *
 * <p>Store-threading (mirrors claude-code-auth's {@code ClaudeBackend}): {@link #forCtx} prefers
 * the server's injected {@link HandlerCtx#store}, so this backend never self-assembles a {@code
 * FileStore} when a host has one to give -- {@link #forConfigDir}'s {@code FileStore} path is kept
 * only as the store-less/legacy-host fallback {@link #forCtx} itself falls back to.
 *
 * <p>Google OAuth endpoint/client (plan DECISION FLAG C): the antigravity refresh token is stored
 * RAW in {@code account.refresh} (project ids live in {@code account.meta}, read directly by
 * {@link AntigravityProvider}), so {@link AccountManager#ensureAccess} can refresh it against
 * Google's token endpoint unmodified. Client id/secret mirror {@code src/constants.ts}'s
 * {@code ANTIGRAVITY_CLIENT_ID}/{@code ANTIGRAVITY_CLIENT_SECRET} (the same values
 * {@code src/antigravity/oauth.ts} POSTs to {@code https://oauth2.googleapis.com/token}) --
 * {@link AntigravityOAuth} reuses these same two constants for the authorize/exchange capability
 * rather than redeclaring them.
 */
final class AntigravityBackend {

    static final String PROVIDER_ID = "antigravity";

    static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String ANTIGRAVITY_CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    // NEVER hardcode the OAuth client secret: it would be published in this provider jars public
    // source and trips GitHub push protection. It is read from the ANTIGRAVITY_CLIENT_SECRET env
    // var at runtime (set it to the same shared antigravity client secret used by the TS plugin).

    private static final ConcurrentHashMap<String, AntigravityBackend> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Store, AntigravityBackend> STORE_CACHE = new ConcurrentHashMap<>();

    final JsonCodec json;
    final Clock clock;
    final Random random;
    final HttpClient http;
    final Store store;
    final AccountStore accountStore;
    final AccountManager accounts;

    private AntigravityBackend(String configDir) {
        this(configFileStore(configDir), new UrlConnectionHttpClient());
    }

    /**
     * Testability seam: lets a test inject a scripted {@link HttpClient} while everything else
     * self-assembles exactly like the production path (real {@code FileStore}, so a test can seed
     * {@code accounts.json} under a temp {@code configDir} via {@link #accountStore}). NOT
     * memoized in {@link #CACHE} -- every call builds a fresh instance, and production call sites
     * never see this constructor (package-private, only {@link #forConfigDir}/{@link #forCtx} are
     * public API).
     */
    AntigravityBackend(String configDir, HttpClient http) {
        this(configFileStore(configDir), http);
    }

    private static Store configFileStore(String configDir) {
        return (configDir != null && !configDir.trim().isEmpty())
                ? new FileStore(Paths.get(configDir))
                : FileStore.fromEnv();
    }

    /** Assembly ctor: takes the {@link Store} directly (no self-assembled {@code FileStore}). */
    private AntigravityBackend(Store store, HttpClient http) {
        this.json = new GsonJsonCodec();
        this.clock = new SystemClock();
        this.random = new SecureRandomAdapter();
        this.http = http;
        this.store = store;

        this.accountStore = new AccountStore(store, json);

        OAuthConfig oauth = new OAuthConfig();
        oauth.tokenUrl = GOOGLE_TOKEN_URL;
        oauth.clientId = ANTIGRAVITY_CLIENT_ID;
        oauth.clientSecret = System.getenv("ANTIGRAVITY_CLIENT_SECRET"); // null when unset -> OAuthConfig omits client_secret

        ManagerOptions opts = new ManagerOptions();
        opts.oauth = oauth;

        this.accounts = new AccountManager(PROVIDER_ID, accountStore, http, clock, random, json, opts);
    }

    /** Memoized per {@code configDir} (empty/null folds to the same {@code FileStore.fromEnv()} key). */
    static AntigravityBackend forConfigDir(String configDir) {
        String key = configDir != null ? configDir : "";
        return CACHE.computeIfAbsent(key, AntigravityBackend::new);
    }

    /** Memoized per {@link Store} identity: the server injects one store per host, so this keeps
     *  one backend/{@code AccountManager} per store, mirroring {@link #forConfigDir}'s memoization. */
    static AntigravityBackend forStore(Store store) {
        return STORE_CACHE.computeIfAbsent(store, s -> new AntigravityBackend(s, new UrlConnectionHttpClient()));
    }

    /** Serving entry point: prefer the server's injected store; fall back to a FileStore only for a
     *  legacy/store-less host (ctx.store == null) -- behavior-neutral there. Never forces a store. */
    static AntigravityBackend forCtx(HandlerCtx ctx) {
        if (ctx != null && ctx.store != null) return forStore(ctx.store);
        return forConfigDir(ctx != null ? ctx.configDir : null);
    }

    /** Test-only factory: a fresh (unmemoized) backend with an injected {@link HttpClient}. */
    static AntigravityBackend forTest(String configDir, HttpClient http) {
        return new AntigravityBackend(configDir, http);
    }

    /** Test-only factory: a fresh (unmemoized) backend built directly from an injected {@link Store}. */
    static AntigravityBackend forTest(Store store, HttpClient http) {
        return new AntigravityBackend(store, http);
    }

    /**
     * Test-only: pre-seeds {@link #CACHE} so a subsequent {@link #forConfigDir} call for this
     * {@code configDir} (e.g. from inside {@code AntigravityProvider#handle}) resolves to the
     * given (already-built, presumably {@link #forTest}) backend instead of self-assembling a
     * production one. Lets a test drive {@code AntigravityProvider#handle} end-to-end with a
     * scripted {@link HttpClient} WITHOUT changing any production call site -- {@link
     * #forConfigDir} itself is untouched; this only ever matters when a test has pre-populated the
     * map for its own (temp-directory) key first.
     */
    static void registerForTest(String configDir, AntigravityBackend backend) {
        CACHE.put(configDir != null ? configDir : "", backend);
    }

    /** Test-only: pre-seeds {@link #STORE_CACHE} so a subsequent {@link #forCtx}/{@link #forStore}
     *  call for this store resolves to the given (already-built, presumably {@link #forTest})
     *  backend instead of self-assembling a production one. */
    static void registerForTest(Store store, AntigravityBackend backend) {
        STORE_CACHE.put(store, backend);
    }
}
