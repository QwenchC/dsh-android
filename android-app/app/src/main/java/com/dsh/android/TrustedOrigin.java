package com.dsh.android;

import java.net.URI;

/** Exact loopback origin comparison; paths and tokens never broaden the allowed host or port. */
final class TrustedOrigin {
    static String from(String value) {
        try {
            URI uri = new URI(value);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() < 1 || uri.getPort() > 65535) return null;
            return "http://127.0.0.1:" + uri.getPort();
        } catch (Exception invalid) { return null; }
    }

    static boolean allows(String expected, String candidate) {
        return expected != null && expected.equals(from(candidate));
    }
}
