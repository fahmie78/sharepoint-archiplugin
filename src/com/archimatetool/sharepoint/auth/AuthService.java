package com.archimatetool.sharepoint.auth;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.archimatetool.sharepoint.prefs.Prefs;
import com.microsoft.aad.msal4j.AuthorizationCodeParameters;
import com.microsoft.aad.msal4j.DeviceCodeFlowParameters;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.MsalInteractionRequiredException;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;

/**
 * Office-like Microsoft 365 / SharePoint Online authentication service backed by MSAL4J.
 * <p>
 * Interactive sign-in first tries a system-browser + localhost loopback flow and falls
 * back to the device-code flow when the loopback server cannot be started or the browser
 * cannot be opened. Tokens are cached on disk (see {@link TokenCacheStore}) so silent
 * acquisition works across restarts.
 * </p>
 * <p>
 * All blocking MSAL calls run on a daemon executor, never on the UI thread. Callbacks may
 * fire on any thread; UI code must marshal to the SWT UI thread itself.
 * </p>
 */
public final class AuthService {

    /** OAuth2 scopes requested for SharePoint/Graph access. */
    static final Set<String> SCOPES = new LinkedHashSet<>(
            List.of("Sites.ReadWrite.All", "Files.ReadWrite.All", "offline_access", "User.Read"));

    private static final String LOGIN_BASE = "https://login.microsoftonline.com/";
    private static final Duration LOOPBACK_TIMEOUT = Duration.ofMinutes(3);

    private static AuthService instance;

    /**
     * @return the lazily created singleton instance
     */
    public static synchronized AuthService get() {
        if (instance == null) {
            instance = new AuthService();
        }
        return instance;
    }

    private final ExecutorService executor;
    private final TokenCacheStore tokenCache;
    /** Guards against concurrent interactive sign-in attempts. */
    private final AtomicBoolean signInInProgress = new AtomicBoolean();

    private PublicClientApplication application;
    private String applicationClientId;
    private String applicationTenant;
    private volatile IAccount account;

    private AuthService() {
        ThreadFactory daemonFactory = runnable -> {
            Thread thread = new Thread(runnable, "sharepoint-auth");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newCachedThreadPool(daemonFactory);
        this.tokenCache = new TokenCacheStore();
    }

    /**
     * @return {@code true} if a signed-in account is known (possibly restored from the
     *         persisted token cache)
     */
    public boolean isSignedIn() {
        return currentAccount() != null;
    }

    /**
     * @return the signed-in user's name (UPN), or {@code null} if not signed in
     */
    public String getSignedInUser() {
        IAccount current = currentAccount();
        return current != null ? current.username() : null;
    }

    /**
     * Acquires an access token for Microsoft Graph silently (from cache / refresh token).
     *
     * @return a future completing with the access token, or completing exceptionally with
     *         an {@link AuthException} if interactive login is needed
     */
    public CompletableFuture<String> acquireGraphToken() {
        CompletableFuture<String> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(acquireTokenSilently());
            } catch (Throwable t) {
                future.completeExceptionally(
                        t instanceof AuthException authException ? authException
                                : new AuthException("Failed to acquire an access token", t));
            }
        });
        return future;
    }

    /**
     * Interactive sign-in. Tries the loopback browser flow first (calling
     * {@code opener.open(authorizeUrl)}), falling back to the device-code flow (reported
     * via the {@code showDeviceCode} consumer) if the loopback server cannot be bound or
     * the opener fails. Runs asynchronously; completion is reported via the callback on an
     * unspecified (non-UI) thread. Only one interactive sign-in may run at a time; a
     * concurrent attempt fails immediately with
     * {@code onError(new AuthException("Sign-in already in progress"))}.
     *
     * @param opener         opens the authorize URL in the system browser
     * @param showDeviceCode consumer notified when device-code flow is used (may be {@code null})
     * @param callback       receives the success or error outcome
     */
    public void signInInteractive(BrowserOpener opener, Consumer<DeviceCodeInfo> showDeviceCode,
            AuthCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (!signInInProgress.compareAndSet(false, true)) {
            callback.onError(new AuthException("Sign-in already in progress"));
            return;
        }
        executor.execute(() -> {
            try {
                IAuthenticationResult result = tryLoopbackFlow(opener);
                if (result == null) {
                    result = deviceCodeFlow(showDeviceCode);
                }
                if (result.account() != null) {
                    account = result.account();
                }
                callback.onSuccess(getSignedInUser());
            } catch (Throwable t) {
                callback.onError(t instanceof AuthException authException ? authException
                        : new AuthException("Sign-in failed", unwrap(t)));
            } finally {
                signInInProgress.set(false);
            }
        });
    }

    /**
     * Signs out: removes all accounts from the MSAL cache and deletes the persisted
     * token-cache file.
     */
    public void signOut() {
        account = null;
        PublicClientApplication app = application;
        if (app != null) {
            try {
                for (IAccount acc : app.getAccounts().join()) {
                    app.removeAccount(acc).join();
                }
            } catch (RuntimeException ignored) {
                // best-effort; the cache file is deleted below anyway
            }
        }
        tokenCache.clear();
    }

    // ------------------------------------------------------------------ internals

    private String acquireTokenSilently() throws AuthException {
        IAccount current = currentAccount();
        if (current == null) {
            throw new AuthException("Not signed in; interactive login required");
        }
        try {
            IAuthenticationResult result = application()
                    .acquireTokenSilently(SilentParameters.builder(SCOPES, current).build()).join();
            if (result.account() != null) {
                account = result.account();
            }
            return result.accessToken();
        } catch (MalformedURLException e) {
            throw new AuthException("Invalid authority URL", e);
        } catch (CompletionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof MsalInteractionRequiredException) {
                throw new AuthException("Interactive sign-in required", cause);
            }
            throw new AuthException("Failed to acquire an access token: " + messageOf(cause), cause);
        }
    }

    private IAccount currentAccount() {
        IAccount current = account;
        if (current != null) {
            return current;
        }
        try {
            Set<IAccount> accounts = application().getAccounts().join();
            if (!accounts.isEmpty()) {
                account = accounts.iterator().next();
            }
        } catch (AuthException | CompletionException ignored) {
            // not configured or not signed in
        }
        return account;
    }

    /**
     * Runs the loopback browser flow.
     *
     * @return the authentication result, or {@code null} if the flow is unavailable and
     *         the caller should fall back to device code
     */
    private IAuthenticationResult tryLoopbackFlow(BrowserOpener opener) throws AuthException {
        LoopbackAuthCodeServer server;
        try {
            server = new LoopbackAuthCodeServer();
        } catch (IOException e) {
            return null; // cannot bind -> device code fallback
        }
        try {
            server.start();
            String redirectUri = server.getRedirectUri();
            String expectedState = UUID.randomUUID().toString();
            // PKCE is trivially supported by msal4j 1.23.1
            // (AuthorizationCodeParametersBuilder.codeVerifier), so use it
            String codeVerifier = newCodeVerifier();
            String authorizeUrl = buildAuthorizeUrl(redirectUri, expectedState,
                    codeChallenge(codeVerifier));
            try {
                opener.open(authorizeUrl);
            } catch (Exception e) {
                return null; // browser could not be opened -> device code fallback
            }
            String code;
            try {
                code = server.awaitCode(LOOPBACK_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthException("Sign-in interrupted", e);
            }
            if (!expectedState.equals(server.getReceivedState())) {
                throw new AuthException("Sign-in failed: OAuth2 state mismatch"
                        + " (the loopback response does not belong to this sign-in attempt)");
            }
            try {
                return application().acquireToken(AuthorizationCodeParameters
                        .builder(code, new URI(redirectUri)).scopes(SCOPES)
                        .codeVerifier(codeVerifier).build()).join();
            } catch (URISyntaxException e) {
                throw new AuthException("Invalid redirect URI", e);
            } catch (CompletionException e) {
                throw new AuthException("Failed to redeem authorization code: "
                        + messageOf(unwrap(e)), unwrap(e));
            }
        } finally {
            server.stop();
        }
    }

    private IAuthenticationResult deviceCodeFlow(Consumer<DeviceCodeInfo> showDeviceCode)
            throws AuthException {
        Consumer<com.microsoft.aad.msal4j.DeviceCode> consumer = deviceCode -> {
            if (showDeviceCode != null) {
                showDeviceCode.accept(new DeviceCodeInfo(deviceCode.userCode(),
                        deviceCode.verificationUri(), deviceCode.message()));
            }
        };
        try {
            return application()
                    .acquireToken(DeviceCodeFlowParameters.builder(SCOPES, consumer).build()).join();
        } catch (CompletionException e) {
            throw new AuthException("Device code sign-in failed: " + messageOf(unwrap(e)), unwrap(e));
        }
    }

    private String buildAuthorizeUrl(String redirectUri, String state, String codeChallenge)
            throws AuthException {
        String tenant = tenant();
        StringBuilder url = new StringBuilder(LOGIN_BASE).append(encode(tenant))
                .append("/oauth2/v2.0/authorize?client_id=").append(encode(clientId()))
                .append("&response_type=code").append("&redirect_uri=").append(encode(redirectUri))
                .append("&response_mode=query").append("&scope=")
                .append(encode(String.join(" ", SCOPES))).append("&prompt=select_account")
                .append("&state=").append(encode(state))
                .append("&code_challenge=").append(encode(codeChallenge))
                .append("&code_challenge_method=S256");
        return url.toString();
    }

    /** @return a fresh PKCE code verifier (RFC 7636) */
    private static String newCodeVerifier() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /**
     * @param codeVerifier the PKCE code verifier
     * @return the S256 code challenge for the verifier
     * @throws AuthException if SHA-256 is unavailable
     */
    private static String codeChallenge(String codeVerifier) throws AuthException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AuthException("SHA-256 unavailable for PKCE", e);
        }
    }

    private synchronized PublicClientApplication application() throws AuthException {
        String clientId = clientId();
        String tenant = tenant();
        if (application == null || !clientId.equals(applicationClientId)
                || !tenant.equals(applicationTenant)) {
            try {
                application = PublicClientApplication.builder(clientId)
                        .authority(LOGIN_BASE + tenant)
                        .setTokenCacheAccessAspect(tokenCache).build();
                applicationClientId = clientId;
                applicationTenant = tenant;
            } catch (MalformedURLException e) {
                throw new AuthException("Invalid tenant: " + tenant, e);
            }
        }
        return application;
    }

    private static String clientId() throws AuthException {
        String clientId = Prefs.getClientId();
        if (clientId == null || clientId.isBlank()) {
            throw new AuthException("Configure your Entra ID app registration (client ID) in "
                    + "Window > Preferences > SharePoint");
        }
        return clientId.trim();
    }

    private static String tenant() {
        String tenant = Prefs.getTenant();
        return tenant == null || tenant.isBlank() ? "organizations" : tenant.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private static String messageOf(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
