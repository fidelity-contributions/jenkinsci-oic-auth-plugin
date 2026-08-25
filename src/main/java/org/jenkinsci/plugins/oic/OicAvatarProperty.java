package org.jenkinsci.plugins.oic;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import java.io.IOException;
import java.util.Base64;
import java.util.Set;
import jenkins.security.csp.AvatarContributor;
import org.kohsuke.accmod.restrictions.suppressions.SuppressRestrictedWarnings;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerResponse2;

public class OicAvatarProperty extends UserProperty implements Action {

    private final AvatarImage avatarImage;
    private final String avatarFileName;
    private final String avatarContentType;

    public OicAvatarProperty(AvatarImage avatarImage) {
        this.avatarImage = avatarImage;
        this.avatarFileName = null;
        this.avatarContentType = null;
    }

    public OicAvatarProperty(User user, AvatarImage avatarImage) throws IOException {
        AvatarData data = avatarImage == null ? null : parseDataUrl(avatarImage.url);
        if (data == null) {
            this.avatarImage = avatarImage;
            this.avatarFileName = null;
            this.avatarContentType = null;
            return;
        }
        this.avatarImage = null;
        this.avatarFileName = "oic-avatar";
        this.avatarContentType = data.contentType();
        java.io.File userFolder = user.getUserFolder();
        if (userFolder == null) {
            throw new IOException("Unable to store avatar because the user folder is unavailable");
        }
        java.nio.file.Files.write(userFolder.toPath().resolve(avatarFileName), data.bytes());
    }

    public String getAvatarUrl() {
        return getAvatarUrlForUser(user);
    }

    public String getAvatarUrlForUser(User avatarUser) {
        if (isHasAvatar()) {
            if (avatarFileName != null) {
                if (avatarUser == null) {
                    return null;
                }
                String userUrl = avatarUser.getUrl();
                if (!userUrl.endsWith("/")) {
                    userUrl += "/";
                }
                return userUrl + getUrlName() + "/image";
            }
            if (avatarImage.isDataUrl() && avatarUser != null) {
                String userUrl = avatarUser.getUrl();
                if (!userUrl.endsWith("/")) {
                    userUrl += "/";
                }
                return userUrl + getUrlName() + "/image";
            }
            return avatarImage.url;
        }
        return null;
    }

    public boolean isHasAvatar() {
        if (avatarFileName != null) {
            java.io.File userFolder = user == null ? null : user.getUserFolder();
            return userFolder != null && new java.io.File(userFolder, avatarFileName).isFile();
        }
        return avatarImage != null && avatarImage.isValid();
    }

    public String getDisplayName() {
        return "OpenID Connect Avatar";
    }

    public String getIconFileName() {
        return null;
    }

    public String getUrlName() {
        return "oic-avatar";
    }

    public void doImage(StaplerResponse2 response) throws IOException {
        if (avatarFileName != null && user != null) {
            java.io.File userFolder = user.getUserFolder();
            if (userFolder != null) {
                java.io.File avatarFile = new java.io.File(userFolder, avatarFileName);
                if (avatarFile.isFile()) {
                    try (java.io.InputStream input = java.nio.file.Files.newInputStream(avatarFile.toPath())) {
                        try {
                            response.serveFile(
                                    Stapler.getCurrentRequest2(),
                                    input,
                                    avatarFile.lastModified(),
                                    avatarFile.length(),
                                    avatarContentType);
                        } catch (jakarta.servlet.ServletException e) {
                            throw new IOException("Unable to serve avatar", e);
                        }
                    }
                    return;
                }
            }
        }
        AvatarData data = avatarImage == null ? null : parseDataUrl(avatarImage.url);
        if (data == null) {
            response.sendError(404);
            return;
        }
        response.setContentType(data.contentType());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(data.bytes());
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return "OpenID Connect Avatar";
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public UserProperty newInstance(User user) {
            return new OicAvatarProperty(null);
        }
    }

    /**
     * OIC avatar is standard picture field on the profile claim.
     */
    @SuppressRestrictedWarnings(AvatarContributor.class)
    public static class AvatarImage {
        static final int MAX_SIZE = 5 * 1024 * 1024;

        private final String url;

        public AvatarImage(String url) {
            this.url = url;
            AvatarContributor.allow(url);
        }

        public boolean isDataUrl() {
            return url != null && url.startsWith("data:");
        }

        public boolean isValid() {
            return url != null && (!isDataUrl() || parseDataUrl(url) != null);
        }

        private Object readResolve() {
            AvatarContributor.allow(url);
            return this;
        }
    }

    private record AvatarData(String contentType, byte[] bytes) {}

    private static AvatarData parseDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return null;
        }
        int separator = dataUrl.indexOf(',');
        if (separator <= 5 || separator == dataUrl.length() - 1) {
            return null;
        }
        String metadata = dataUrl.substring(5, separator);
        if (!metadata.endsWith(";base64")) {
            return null;
        }
        int metadataSeparator = metadata.indexOf(';');
        String contentType = metadata.substring(0, metadataSeparator);
        if (!metadata.equals(contentType + ";base64")) {
            return null;
        }
        if (!Set.of("image/gif", "image/jpeg", "image/png", "image/webp").contains(contentType)) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(separator + 1));
            return bytes.length > AvatarImage.MAX_SIZE ? null : new AvatarData(contentType, bytes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
