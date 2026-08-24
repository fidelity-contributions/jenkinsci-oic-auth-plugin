package org.jenkinsci.plugins.oic;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

/**
 * Fetches the current user's profile photo from Microsoft Graph for IdPs that
 * do not surface it as a standard OIDC {@code picture} claim (e.g. Azure Entra ID).
 *
 * The image is returned as a {@code data:} URL so the byte stream can be persisted
 * alongside other avatar URLs in {@link OicAvatarProperty}.
 */
class MicrosoftGraphAvatarFetcher {

    private static final Logger LOGGER = Logger.getLogger(MicrosoftGraphAvatarFetcher.class.getName());

    static final String DEFAULT_PHOTO_ENDPOINT = "https://graph.microsoft.com/v1.0/me/photo/$value";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String GRAPH_HOST_PREFIX = "graph.microsoft.";

    private final URL endpointUrl;
    private final ProxyAwareResourceRetriever resourceRetriever;

    MicrosoftGraphAvatarFetcher(@NonNull URL endpointUrl, @NonNull ProxyAwareResourceRetriever resourceRetriever) {
        this.endpointUrl = endpointUrl;
        this.resourceRetriever = resourceRetriever;
    }

    static URL defaultEndpointUrl() throws MalformedURLException {
        return new URL(DEFAULT_PHOTO_ENDPOINT);
    }

    @CheckForNull
    String fetchAsDataUrl(@CheckForNull OicCredentials credentials) {
        String accessToken = credentials == null ? null : Util.fixEmptyAndTrim(credentials.getAccessToken());
        if (accessToken == null) {
            LOGGER.finest("Microsoft Graph avatar is enabled but access token was empty.");
            return null;
        }

        HttpURLConnection connection = null;
        try {
            connection = resourceRetriever.openHTTPConnection(endpointUrl);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", BEARER_PREFIX + accessToken);
            connection.setRequestProperty("Accept", "image/*");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LOGGER.fine("Microsoft Graph avatar lookup failed with status " + responseCode);
                return null;
            }

            byte[] image = connection.getInputStream().readAllBytes();
            if (image.length == 0 || image.length > OicAvatarProperty.AvatarImage.MAX_SIZE) {
                LOGGER.fine("Microsoft Graph avatar lookup returned an empty or oversized body");
                return null;
            }

            String contentType = Util.fixEmptyAndTrim(connection.getContentType());
            if (contentType == null) {
                contentType = "image/jpeg";
            }
            contentType = contentType.split(";", 2)[0].toLowerCase(java.util.Locale.ROOT);
            if (!contentType.equals("image/gif")
                    && !contentType.equals("image/jpeg")
                    && !contentType.equals("image/png")
                    && !contentType.equals("image/webp")) {
                LOGGER.fine("Microsoft Graph avatar lookup returned an unsupported content type: " + contentType);
                return null;
            }
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(image);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Microsoft Graph avatar lookup failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static boolean isGraphPhotoUrl(@CheckForNull String avatarUrl) {
        if (avatarUrl == null) {
            return false;
        }
        try {
            URI uri = new URI(avatarUrl);
            String host = Util.fixEmptyAndTrim(uri.getHost());
            if (host == null || !StringUtils.startsWithIgnoreCase(host, GRAPH_HOST_PREFIX)) {
                return false;
            }
            String path = Util.fixEmptyAndTrim(uri.getPath());
            return path != null && StringUtils.containsIgnoreCase(path, "/photo");
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
