import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.grandma.rumorguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grandma.rumorguard"
        // AccessibilityService.takeScreenshot needs Android 11 (API 30).
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    // android.jar only ships org.json stubs; JVM unit tests need the real implementation.
    testImplementation(libs.org.json)
}
