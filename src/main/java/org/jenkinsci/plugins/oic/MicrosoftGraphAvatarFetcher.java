package org.jenkinsci.plugins.oic;

import hudson.Util;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

final class MicrosoftGraphAvatarFetcher {
    static final String DEFAULT_PHOTO_ENDPOINT = "https://graph.microsoft.com/v1.0/me/photo/$value";
    private static final Logger LOGGER = Logger.getLogger(MicrosoftGraphAvatarFetcher.class.getName());
    private static final String BEARER_PREFIX = "Bearer ";

    private final URL endpointUrl;
    private final ProxyAwareResourceRetriever resourceRetriever;

    MicrosoftGraphAvatarFetcher(URL endpointUrl, ProxyAwareResourceRetriever resourceRetriever) {
        this.endpointUrl = endpointUrl;
        this.resourceRetriever = resourceRetriever;
    }

    static URL defaultEndpointUrl() throws MalformedURLException {
        return URI.create(DEFAULT_PHOTO_ENDPOINT).toURL();
    }

    String fetchAsDataUrl(OicCredentials credentials) {
        String accessToken = credentials == null ? null : Util.fixEmptyAndTrim(credentials.getAccessToken());
        if (accessToken == null) {
            LOGGER.finest("Microsoft Graph avatar is enabled but access token was empty");
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
                LOGGER.log(Level.FINE, "Microsoft Graph avatar request returned HTTP {0}", responseCode);
                return null;
            }
            byte[] bytes = connection.getInputStream().readAllBytes();
            if (bytes.length == 0 || bytes.length > OicAvatarProperty.AvatarImage.MAX_SIZE) {
                LOGGER.fine("Microsoft Graph avatar response was empty or too large");
                return null;
            }
            String contentType = Util.fixEmptyAndTrim(connection.getContentType());
            if (contentType == null) {
                contentType = "image/jpeg";
            }
            contentType = contentType.split(";", 2)[0].toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/")) {
                LOGGER.log(Level.WARNING, "Microsoft Graph avatar response was not an image: {0}", contentType);
                return null;
            }
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Microsoft Graph avatar lookup failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
