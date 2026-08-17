package org.jenkinsci.plugins.oic;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.model.User;
import hudson.util.Secret;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.htmlunit.Page;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.WithoutJenkins;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@WithJenkins
class OicSecurityRealmTest {

    public static final String ADMIN = "admin";

    private static final SimpleGrantedAuthority GRANTED_AUTH1 = new SimpleGrantedAuthority(ADMIN);

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .failOnUnmatchedRequests(true)
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void testAuthenticate_withAnonymousAuthenticationToken(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm(wireMock);
        AuthenticationManager manager = realm.getSecurityComponents().manager2;

        assertNotNull(manager);

        String key = "testKey";
        String principal = "testUser";
        Collection<GrantedAuthority> authorities = List.of(GRANTED_AUTH1);
        AnonymousAuthenticationToken token = new AnonymousAuthenticationToken(principal, key, authorities);

        assertEquals(token, manager.authenticate(token));
    }

    @Test
    void testAuthenticate_withUsernamePasswordAuthenticationToken(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm(wireMock);
        AuthenticationManager manager = realm.getSecurityComponents().manager2;
        assertNotNull(manager);
        String key = "testKey";
        Object principal = "testUser";
        Collection<GrantedAuthority> authorities = List.of(GRANTED_AUTH1);
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principal, key, authorities);
        assertThrows(BadCredentialsException.class, () -> assertEquals(token, manager.authenticate(token)));
    }

    @Test
    void testGetAuthenticationGatewayUrl(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm(wireMock);
        assertEquals("securityRealm/escapeHatch", realm.getAuthenticationGatewayUrl());
    }

    @Test
    void testShouldSetNullClientSecretWhenSecretIsNull(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults().WithClient("id without secret", null).build();
        assertEquals("none", Secret.toString(realm.getClientSecret()));
    }

    @Test
    void testGetValidRedirectUrl(JenkinsRule jenkinsRule) throws Exception {
        // root url is http://localhost:????/jenkins/
        final String rootUrl = jenkinsRule.jenkins.getRootUrl();

        TestRealm realm = new TestRealm.Builder(wireMock).WithMinimalDefaults().build();

        assertEquals(rootUrl + "foo", realm.getValidRedirectUrl("foo"));
        assertEquals(rootUrl + "foo", realm.getValidRedirectUrl("/jenkins/foo"));
        assertEquals(rootUrl + "foo", realm.getValidRedirectUrl(rootUrl + "foo"));
        assertEquals(rootUrl, realm.getValidRedirectUrl(null));
        assertEquals(rootUrl, realm.getValidRedirectUrl(""));

        assertEquals(rootUrl, realm.getValidRedirectUrl(OicLogoutAction.POST_LOGOUT_URL));
    }

    @Test
    void testShouldReturnRootUrlWhenRedirectUrlIsInvalid(JenkinsRule jenkinsRule) throws Exception {
        // root url is http://localhost:????/jenkins/
        String rootUrl = jenkinsRule.jenkins.getRootUrl();

        TestRealm realm = new TestRealm.Builder(wireMock).WithMinimalDefaults().build();

        assertEquals(rootUrl, realm.getValidRedirectUrl("/bar"));
        assertEquals(rootUrl, realm.getValidRedirectUrl("../bar"));
        assertEquals(rootUrl, realm.getValidRedirectUrl("http://localhost/"));
        assertEquals(rootUrl, realm.getValidRedirectUrl("http://localhost/bar/"));
        assertEquals(rootUrl, realm.getValidRedirectUrl("http://localhost/jenkins/../bar/"));
    }

    @Test
    @WithoutJenkins
    public void testMaybeOpenIdLogoutEndpoint() throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithLogout(Boolean.FALSE, "https://endpoint")
                        .build();
        Assertions.assertNull(realm.maybeOpenIdLogoutEndpoint("my-id-token", null, "https://localhost"));

        realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults().WithLogout(Boolean.TRUE, null).build();
        Assertions.assertNull(realm.maybeOpenIdLogoutEndpoint("my-id-token", null, "https://localhost"));

        realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults().WithLogout(Boolean.FALSE, null).build();
        Assertions.assertNull(realm.maybeOpenIdLogoutEndpoint("my-id-token", null, "https://localhost"));

        realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithLogout(Boolean.TRUE, "https://endpoint?query-param-1=test")
                        .build();
        assertEquals(
                "https://endpoint?query-param-1=test&id_token_hint=my-id-token&post_logout_redirect_uri=https%3A%2F%2Flocalhost",
                realm.maybeOpenIdLogoutEndpoint("my-id-token", null, "https://localhost"));
    }

    @Test
    @WithoutJenkins
    public void testMaybeOpenIdLogoutEndpointWithNoCustomLogoutQueryParameters() throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults().WithLogout(true, "https://endpoint").build();
        assertEquals(
                "https://endpoint?id_token_hint=my-id-token&post_logout_redirect_uri=https%3A%2F%2Flocalhost",
                realm.maybeOpenIdLogoutEndpoint("my-id-token", "null", "https://localhost"));
        assertEquals(
                "https://endpoint?id_token_hint=my-id-token&post_logout_redirect_uri=https%3A%2F%2Flocalhost",
                realm.maybeOpenIdLogoutEndpoint("my-id-token", null, "https://localhost"));
        assertEquals(
                "https://endpoint?id_token_hint=my-id-token&state=test&post_logout_redirect_uri=https%3A%2F%2Flocalhost",
                realm.maybeOpenIdLogoutEndpoint("my-id-token", "test", "https://localhost"));
        assertEquals("https://endpoint", realm.maybeOpenIdLogoutEndpoint(null, null, null));
    }

    @Test
    public void testMaybeOpenIdLogoutEndpointWithCustomLogoutQueryParameters(JenkinsRule jenkinsRule) throws Exception {
        jenkinsRule
                .jenkins
                .getDescriptorList(LogoutQueryParameter.class)
                .add(new LogoutQueryParameter.DescriptorImpl());
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithLogoutQueryParameters(List.of(
                                new LogoutQueryParameter("key1", " with-spaces   "),
                                new LogoutQueryParameter("param-only", "")))
                        .WithLogout(true, "https://endpoint")
                        .build();
        String result = realm.maybeOpenIdLogoutEndpoint("my-id-token", "test", "https://localhost");
        assertNotNull(result);
        assertFalse(result.contains("overwrite-test"));
        assertEquals(
                "https://endpoint?id_token_hint=my-id-token&state=test&post_logout_redirect_uri=https%3A%2F%2Flocalhost&key1=with-spaces&param-only",
                result);
    }

    @Test
    public void testMaybeOpenIdLogoutEndpointWithLogoutQueryParameters(JenkinsRule jenkinsRule) throws Exception {
        jenkinsRule
                .jenkins
                .getDescriptorList(LogoutQueryParameter.class)
                .add(new LogoutQueryParameter.DescriptorImpl());
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithLogoutQueryParameters(List.of(
                                new LogoutQueryParameter("a/test#", "1"),
                                new LogoutQueryParameter("b", ","),
                                new LogoutQueryParameter("b+", "$other:new"),
                                new LogoutQueryParameter("&ampersand", " 2@+ , ?"),
                                new LogoutQueryParameter("d=", " 2 "),
                                new LogoutQueryParameter("iamnull", null),
                                new LogoutQueryParameter(" e? ", "     ")))
                        .WithLogout(true, "https://endpoint")
                        .build();
        String result = realm.maybeOpenIdLogoutEndpoint("my-id-token", "test", "https://localhost");
        assertNotNull(result);
        assertFalse(result.contains("overwrite-test"));
        String queryParams = result.replace("https://endpoint?", "");
        assertEquals(
                Stream.of(
                                "b=%2C",
                                "id_token_hint=my-id-token",
                                "a%2Ftest%23=1",
                                "state=test",
                                "post_logout_redirect_uri=https%3A%2F%2Flocalhost",
                                "d%3D=2",
                                "iamnull",
                                "b%2B=%24other%3Anew",
                                "%26ampersand=2%40%2B+%2C+%3F",
                                "e%3F")
                        .sorted()
                        .toList(),
                Stream.of(queryParams.split("&")).sorted().toList());
    }

    @Test
    public void testOpenIdLoginEndpointWithCustomLoginQueryParameters(JenkinsRule jenkinsRule) throws Exception {
        jenkinsRule
                .jenkins
                .getDescriptorList(LogoutQueryParameter.class)
                .add(new LogoutQueryParameter.DescriptorImpl());
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithLoginQueryParameters(List.of(
                                new LoginQueryParameter("key1", "value1"),
                                new LoginQueryParameter("key with space", " space "),
                                new LoginQueryParameter("blah:wibble", "anything:here"),
                                new LoginQueryParameter("emailaddr", "joe@example.com")))
                        .build();
        jenkinsRule.jenkins.setSecurityRealm(realm);
        try (WebClient wc = jenkinsRule.createWebClient()) {
            wc.getOptions().setRedirectEnabled(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            Page redirect = wc.goTo("securityRealm/commenceLogin", null);
            String locationHeader = redirect.getWebResponse().getResponseHeaderValue("Location");
            // alas PAC4j does not maintain any ordering of its built URL
            assertThat(locationHeader, startsWith(wireMock.baseUrl()));
            URL u = new URL(locationHeader);
            String[] queries = u.getQuery().split("&");
            assertThat(
                    queries,
                    allOf(
                            hasItemInArray("key1=value1"),
                            hasItemInArray("key%20with%20space=space"),
                            hasItemInArray("blah%3Awibble=anything%3Ahere"),
                            hasItemInArray("emailaddr=joe%40example.com")));
        }
    }

    @Test
    void testProtectedAvatarUrlDetection() {
        assertTrue(OicSecurityRealm.isLikelyProtectedAvatarUrl("https://graph.microsoft.com/v1.0/me/photo/$value"));
        assertTrue(OicSecurityRealm.isLikelyProtectedAvatarUrl("https://graph.microsoft.com/beta/me/photo/$value"));
        assertFalse(OicSecurityRealm.isLikelyProtectedAvatarUrl("https://graph.microsoft.com"));
        assertFalse(OicSecurityRealm.isLikelyProtectedAvatarUrl("https://graph.microsoft.com/v1.0/me/photo"));
        assertFalse(OicSecurityRealm.isLikelyProtectedAvatarUrl("http://[invalid"));
        assertFalse(OicSecurityRealm.isLikelyProtectedAvatarUrl("https://example.org/my-avatar.png"));
        assertFalse(OicSecurityRealm.isLikelyProtectedAvatarUrl(null));
    }

    @Test
    void addsUserReadScopeForMicrosoftEntraIssuer(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithIssuer("https://tenant.login.microsoftonline.com/")
                        .WithScopes("openid email")
                        .build();

        assertTrue(
                realm.buildOidcClient().getConfiguration().getScope().toString().contains("User.Read"));
    }

    @Test
    void preservesExistingUserReadScopeForMicrosoftEntraIssuer(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithIssuer("https://tenant.login.microsoftonline.com/")
                        .WithScopes("openid email User.Read")
                        .build();

        assertEquals(
                "openid email User.Read",
                realm.buildOidcClient().getConfiguration().getScope().toString());
    }

    @Test
    void recognizesMicrosoftEntraIssuerAndDelegatesGraphAvatarFetch(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithIssuer("https://login.microsoft.com/")
                        .build();

        Method isEntra = OicSecurityRealm.class.getDeclaredMethod("isMicrosoftEntraProvider");
        isEntra.setAccessible(true);
        assertTrue((Boolean) isEntra.invoke(realm));

        Method createAvatarImage =
                OicSecurityRealm.class.getDeclaredMethod("createAvatarImage", String.class, OicCredentials.class);
        createAvatarImage.setAccessible(true);
        assertNull(createAvatarImage.invoke(realm, "https://graph.microsoft.com/v1.0/me/photo/$value", null));
    }

    @Test
    void createsAvatarImageWhenGraphFetchSucceeds(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = org.mockito.Mockito.spy(
                new TestRealm.Builder(wireMock).WithMinimalDefaults().build());
        ProxyAwareResourceRetriever retriever = mock(ProxyAwareResourceRetriever.class);
        HttpURLConnection connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(connection.getContentType()).thenReturn("image/png");
        when(retriever.openHTTPConnection(any())).thenReturn(connection);
        doReturn(retriever).when(realm).getResourceRetriever();

        Method createAvatarImage =
                OicSecurityRealm.class.getDeclaredMethod("createAvatarImage", String.class, OicCredentials.class);
        createAvatarImage.setAccessible(true);
        Object avatarImage = createAvatarImage.invoke(
                realm,
                "https://graph.microsoft.com/v1.0/me/photo/$value",
                new OicCredentials("token", null, null, 3600L, 0L, 0L));

        assertNotNull(avatarImage);
    }

    @Test
    void recognizesStsWindowsIssuerAndRejectsHttpGraphUrl(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults().WithIssuer("https://sts.windows.net/").build();
        Method isEntra = OicSecurityRealm.class.getDeclaredMethod("isMicrosoftEntraProvider");
        isEntra.setAccessible(true);
        assertTrue((Boolean) isEntra.invoke(realm));
        assertFalse(OicSecurityRealm.isLikelyProtectedAvatarUrl("http://graph.microsoft.com/v1.0/me/photo/$value"));
    }

    @Test
    void returnsFalseForIssuerWithoutHost(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults().WithIssuer("issuer").build();
        Method isEntra = OicSecurityRealm.class.getDeclaredMethod("isMicrosoftEntraProvider");
        isEntra.setAccessible(true);
        assertFalse((Boolean) isEntra.invoke(realm));
    }

    @Test
    void usesGraphEndpointWhenEntraUserHasNoPictureClaim(JenkinsRule jenkinsRule) throws Exception {
        TestRealm realm = new TestRealm.Builder(wireMock)
                .WithMinimalDefaults()
                        .WithIssuer("https://tenant.login.microsoftonline.com/")
                        .build();
        Method loginAndSetUserData = OicSecurityRealm.class.getDeclaredMethod(
                "loginAndSetUserData", String.class, com.nimbusds.jwt.JWT.class, Map.class, OicCredentials.class);
        loginAndSetUserData.setAccessible(true);

        loginAndSetUserData.invoke(
                realm,
                "entra-avatar-user",
                null,
                Map.of("sub", "entra-avatar-user"),
                new OicCredentials(null, null, null, null, null, null));

        assertTrue(User.getById("entra-avatar-user", false).getProperty(OicAvatarProperty.class) != null);
    }
}
