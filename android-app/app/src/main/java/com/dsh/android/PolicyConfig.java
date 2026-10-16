package com.dsh.android;

/** Materializes a literal module name: loader names do not evaluate !!js expressions. */
final class PolicyConfig {
    static String materialize(String template, String moduleUri) {
        if (!template.contains("__ANDROID_POLICY_MODULE_URI__")) throw new IllegalArgumentException("missing policy module placeholder");
        java.net.URI uri = java.net.URI.create(moduleUri);
        if (!"file".equals(uri.getScheme()) || moduleUri.contains("\"") || moduleUri.contains("\n") || moduleUri.contains("\\")) {
            throw new IllegalArgumentException("invalid policy module URI");
        }
        return template.replace("__ANDROID_POLICY_MODULE_URI__", moduleUri);
    }
}
