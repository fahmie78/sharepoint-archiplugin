package com.archimatetool.sharepoint.auth;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import com.microsoft.aad.msal4j.ITokenCacheAccessAspect;
import com.microsoft.aad.msal4j.ITokenCacheAccessContext;

/**
 * Persists the MSAL4J token cache to a file so sessions survive restarts.
 * The serialized cache is stored Base64-encoded at
 * {@code <plugin state dir>/token-cache.dat}.
 * <p>
 * Internal helper of {@link AuthService}. All methods are synchronized and never
 * throw; persistence failures are silently ignored (worst case the user has to
 * sign in again).
 * </p>
 */
final class TokenCacheStore implements ITokenCacheAccessAspect {

    private final File cacheFile;

    /** Bumped by {@link #clear()}; makes writes from accesses started earlier no-ops. */
    private long generation;

    /** The generation seen by the most recent {@link #beforeCacheAccess}. */
    private long readGeneration;

    /**
     * Creates a store using the default plugin state location.
     */
    TokenCacheStore() {
        this(new File(LoopbackAuthCodeServer.resolveStateDir(), "token-cache.dat"));
    }

    /**
     * Creates a store writing to the given file.
     *
     * @param cacheFile the backing file
     */
    TokenCacheStore(File cacheFile) {
        this.cacheFile = cacheFile;
    }

    @Override
    public synchronized void beforeCacheAccess(ITokenCacheAccessContext context) {
        readGeneration = generation;
        try {
            if (cacheFile.isFile()) {
                byte[] encoded = Files.readAllBytes(cacheFile.toPath());
                String serialized = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                if (!serialized.isEmpty()) {
                    context.tokenCache().deserialize(serialized);
                }
            }
        } catch (Exception ignored) {
            // corrupted or unreadable cache - start with an empty cache
        }
    }

    @Override
    public synchronized void afterCacheAccess(ITokenCacheAccessContext context) {
        if (!context.hasCacheChanged()) {
            return;
        }
        if (readGeneration != generation) {
            // stale access: clear() (sign-out) ran after this cache access started, so
            // persisting now would resurrect the deleted token-cache file
            return;
        }
        try {
            File parent = cacheFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            byte[] encoded = Base64.getEncoder()
                    .encode(context.tokenCache().serialize().getBytes(StandardCharsets.UTF_8));
            Files.write(cacheFile.toPath(), encoded);
        } catch (IOException | RuntimeException ignored) {
            // cache persistence is best-effort
        }
    }

    /**
     * Deletes the persisted cache (used on sign-out). Bumping the generation first makes
     * any in-flight {@link #afterCacheAccess} a no-op, so the deletion cannot be undone
     * by a stale write.
     */
    synchronized void clear() {
        generation++;
        try {
            Files.deleteIfExists(cacheFile.toPath());
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
