package com.archimatetool.sharepoint.auth;

/**
 * Strategy for opening a URL in the user's system browser.
 * Implementations typically delegate to the platform (e.g. {@code Program.launch(url)}).
 */
public interface BrowserOpener {

    /**
     * Opens the given URL in an external browser.
     *
     * @param url the URL to open
     * @throws Exception if the browser could not be launched
     */
    void open(String url) throws Exception;
}
