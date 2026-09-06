import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. CI or a developer passes the keystore through environment
// variables (or -P properties); without one, the release build is signed with
// the debug key so it still installs, and the build prints that it did so.
fun signingProperty(env: String, prop: String): String? =
    System.getenv(env) ?: (project.findProperty(prop) as? String)

android {
    namespace = "app.novacalc"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.novacalc"
        minSdk = 26
        targetSdk = 36
        // versionCode = major*10000 + minor*100 + patch, derived from versionName.
        versionName = "1.0.0"
        versionCode = versionName!!.split(".").let { (major, minor, patch) ->
            major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = signingProperty("NOVACALC_KEYSTORE", "novacalcKeystore")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = signingProperty("NOVACALC_KEYSTORE_PASSWORD", "novacalcKeystorePassword")
                keyAlias = signingProperty("NOVACALC_KEY_ALIAS", "novacalcKeyAlias") ?: "novacalc"
                keyPassword = signingProperty("NOVACALC_KEY_PASSWORD", "novacalcKeyPassword") ?: storePassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else {
                logger.warn("NovaCalc: no release keystore configured, signing release with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        // Single-language app: keep library translations out of the APK.
        localeFilters += "en"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
