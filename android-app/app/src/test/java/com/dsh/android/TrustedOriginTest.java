package com.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrustedOriginTest {
    private final String origin = "http://127.0.0.1:4321";
    @Test public void permitsOnlyExactRunOrigin() {
        assertTrue(TrustedOrigin.allows(origin, origin + "/?token=test"));
        assertTrue(TrustedOrigin.allows(origin, origin + "/rpc"));
        for (String url : new String[] { null, "http://127.0.0.1:4322/", "http://localhost:4321/",
                "https://127.0.0.1:4321/", "http://127.0.0.1.evil.test:4321/", "http://evil.test/",
                "http://evil@127.0.0.1:4321/", "javascript:alert(1)", "file:///tmp/test", "content://test",
                "http://127.0.0.1/", "http://127.0.0.1:65536/" }) {
            assertFalse(String.valueOf(url), TrustedOrigin.allows(origin, url));
        }
        assertFalse(TrustedOrigin.allows(null, origin));
    }
}
