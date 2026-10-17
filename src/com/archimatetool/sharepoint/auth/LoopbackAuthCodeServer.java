package com.archimatetool.sharepoint.auth;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal loopback HTTP server used to receive the OAuth2 authorization code
 * redirect from the system browser. Binds to the loopback address on an
 * ephemeral port and answers the first request carrying a {@code code} or
 * {@code error} query parameter with a "you can close this window" page.
 * <p>
 * Internal helper of {@link AuthService}; not thread-safe, single use.
 * </p>
 */
final class LoopbackAuthCodeServer {

    private static final String RESPONSE_PAGE = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
            + "<title>Sign-in complete</title></head><body style=\"font-family:sans-serif\">"
            + "<h2>Sign-in complete</h2><p>You can close this window and return to Archi.</p>"
            + "</body></html>";

    private final HttpServer server;
    private final CountDownLatch latch = new CountDownLatch(1);

    private volatile String code;
    private volatile String state;
    private volatile String error;
    private volatile String errorDescription;

    /**
     * Creates and binds the server on an ephemeral loopback port.
     *
     * @throws IOException if the socket could not be bound
     */
    LoopbackAuthCodeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(command -> {
            Thread thread = new Thread(command, "sharepoint-auth-loopback");
            thread.setDaemon(true);
            thread.start();
        });
    }

    /**
     * @return the port the server is bound to
     */
    int getPort() {
        return server.getAddress().getPort();
    }

    /**
     * @return the redirect URI to register for this flow ({@code http://localhost:<port>/})
     */
    String getRedirectUri() {
        return "http://localhost:" + getPort() + "/";
    }

    /**
     * Starts accepting requests.
     */
    void start() {
        server.start();
    }

    /**
     * Stops the server immediately.
     */
    void stop() {
        try {
            server.stop(0);
        } catch (RuntimeException ignored) {
            // already stopped
        }
    }

    /**
     * Blocks until the authorization code arrives or the timeout elapses.
     *
     * @param timeout maximum time to wait
     * @return the authorization code
     * @throws AuthException        on timeout or if the provider returned an error
     * @throws InterruptedException if the waiting thread is interrupted
     */
    String awaitCode(Duration timeout) throws AuthException, InterruptedException {
        if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AuthException("Timed out waiting for the sign-in to complete in the browser");
        }
        if (error != null) {
            String detail = errorDescription != null ? error + " (" + errorDescription + ")" : error;
            throw new AuthException("Sign-in failed: " + detail);
        }
        if (code == null) {
            throw new AuthException("No authorization code received");
        }
        return code;
    }

    /**
     * @return the OAuth2 {@code state} parameter received together with the
     *         authorization code, or {@code null} if the response carried none
     */
    String getReceivedState() {
        return state;
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            String receivedCode = query.get("code");
            if (receivedCode != null) {
                code = receivedCode;
                state = query.get("state");
                latch.countDown();
            } else if (query.containsKey("error")) {
                error = query.get("error");
                errorDescription = query.get("error_description");
                latch.countDown();
            }
            // else: ancillary request (e.g. favicon) - answer but keep waiting
            byte[] body = RESPONSE_PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.put(urlDecode(pair), "");
            } else {
                result.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return result;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Resolves the directory used to persist plugin state (token cache) without
     * depending on the plugin activator.
     *
     * @return the plugin state directory, falling back to
     *         {@code ~/.archi/sharepoint-sync} outside an OSGi runtime
     */
    static File resolveStateDir() {
        File dir = null;
        try {
            org.osgi.framework.Bundle bundle = org.eclipse.core.runtime.Platform
                    .getBundle("com.archimatetool.sharepoint");
            if (bundle != null) {
                org.eclipse.core.runtime.IPath location = org.eclipse.core.runtime.Platform
                        .getStateLocation(bundle);
                if (location != null) {
                    dir = location.toFile();
                }
            }
        } catch (Throwable ignored) {
            // not running inside OSGi - use fallback
        }
        if (dir == null) {
            dir = new File(System.getProperty("user.home"), ".archi" + File.separator + "sharepoint-sync");
        }
        return dir;
    }
}
