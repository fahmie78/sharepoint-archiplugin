package com.archimatetool.sharepoint.auth;

/**
 * Callback reporting the outcome of an interactive sign-in attempt.
 * May be invoked on any thread; UI updates must be marshalled to the UI thread
 * by the implementation.
 */
public interface AuthCallback {

    /**
     * Called when sign-in completed successfully.
     *
     * @param accountName the signed-in user's name (e.g. UPN)
     */
    void onSuccess(String accountName);

    /**
     * Called when sign-in failed or was cancelled.
     *
     * @param error the cause, typically an {@link AuthException}
     */
    void onError(Throwable error);
}
