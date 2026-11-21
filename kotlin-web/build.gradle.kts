plugins {
    kotlin("multiplatform") version "2.4.21"
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
