package org.jenkinsci.plugins.oic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.model.User;
import jakarta.servlet.ServletOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
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
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(output).write(new byte[] {1, 2, 3});
        assertEquals(dataUrl, property.getAvatarUrlForUser(null));

        User userWithTrailingSlash = mock(User.class);
        when(userWithTrailingSlash.getUrl()).thenReturn("user/avatar-user/");
        assertEquals("user/avatar-user/oic-avatar/image", property.getAvatarUrlForUser(userWithTrailingSlash));
    }

    @Test
    void rejectsMalformedDataUrls() throws Exception {
        assertFalse(new OicAvatarProperty.AvatarImage(null).isDataUrl());
        assertFalse(new OicAvatarProperty.AvatarImage("not-a-data-url").isDataUrl());
        OicAvatarProperty nonDataProperty = new OicAvatarProperty(new OicAvatarProperty.AvatarImage("not-a-data-url"));
        StaplerResponse2 nonDataResponse = mock(StaplerResponse2.class);
        nonDataProperty.doImage(nonDataResponse);
        verify(nonDataResponse).sendError(404);
        OicAvatarProperty shortDataProperty = new OicAvatarProperty(new OicAvatarProperty.AvatarImage("data:,"));
        StaplerResponse2 shortDataResponse = mock(StaplerResponse2.class);
        shortDataProperty.doImage(shortDataResponse);
        verify(shortDataResponse).sendError(404);
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/png,QQ==").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/png;base64,").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:text/plain;base64,QQ==").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/png;base64,not-base64").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/svg+xml;base64,QQ==").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/x-custom;base64,QQ==").isValid());
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/png;charset=utf-8;base64,QQ==").isValid());
        OicAvatarProperty unsafeProperty = new OicAvatarProperty(
                new OicAvatarProperty.AvatarImage("data:image/svg+xml;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="));
        StaplerResponse2 unsafeResponse = mock(StaplerResponse2.class);
        unsafeProperty.doImage(unsafeResponse);
        verify(unsafeResponse).sendError(404);

        String oversizedData = Base64.getEncoder().encodeToString(new byte[OicAvatarProperty.AvatarImage.MAX_SIZE + 1]);
        assertFalse(new OicAvatarProperty.AvatarImage("data:image/png;base64," + oversizedData).isValid());

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
        OicAvatarProperty property =
                new OicAvatarProperty(new OicAvatarProperty.AvatarImage("https://example.org/avatar.png"));

        assertTrue(property.isHasAvatar());
        assertEquals("https://example.org/avatar.png", property.getAvatarUrl());
    }

    @Test
    void storesDataAvatarInUserFolderAndServesItFromDisk(JenkinsRule rule) throws Exception {
        byte[] content = new byte[] {1, 2, 3};
        User user = User.getById("disk-avatar-user", true);
        user.save();
        OicAvatarProperty property = new OicAvatarProperty(
                user, new OicAvatarProperty.AvatarImage(dataUrl("image/png", content)));
        user.addProperty(property);

        java.io.File avatarFile = new java.io.File(user.getUserFolder(), "oic-avatar");
        assertTrue(avatarFile.isFile());
        assertArrayEquals(content, Files.readAllBytes(avatarFile.toPath()));
        assertEquals(user.getUrl() + "/oic-avatar/image", property.getAvatarUrlForUser(user));

        StaplerResponse2 response = mock(StaplerResponse2.class);
        property.doImage(response);

        verify(response).serveFile(
                isNull(), any(), eq(avatarFile.lastModified()), eq(avatarFile.length()), eq("image/png"));
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
