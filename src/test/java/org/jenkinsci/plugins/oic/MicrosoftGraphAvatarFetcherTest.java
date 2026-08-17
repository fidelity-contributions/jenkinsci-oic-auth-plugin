package org.jenkinsci.plugins.oic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.util.Secret;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MicrosoftGraphAvatarFetcherTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    @Test
    void fetchesImageAsDataUrl() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/photo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "image/PNG; charset=binary")
                        .withBody(new byte[] {1, 2, 3})));

        OicCredentials credentials = credentials("access-token");
        String dataUrl = new MicrosoftGraphAvatarFetcher(
                        URI.create(wireMock.url("/photo")).toURL(),
                        ProxyAwareResourceRetriever.createProxyAwareResourceRetriver(false))
                .fetchAsDataUrl(credentials);

        assertEquals("data:image/png;base64,AQID", dataUrl);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/photo"))
                .withHeader("Authorization", equalTo("Bearer access-token"))
                .withHeader("Accept", equalTo("image/*")));
    }

    @Test
    void returnsNullWhenAccessTokenIsMissing() throws Exception {
        String dataUrl = new MicrosoftGraphAvatarFetcher(
                        MicrosoftGraphAvatarFetcher.defaultEndpointUrl(),
                        ProxyAwareResourceRetriever.createProxyAwareResourceRetriver(false))
                .fetchAsDataUrl(null);

        assertNull(dataUrl);
    }

    @Test
    void returnsNullForNonSuccessfulOrEmptyResponses() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/missing")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(get(urlPathEqualTo("/empty")).willReturn(aResponse().withStatus(200)));

        ProxyAwareResourceRetriever retriever = ProxyAwareResourceRetriever.createProxyAwareResourceRetriver(false);
        assertNull(new MicrosoftGraphAvatarFetcher(
                        URI.create(wireMock.url("/missing")).toURL(), retriever)
                .fetchAsDataUrl(credentials("token")));
        assertNull(new MicrosoftGraphAvatarFetcher(
                        URI.create(wireMock.url("/empty")).toURL(), retriever)
                .fetchAsDataUrl(credentials("token")));
    }

    @Test
    void returnsNullForNonImageResponse() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/text"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("text")));

        String dataUrl = new MicrosoftGraphAvatarFetcher(
                        URI.create(wireMock.url("/text")).toURL(),
                        ProxyAwareResourceRetriever.createProxyAwareResourceRetriver(false))
                .fetchAsDataUrl(credentials("token"));

        assertNull(dataUrl);
    }

    @Test
    void defaultsMissingContentTypeToJpeg() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/no-content-type"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", " ")
                        .withBody(new byte[] {1})));

        String dataUrl = new MicrosoftGraphAvatarFetcher(
                        URI.create(wireMock.url("/no-content-type")).toURL(),
                        ProxyAwareResourceRetriever.createProxyAwareResourceRetriver(false))
                .fetchAsDataUrl(credentials("token"));

        assertEquals("data:image/jpeg;base64,AQ==", dataUrl);
    }

    @Test
    void defaultsMissingContentTypeOnConnectionToJpeg() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(connection.getContentType()).thenReturn(null);
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        when(retriever.openHTTPConnection(any())).thenReturn(connection);

        String dataUrl = new MicrosoftGraphAvatarFetcher(MicrosoftGraphAvatarFetcher.defaultEndpointUrl(), retriever)
                .fetchAsDataUrl(credentials("token"));

        assertEquals("data:image/jpeg;base64,AQ==", dataUrl);
    }

    @Test
    void rejectsOversizedImage() throws Exception {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream())
                .thenReturn(new ByteArrayInputStream(new byte[OicAvatarProperty.AvatarImage.MAX_SIZE + 1]));
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        when(retriever.openHTTPConnection(any())).thenReturn(connection);

        assertNull(
                new MicrosoftGraphAvatarFetcher(new URL(MicrosoftGraphAvatarFetcher.DEFAULT_PHOTO_ENDPOINT), retriever)
                        .fetchAsDataUrl(credentials("token")));
    }

    @Test
    void returnsNullWhenConnectionFails() throws Exception {
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        when(retriever.openHTTPConnection(any())).thenThrow(new IOException("connection failed"));

        assertNull(new MicrosoftGraphAvatarFetcher(MicrosoftGraphAvatarFetcher.defaultEndpointUrl(), retriever)
                .fetchAsDataUrl(credentials("token")));
    }

    @Test
    void identifiesDefaultEndpoint() throws Exception {
        assertTrue(MicrosoftGraphAvatarFetcher.defaultEndpointUrl().toString().endsWith("/photo/$value"));
    }

    private static OicCredentials credentials(String accessToken) {
        return new OicCredentials(Secret.fromString(accessToken), null, null, null);
    }
}
