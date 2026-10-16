// AGP still initializes KotlinBaseApiPlugin for Java-only modules. Its bundled
// KGP 2.2.10 calls Configuration.setVisible; pin the fixed build dependency.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

// The DeepSeek Harness runtime is a payload, not a Gradle dependency.
plugins {
    alias(libs.plugins.android.application) apply false
}
