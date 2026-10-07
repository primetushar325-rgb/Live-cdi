plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin)
}

/**
 * Optional release signing.
 *
 * The APK is always produced (and therefore always installable). When the four
 * MIL_* environment variables point at a real keystore the release build is
 * signed with it, otherwise the release build falls back to the debug signing
 * config so that GitHub Actions artifacts remain installable.
 */
val releaseKeystore: String? = System.getenv("MIL_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val hasReleaseKeystore: Boolean = releaseKeystore != null && file(releaseKeystore!!).exists()

android {
    namespace = "com.mihad.live"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mihad.live"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("mihadRelease") {
                storeFile = file(releaseKeystore!!)
                storePassword = System.getenv("MIL_KEYSTORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("MIL_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("MIL_KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "SAFE_DIAGNOSTICS", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "SAFE_DIAGNOSTICS", "false")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("mihadRelease")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    // Streaming core (vendored RootEncoder)
    implementation(project(":library"))
    implementation(project(":encoder"))
    implementation(project(":rtmp"))
    implementation(project(":common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
