plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.binary.compatibility.validator)
    `maven-publish`
}

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

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = System.getenv("GROUP") ?: "com.jossephus.chuchu"
            artifactId = "plugin-api"
            version = System.getenv("VERSION") ?: "1.0.0"
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
    api(libs.kotlinx.coroutines.core)
    // Plugin APKs must not bundle their own Compose: two Compose runtimes in one process crash.
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
}
