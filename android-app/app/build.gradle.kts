
plugins {
    id("com.android.application")
}

android {
    namespace = "com.nepsepulse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nepsepulse.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
