package org.jenkinsci.plugins.oic;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.oic.OicAvatarProperty.AvatarData;

/**
 * Downloads the image referenced by the OIDC {@code picture} claim so that it can be served by Jenkins.
 *
 * <p>Some providers only expose the picture through an endpoint that requires the access token (for example the
 * <a href="https://learn.microsoft.com/en-us/entra/identity-platform/userinfo">Microsoft Graph photo URL</a>), which a
 * browser can never load directly. Fetching the image server side works for those providers without requiring any
 * provider specific configuration.
 */
class OicAvatarFetcher {

    private static final Logger LOGGER = Logger.getLogger(OicAvatarFetcher.class.getName());

    private final ProxyAwareResourceRetriever resourceRetriever;

    OicAvatarFetcher(@NonNull ProxyAwareResourceRetriever resourceRetriever) {
        this.resourceRetriever = resourceRetriever;
    }

    /**
     * @param avatarUrl the {@code http} or {@code https} URL of the image to download
     * @param accessToken the access token to present to the provider, or {@code null} to fetch anonymously
     * @return the downloaded image, or {@code null} if it could not be retrieved or is not a supported image
     */
    @CheckForNull
    AvatarData fetch(@NonNull String avatarUrl, @CheckForNull String accessToken) {
        URL url;
        try {
            url = new URI(avatarUrl).toURL();
        } catch (IllegalArgumentException | IOException | URISyntaxException e) {
            LOGGER.log(Level.FINE, "Avatar URL is not a valid URL", e);
            return null;
        }
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"https".equals(protocol) && !"http".equals(protocol)) {
            LOGGER.fine(() -> "Ignoring avatar URL with unsupported protocol " + protocol);
            return null;
        }

        HttpURLConnection connection = null;
        try {
            connection = resourceRetriever.openHTTPConnection(url);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "image/*");
            if (accessToken != null && "https".equals(protocol)) {
                // do not follow redirects, otherwise the token could be sent to an unrelated host
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LOGGER.fine(() -> "Avatar download failed with status " + responseCode);
                return null;
            }

            String contentType = Util.fixEmptyAndTrim(connection.getContentType());
            if (contentType != null) {
                contentType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            }
            if (!AvatarData.SUPPORTED_CONTENT_TYPES.contains(contentType)) {
                LOGGER.fine("Avatar download returned an unsupported content type");
                return null;
            }

            byte[] image;
            try (InputStream input = connection.getInputStream()) {
                image = input.readNBytes(OicAvatarProperty.AvatarImage.MAX_SIZE + 1);
            }
            if (image.length == 0 || image.length > OicAvatarProperty.AvatarImage.MAX_SIZE) {
                LOGGER.fine("Avatar download returned an empty or oversized image");
                return null;
            }
            return new AvatarData(contentType, image);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Avatar download failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
