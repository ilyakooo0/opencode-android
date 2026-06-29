plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

/**
 * Monotonically increasing versionCode: the git commit count on HEAD, so each release
 * ships a strictly higher code (never a "downgrade" block) without manual bookkeeping.
 * Falls back to 1 when git or the history isn't available (e.g. a plain source export).
 */
val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "soy.iko.opencode"
    compileSdk = 35

    defaultConfig {
        applicationId = "soy.iko.opencode"
        minSdk = 26
        targetSdk = 35
        // Monotonically increasing across builds: derived from the git commit count so
        // each release ships a strictly higher versionCode without manual bookkeeping
        // (and never triggers a "downgrade" install block). Falls back to 1 when the
        // .git history isn't available (e.g. a plain source export).
        versionCode = gitCommitCount
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release builds are signed when keystore credentials are supplied via env vars
    // (so CI can publish an installable APK). When the env vars are absent, the
    // release build falls back to unsigned — same behavior as before — so local
    // builds keep working without any keystore setup.
    val releaseSigning = providers.environmentVariable("OPENCODE_STORE_FILE").orNull?.let { path ->
        signingConfigs.create("release") {
            storeFile = file(path)
            storePassword = providers.environmentVariable("OPENCODE_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("OPENCODE_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("OPENCODE_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            releaseSigning?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Use the kotlin { compilerOptions {} } block (the kotlinOptions DSL is deprecated
    // in favor of the type-safe extension exposed by the Kotlin Gradle plugin).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Fail CI on real errors. Warnings are reported but don't break the build so
        // minor/style issues don't gate development.
        abortOnError = true
        // Dependency upgrades are tracked by Renovate, so don't lint for them here.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Return default values for Android framework stubs (e.g. Log.d, Context.getString)
            // instead of throwing "not mocked" exceptions, so JVM unit tests that brush
            // against Android APIs don't need Robolectric.
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.coil.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

detekt {
    buildUponDefaultConfig = true
    // Carry forward the current baseline so CI only fails on *new* findings;
    // existing issues are grandfathered rather than blocking the migration.
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/config/detekt/detekt.yml")
}
