package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.jvm.backend.clock.SystemClock;
import io.github.intisy.ai.jvm.backend.http.UrlConnectionHttpClient;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.random.SecureRandomAdapter;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.manager.AccountManager;
import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.oauth.OAuthConfig;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.store.AccountStore;

import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2 self-assembled backend (plan DECISION FLAG A: self-assembly, no {@code HandlerCtx}
 * change): builds the {@code :jvm} SPI implementations + a real {@code AccountManager} lazily
 * from {@link io.github.intisy.ai.shared.routing.HandlerCtx#configDir}, memoized per
 * {@code configDir} so repeated {@code handle()} calls on the same provider instance reuse one
 * {@code Store}/{@code AccountManager} rather than re-opening the accounts file store on every
 * request (mirrors {@code io.github.intisy.ai.jvm.AiJava#accountManager}, which this provider
 * cannot call directly -- a {@code Provider} is handed only {@code HandlerCtx}, never an
 * {@code AiJava} instance).
 *
 * <p>Google OAuth endpoint/client (plan DECISION FLAG C): the antigravity refresh token is stored
 * RAW in {@code account.refresh} (project ids live in {@code account.meta}, read directly by
 * {@link AntigravityProvider}), so {@link AccountManager#ensureAccess} can refresh it against
 * Google's token endpoint unmodified. Client id/secret mirror {@code src/constants.ts}'s
 * {@code ANTIGRAVITY_CLIENT_ID}/{@code ANTIGRAVITY_CLIENT_SECRET} (the same values
 * {@code src/antigravity/oauth.ts} POSTs to {@code https://oauth2.googleapis.com/token}).
 */
final class AntigravityBackend {

    static final String PROVIDER_ID = "antigravity";

    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String ANTIGRAVITY_CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    // NEVER hardcode the OAuth client secret: it would be published in this provider jars public
    // source and trips GitHub push protection. It is read from the ANTIGRAVITY_CLIENT_SECRET env
    // var at runtime (set it to the same shared antigravity client secret used by the TS plugin).

    private static final ConcurrentHashMap<String, AntigravityBackend> CACHE = new ConcurrentHashMap<>();

    final JsonCodec json;
    final Clock clock;
    final Random random;
    final HttpClient http;
    final AccountManager accounts;

    private AntigravityBackend(String configDir) {
        FileStore store = (configDir != null && !configDir.trim().isEmpty())
                ? new FileStore(Paths.get(configDir))
                : FileStore.fromEnv();

        this.json = new GsonJsonCodec();
        this.clock = new SystemClock();
        this.random = new SecureRandomAdapter();
        this.http = new UrlConnectionHttpClient();

        AccountStore accountStore = new AccountStore(store, json);

        OAuthConfig oauth = new OAuthConfig();
        oauth.tokenUrl = GOOGLE_TOKEN_URL;
        oauth.clientId = ANTIGRAVITY_CLIENT_ID;
        String cs = System.getenv("ANTIGRAVITY_CLIENT_SECRET"); oauth.clientSecret = cs != null ? cs : "";

        ManagerOptions opts = new ManagerOptions();
        opts.oauth = oauth;

        this.accounts = new AccountManager(PROVIDER_ID, accountStore, http, clock, random, json, opts);
    }

    /** Memoized per {@code configDir} (empty/null folds to the same {@code FileStore.fromEnv()} key). */
    static AntigravityBackend forConfigDir(String configDir) {
        String key = configDir != null ? configDir : "";
        return CACHE.computeIfAbsent(key, AntigravityBackend::new);
    }
}
