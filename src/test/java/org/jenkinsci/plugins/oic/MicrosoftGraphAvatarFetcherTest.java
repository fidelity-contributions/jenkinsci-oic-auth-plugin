package org.jenkinsci.plugins.oic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.util.Secret;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import org.junit.jupiter.api.Test;

class MicrosoftGraphAvatarFetcherTest {
    @Test
    void fetchesSupportedImage() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(connection.getContentType()).thenReturn("image/PNG; charset=binary");
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        when(retriever.openHTTPConnection(any())).thenReturn(connection);

        assertEquals(
                "data:image/png;base64,AQID",
                new MicrosoftGraphAvatarFetcher(URI.create("https://graph.microsoft.com/photo").toURL(), retriever)
                        .fetchAsDataUrl(credentials("access-token")));
    }

    @Test
    void rejectsUnsupportedResponse() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(connection.getContentType()).thenReturn("image/svg+xml");
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        when(retriever.openHTTPConnection(any())).thenReturn(connection);

        assertNull(new MicrosoftGraphAvatarFetcher(URI.create("https://graph.microsoft.com/photo").toURL(), retriever)
                .fetchAsDataUrl(credentials("token")));
    }

    @Test
    void handlesMissingTokenAndConnectionFailure() throws Exception {
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        when(retriever.openHTTPConnection(any())).thenThrow(new IOException("connection failed"));
        MicrosoftGraphAvatarFetcher fetcher = new MicrosoftGraphAvatarFetcher(
                MicrosoftGraphAvatarFetcher.defaultEndpointUrl(), retriever);

        assertNull(fetcher.fetchAsDataUrl(null));
        assertNull(fetcher.fetchAsDataUrl(credentials("token")));
    }

    @Test
    void rejectsNonSuccessfulEmptyAndOversizedResponses() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        when(retriever.openHTTPConnection(any())).thenReturn(connection);
        MicrosoftGraphAvatarFetcher fetcher = new MicrosoftGraphAvatarFetcher(
                MicrosoftGraphAvatarFetcher.defaultEndpointUrl(), retriever);
        assertNull(fetcher.fetchAsDataUrl(credentials("token")));

        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        assertNull(fetcher.fetchAsDataUrl(credentials("token")));
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(
                new byte[OicAvatarProperty.AvatarImage.MAX_SIZE + 1]));
        assertNull(fetcher.fetchAsDataUrl(credentials("token")));
    }

    @Test
    void detectsGraphPhotoUrls() {
        assertEquals(true, MicrosoftGraphAvatarFetcher.isGraphPhotoUrl("https://graph.microsoft.com/v1.0/me/photo/$value"));
        assertEquals(false, MicrosoftGraphAvatarFetcher.isGraphPhotoUrl("https://example.org/avatar.png"));
        assertEquals(false, MicrosoftGraphAvatarFetcher.isGraphPhotoUrl(null));
    }

    private static OicCredentials credentials(String accessToken) {
        return new OicCredentials(Secret.fromString(accessToken), null, null, null);
    }
}
