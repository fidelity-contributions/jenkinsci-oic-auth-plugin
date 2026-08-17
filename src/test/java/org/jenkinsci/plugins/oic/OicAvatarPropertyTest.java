package org.jenkinsci.plugins.oic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.model.User;
import jakarta.servlet.ServletOutputStream;
import java.lang.reflect.Method;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerResponse2;

@WithJenkins
class OicAvatarPropertyTest {
    @Test
    void servesDataUrlForUserAvatar(JenkinsRule rule) throws Exception {
        String dataUrl = dataUrl("image/png", new byte[] {1, 2, 3});
        OicAvatarProperty property = new OicAvatarProperty(new OicAvatarProperty.AvatarImage(dataUrl));
        User user = User.getById("avatar-user", true);

        assertTrue(property.isHasAvatar());
        assertTrue(new OicAvatarProperty.AvatarImage(dataUrl).isDataUrl());
        assertEquals(user.getUrl() + "/oic-avatar/image", property.getAvatarUrlForUser(user));

        StaplerResponse2 response = mock(StaplerResponse2.class);
        ServletOutputStream output = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(output);
        property.doImage(response);

        verify(response).setContentType("image/png");
        verify(output).write(new byte[] {1, 2, 3});
        assertEquals(dataUrl, property.getAvatarUrlForUser(null));
    }

    @Test
    void rejectsMalformedDataUrls() throws Exception {
        assertFalse(new OicAvatarProperty.AvatarImage("not-a-data-url").isDataUrl());
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/png;base64,").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:text/plain;base64,QQ==").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/png;base64,not-base64").isValid());

        OicAvatarProperty emptyProperty = new OicAvatarProperty(null);
        assertFalse(emptyProperty.isHasAvatar());
        StaplerResponse2 emptyResponse = mock(StaplerResponse2.class);
        emptyProperty.doImage(emptyResponse);
        verify(emptyResponse).sendError(404);

        OicAvatarProperty nullImageProperty = new OicAvatarProperty(new OicAvatarProperty.AvatarImage(null));
        assertFalse(nullImageProperty.isHasAvatar());
        StaplerResponse2 nullImageResponse = mock(StaplerResponse2.class);
        nullImageProperty.doImage(nullImageResponse);
        verify(nullImageResponse).sendError(404);

        OicAvatarProperty property = new OicAvatarProperty(new OicAvatarProperty.AvatarImage("data:image/png;base64,"));
        assertNull(property.getAvatarUrl());
        StaplerResponse2 response = mock(StaplerResponse2.class);
        property.doImage(response);
        verify(response).sendError(404);
    }

    @Test
    void returnsDirectUrlForNonDataAvatar(JenkinsRule rule) {
        OicAvatarProperty property = new OicAvatarProperty(
                new OicAvatarProperty.AvatarImage("https://example.org/avatar.png"));

        assertTrue(property.isHasAvatar());
        assertEquals("https://example.org/avatar.png", property.getAvatarUrl());
    }

    @Test
    void preservesAvatarImageDuringDeserialization(JenkinsRule rule) throws Exception {
        OicAvatarProperty.AvatarImage image = new OicAvatarProperty.AvatarImage("https://example.org/avatar.png");
        Method readResolve = OicAvatarProperty.AvatarImage.class.getDeclaredMethod("readResolve");
        readResolve.setAccessible(true);

        assertEquals(image, readResolve.invoke(image));
    }

    private static String dataUrl(String contentType, byte[] content) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(content);
    }
}
