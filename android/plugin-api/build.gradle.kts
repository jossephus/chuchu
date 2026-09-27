plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // `apiCheck` (part of `check`) fails when the public API changes without an updated
    // api/plugin-api.api dump. Review the diff, bump PluginApi.VERSION if it breaks
    // existing plugins, then run `apiDump`.
    alias(libs.plugins.binary.compatibility.validator)
    `maven-publish`
}

// The contract third-party plugin APKs compile against (as compileOnly) and the host
// implements. Keep it free of app internals: plugins meet the host only through here.
android {
    namespace = "com.jossephus.chuchu.plugin.api"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Plugin authors depend on this artifact as compileOnly. The version tracks
// PluginApi.VERSION (major) so the dependency line says which contract a plugin targets.
// `publishReleasePublicationToBuildRepository` writes to build/repo for inspection; a real
// release adds a remote repository (e.g. GitHub Packages) here.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.jossephus.chuchu"
            artifactId = "plugin-api"
            version = "1.0.0"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "build"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

dependencies {
    // Exposed in the API surface (CoroutineScope), so plugins see the same types as the host.
    api(libs.kotlinx.coroutines.core)
    // compileOnly: the host app provides Compose at runtime, and a plugin APK must not bundle
    // its own copy (two Compose runtimes in one process crash).
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
}
