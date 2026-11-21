plugins {
    kotlin("multiplatform") version "2.4.21"
}

// Kotlin/JS runtime artifacts (stdlib and DOM APIs) are published to Maven Central.
repositories {
    mavenCentral()
}

kotlin {
    js {
        browser()
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            // Browser APIs are accessed through Kotlin/JS interop.
        }
    }
}
