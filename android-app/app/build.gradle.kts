plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.dsh.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.dsh.android"
        // The runtime payload is a Node.js build with a 24 (Android 7.0) API
        // floor and a 64-bit-only ABI set.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // The Node executable and its libraries must exist as real files:
            // an application may execute only what the installer extracted,
            // never what it wrote into its own data directory.
            useLegacyPackaging = true
            // The payload is prebuilt and stripped; re-stripping it would also
            // touch the executable, which is not a loadable library.
            keepDebugSymbols += listOf("**/libnode.so", "**/librg.so", "**/libbash.so")
        }
        resources {
            excludes += listOf("META-INF/**", "**/*.kotlin_metadata")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
