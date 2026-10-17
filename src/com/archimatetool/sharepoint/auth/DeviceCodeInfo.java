package com.archimatetool.sharepoint.auth;

/**
 * Immutable details of an OAuth2 device-code flow, to be shown to the user so they
 * can complete sign-in on any device.
 */
public final class DeviceCodeInfo {

    /** The code the user must enter at the verification URI. */
    public final String userCode;

    /** The URI where the user enters the code. */
    public final String verificationUri;

    /** The full human-readable instruction message provided by the identity provider. */
    public final String message;

    /**
     * Creates a new instance from MSAL device code data.
     *
     * @param userCode        the code to enter
     * @param verificationUri the verification page URL
     * @param message         the full instruction message
     */
    public DeviceCodeInfo(String userCode, String verificationUri, String message) {
        this.userCode = userCode;
        this.verificationUri = verificationUri;
        this.message = message;
    }
}
