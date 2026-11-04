package com.circularsync.web;

import java.nio.file.Path;
import java.util.Objects;

/** Network and security configuration of the web portal. */
public final class PortalSettings {

    private final String host;
    private final int port;
    private final Path webRoot;
    private final Path demoDirectory;
    private final String adminKey;
    private final boolean trustProxy;

    /**
     * @param host          interface to bind: "127.0.0.1" for this computer only, "0.0.0.0" for all
     * @param port          TCP port; 0 picks a free one
     * @param webRoot       folder holding index.html
     * @param demoDirectory folder of demo notices offered as one-click drops
     * @param adminKey      admin password required for uploads, deletions and demo drops; null disables the check
     * @param trustProxy    take the client address from X-Forwarded-For / CF-Connecting-IP
     *                      (only when running behind a reverse proxy or tunnel)
     */
    public PortalSettings(String host, int port, Path webRoot, Path demoDirectory, String adminKey, boolean trustProxy) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.webRoot = Objects.requireNonNull(webRoot, "webRoot");
        this.demoDirectory = Objects.requireNonNull(demoDirectory, "demoDirectory");
        this.adminKey = adminKey == null || adminKey.isBlank() ? null : adminKey;
        this.trustProxy = trustProxy;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Path getWebRoot() {
        return webRoot;
    }

    public Path getDemoDirectory() {
        return demoDirectory;
    }

    public String getAdminKey() {
        return adminKey;
    }

    public boolean isAdminProtected() {
        return adminKey != null;
    }

    public boolean isTrustProxy() {
        return trustProxy;
    }
}
