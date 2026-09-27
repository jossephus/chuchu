plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// A minimal chuchu plugin APK, and the template for writing one. It installs like any app
// (no launcher icon) and chuchu loads its code into its own process once the user enables it.
android {
    namespace = "com.jossephus.chuchu.example.hello"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.jossephus.chuchu.example.hello"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // compileOnly: chuchu provides the plugin API and Compose at runtime. Its class loader
    // is consulted first, so bundled copies would only be dead weight, and code compiled
    // against a newer bundled Compose could call methods chuchu's copy doesn't have.
    compileOnly(project(":plugin-api"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.foundation)
}

// The Kotlin plugin adds kotlin-stdlib to every module; chuchu already provides it, so keep
// it off the APK's runtime classpath (the compile classpath still has it).
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains", module = "annotations")
    }
}
