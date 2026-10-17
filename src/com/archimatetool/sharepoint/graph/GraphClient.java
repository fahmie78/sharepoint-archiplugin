package com.archimatetool.sharepoint.graph;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal Microsoft Graph REST client (v1.0) for SharePoint document libraries,
 * built on {@link java.net.http.HttpClient} and Gson.
 * <p>
 * All methods are blocking and must NOT be called on the UI thread. Every request
 * carries {@code Authorization: Bearer <token>} obtained from the token provider;
 * on HTTP 401 the request is retried once with a fresh token before a
 * {@link GraphAuthException} is thrown.
 * </p>
 */
public class GraphClient {

    private static final String BASE = "https://graph.microsoft.com/v1.0";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(120);

    private final Supplier<CompletableFuture<String>> tokenProvider;
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String baseUrl;

    /**
     * Creates a client obtaining access tokens from the given provider.
     *
     * @param tokenProvider supplies access tokens (e.g. {@code AuthService.get()::acquireGraphToken})
     */
    public GraphClient(Supplier<CompletableFuture<String>> tokenProvider) {
        this(tokenProvider, BASE);
    }

    /** Constructor with explicit base URL (for tests against a mock server). */
    GraphClient(Supplier<CompletableFuture<String>> tokenProvider, String baseUrl) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /**
     * Resolves a SharePoint site from its web URL.
     *
     * @param siteUrl e.g. {@code https://contoso.sharepoint.com/sites/team[/sub]}
     * @return the resolved site
     * @throws GraphException on invalid input, network or server errors
     */
    public Site resolveSite(String siteUrl) throws GraphException {
        URI uri;
        try {
            uri = new URI(siteUrl == null ? "" : siteUrl.trim());
        } catch (URISyntaxException e) {
            throw new GraphException(-1, "Invalid site URL: " + siteUrl, e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new GraphException(-1, "Invalid site URL (missing host): " + siteUrl);
        }
        StringBuilder url = new StringBuilder(baseUrl).append("/sites/").append(encode(host));
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+|/+$", "");
        if (!path.isEmpty()) {
            url.append(":/").append(encodePath(path));
        }
        JsonObject json = getJson(url.toString());
        Site site = new Site();
        site.id = string(json, "id");
        site.displayName = string(json, "displayName");
        site.webUrl = string(json, "webUrl");
        return site;
    }

    /**
     * Lists the document libraries (drives) of a site.
     *
     * @param siteId the Graph site id
     * @return the drives (never {@code null})
     * @throws GraphException on network or server errors
     */
    public List<Drive> listDrives(String siteId) throws GraphException {
        String url = baseUrl + "/sites/" + encode(siteId) + "/drives";
        List<Drive> drives = new ArrayList<>();
        while (url != null) {
            JsonObject page = getJson(url);
            for (JsonElement element : array(page, "value")) {
                JsonObject o = element.getAsJsonObject();
                Drive drive = new Drive();
                drive.id = string(o, "id");
                drive.name = string(o, "name");
                drives.add(drive);
            }
            url = nextLink(page);
        }
        return drives;
    }

    /**
     * Lists the children of a folder in a drive.
     *
     * @param driveId    the Graph drive id
     * @param folderPath the folder path relative to the drive root; {@code ""} or {@code "/"}
     *                   denotes the root
     * @return the child items (never {@code null})
     * @throws GraphException on network or server errors
     */
    public List<DriveItem> listChildren(String driveId, String folderPath) throws GraphException {
        String url;
        if (isRoot(folderPath)) {
            url = baseUrl + "/drives/" + encode(driveId) + "/root/children";
        } else {
            url = baseUrl + "/drives/" + encode(driveId) + "/root:/" + encodePath(folderPath)
                    + ":/children";
        }
        List<DriveItem> items = new ArrayList<>();
        while (url != null) {
            JsonObject page = getJson(url);
            for (JsonElement element : array(page, "value")) {
                items.add(toDriveItem(element.getAsJsonObject()));
            }
            url = nextLink(page);
        }
        return items;
    }

    /**
     * Gets metadata for an item addressed by path relative to the drive root.
     *
     * @param driveId  the Graph drive id
     * @param itemPath the item path (e.g. {@code folder/model.archimate})
     * @return the item metadata
     * @throws GraphException on network or server errors ({@link NotFoundException} if absent)
     */
    public DriveItem getItem(String driveId, String itemPath) throws GraphException {
        String url;
        if (isRoot(itemPath)) {
            url = baseUrl + "/drives/" + encode(driveId) + "/root";
        } else {
            url = baseUrl + "/drives/" + encode(driveId) + "/root:/" + encodePath(itemPath);
        }
        return toDriveItem(getJson(url));
    }

    /**
     * Uploads content to an item addressed by path, creating or overwriting it.
     *
     * @param driveId     the Graph drive id
     * @param itemPath    the item path relative to the drive root
     * @param content     the bytes to upload
     * @param ifMatchETag {@code null}/empty for plain create-or-overwrite; otherwise sent
     *                    verbatim (including quotes) as the {@code If-Match} header
     * @return the fresh item metadata (new ETag)
     * @throws PreconditionFailedException if the ETag did not match (HTTP 412)
     * @throws GraphException              on network or server errors
     */
    public DriveItem uploadByPath(String driveId, String itemPath, byte[] content,
            String ifMatchETag) throws GraphException {
        String url = baseUrl + "/drives/" + encode(driveId) + "/root:/" + encodePath(itemPath)
                + ":/content";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .header("Content-Type", "application/octet-stream");
        if (ifMatchETag != null && !ifMatchETag.isEmpty()) {
            request.header("If-Match", ifMatchETag);
        }
        HttpResponse<byte[]> response = execute(request, TRANSFER_TIMEOUT);
        return toDriveItem(parse(response.body()));
    }

    /**
     * Downloads the content of an item.
     *
     * @param driveId the Graph drive id
     * @param itemId  the Graph item id
     * @return the file bytes
     * @throws GraphException on network or server errors
     */
    public byte[] download(String driveId, String itemId) throws GraphException {
        String url = baseUrl + "/drives/" + encode(driveId) + "/items/" + encode(itemId) + "/content";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        return execute(request, TRANSFER_TIMEOUT).body();
    }

    /**
     * Checks out an item (SharePoint check-out).
     *
     * @param driveId the Graph drive id
     * @param itemId  the Graph item id
     * @throws GraphException on network or server errors (409 if already checked out)
     */
    public void checkOut(String driveId, String itemId) throws GraphException {
        String url = baseUrl + "/drives/" + encode(driveId) + "/items/" + encode(itemId) + "/checkout";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody());
        execute(request, DEFAULT_TIMEOUT);
    }

    /**
     * Checks in an item (SharePoint check-in).
     *
     * @param driveId the Graph drive id
     * @param itemId  the Graph item id
     * @param comment the check-in comment
     * @throws GraphException on network or server errors (409 if not checked out)
     */
    public void checkIn(String driveId, String itemId, String comment) throws GraphException {
        String url = baseUrl + "/drives/" + encode(driveId) + "/items/" + encode(itemId) + "/checkin";
        JsonObject body = new JsonObject();
        body.addProperty("comment", comment == null ? "" : comment);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers
                        .ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json");
        execute(request, DEFAULT_TIMEOUT);
    }

    // ------------------------------------------------------------------ internals

    private JsonObject getJson(String url) throws GraphException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET()
                .header("Accept", "application/json");
        return parse(execute(request, DEFAULT_TIMEOUT).body());
    }

    private HttpResponse<byte[]> execute(HttpRequest.Builder request, Duration timeout)
            throws GraphException {
        request.timeout(timeout);
        request.setHeader("Authorization", "Bearer " + awaitToken());
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() == 401) {
            // retry once with a fresh token
            request.setHeader("Authorization", "Bearer " + awaitToken());
            response = send(request);
        }
        if (response.statusCode() >= 400) {
            throw mapError(response);
        }
        return response;
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws GraphException {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new GraphException(-1, "Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException(-1, "Request interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new GraphException(-1, "Invalid request: " + e.getMessage(), e);
        }
    }

    private String awaitToken() throws GraphAuthException {
        try {
            return tokenProvider.get().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            throw new GraphAuthException(401, "Authentication failed: " + message);
        } catch (RuntimeException e) {
            throw new GraphAuthException(401, "Authentication failed: " + e.getMessage());
        }
    }

    private GraphException mapError(HttpResponse<byte[]> response) {
        int code = response.statusCode();
        String message = extractErrorMessage(response.body());
        if (message == null || message.isBlank()) {
            message = "HTTP " + code;
        }
        return switch (code) {
            case 404 -> new NotFoundException(message);
            case 412 -> new PreconditionFailedException(message);
            case 401, 403 -> new GraphAuthException(code, message);
            default -> new GraphException(code, message);
        };
    }

    private String extractErrorMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                JsonElement error = parsed.getAsJsonObject().get("error");
                if (error != null && error.isJsonObject()) {
                    JsonElement message = error.getAsJsonObject().get("message");
                    if (message != null && message.isJsonPrimitive()) {
                        return message.getAsString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // body was not JSON - no message available
        }
        return null;
    }

    private JsonObject parse(byte[] body) throws GraphException {
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new GraphException(-1, "Invalid JSON response", e);
        }
    }

    private static DriveItem toDriveItem(JsonObject o) {
        DriveItem item = new DriveItem();
        item.id = string(o, "id");
        item.name = string(o, "name");
        item.eTag = string(o, "eTag");
        JsonElement size = o.get("size");
        item.size = size != null && size.isJsonPrimitive() ? size.getAsLong() : 0;
        item.lastModified = string(o, "lastModifiedDateTime");
        JsonObject parentReference = object(o, "parentReference");
        item.parentPath = parentReference != null ? string(parentReference, "path") : null;
        JsonObject lastModifiedBy = object(o, "lastModifiedBy");
        JsonObject user = lastModifiedBy != null ? object(lastModifiedBy, "user") : null;
        item.lastModifiedBy = user != null ? string(user, "displayName") : null;
        JsonElement folder = o.get("folder");
        item.folder = folder != null && folder.isJsonObject();
        return item;
    }

    private static String nextLink(JsonObject page) {
        return string(page, "@odata.nextLink");
    }

    private static JsonArray array(JsonObject o, String member) {
        JsonElement element = o.get(member);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonObject o, String member) {
        JsonElement element = o.get(member);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject o, String member) {
        JsonElement element = o.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean isRoot(String path) {
        return path == null || path.isBlank() || path.equals("/");
    }

    /** URL-encodes a single value (id or path segment); spaces become %20, not '+'. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** URL-encodes each segment of a drive-relative path, keeping '/' separators. */
    private static String encodePath(String path) {
        StringBuilder result = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(encode(segment));
        }
        return result.toString();
    }
}
