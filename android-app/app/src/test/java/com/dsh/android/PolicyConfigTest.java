package com.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class PolicyConfigTest {
    @Test public void moduleNameIsLiteralAndEncoded() {
        String uri = new java.io.File("/data/test #100%/android-policy.mjs").toURI().toASCIIString();
        String result = PolicyConfig.materialize("name: \"__ANDROID_POLICY_MODULE_URI__\"", uri);
        assertEquals("name: \"" + uri + "\"", result);
        assertFalse(result.contains("!!js"));
        assertTrue(result.contains("%23"));
    }
    @Test public void missingPlaceholderCannotSilentlyDisablePolicy() {
        assertThrows(IllegalArgumentException.class, () -> PolicyConfig.materialize("name: wrong", "file:/policy.mjs"));
    }
}
