plugins {
    id("com.android.application")
}

android {
    namespace = "ua.flibrary.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.flibrary.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
