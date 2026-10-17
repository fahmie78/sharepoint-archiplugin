package com.archimatetool.sharepoint.graph;

/**
 * A SharePoint site as returned by Microsoft Graph.
 */
public final class Site {

    /** The Graph site id. */
    public String id;

    /** The human-readable site title. */
    public String displayName;

    /** The site's web URL. */
    public String webUrl;
}
