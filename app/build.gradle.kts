plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version is overridable from the release workflow via
// `-PversionName=... -PversionCode=...`; falls back to sensible defaults locally.
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// A release keystore is supplied by CI (decoded from a secret) via env vars.
// When it's absent — local builds, or CI before signing is configured — the
// release build falls back to the debug signing config so the APK is still
// installable, rather than producing an uninstallable unsigned APK.
val releaseStorePath: String? = System.getenv("OPENCODE_STORE_FILE")
val hasReleaseKeystore = releaseStorePath != null && file(releaseStorePath).exists()

android {
    namespace = "soy.iko.opencode"
    compileSdk = 35

    defaultConfig {
        applicationId = "soy.iko.opencode"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = System.getenv("OPENCODE_STORE_PASSWORD")
                keyAlias = System.getenv("OPENCODE_KEY_ALIAS")
                keyPassword = System.getenv("OPENCODE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
