plugins {
    id("com.android.application")
}

android {
    namespace = "com.nepsepulse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nepsepulse.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Play Console reports obfuscation and optimisation below threshold
            // because R8 was disabled. Enable shrinking, obfuscation and
            // optimisation with the optimising default rule set plus the
            // project's own keep rules (proguard-rules.pro).
            // Signing configuration is deliberately not changed here.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
