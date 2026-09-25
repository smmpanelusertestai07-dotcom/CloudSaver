import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Owner configuration comes from Gradle properties or the environment, never from the source.
// CI passes the GitHub App's client ID (an Actions variable) and the signing certificate's
// SHA-256; a local build without them still compiles and runs, and the app explains what is
// missing instead of failing.
fun config(name: String): String {
    val local = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
    return (project.findProperty(name) as String?)
        ?: System.getenv(name)
        ?: local.getProperty(name)
        ?: ""
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.pocketide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketide"
        minSdk = 29
        targetSdk = 36
        // Above 2.6.0's 260, so 3.0.0 installs over it as an update with the same key.
        versionCode = 300
        versionName = "3.0.0"

        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_APP_CLIENT_ID", quoted(config("POCKETIDE_GITHUB_APP_CLIENT_ID")))
        buildConfigField("String", "GITHUB_APP_SLUG", quoted(config("POCKETIDE_GITHUB_APP_SLUG")))
        buildConfigField("String", "SIGNING_CERT_SHA256", quoted(config("POCKETIDE_SIGNING_CERT_SHA256").lowercase()))
        buildConfigField("String", "RELEASES_REPO", quoted(config("POCKETIDE_RELEASES_REPO").ifEmpty { "smmpanelusertestai07-dotcom/CloudSaver" }))
        buildConfigField("String", "RELEASE_TAG_PREFIX", quoted("pocketide-v"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed in CI with apksigner (v2 + v3), from the POCKETIDE_* secrets.
            signingConfig = null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        // proot and its loader are executed from nativeLibraryDir, the one place Android lets
        // an app run its own binaries (W^X), so the libraries must be extracted at install.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "plugin.properties",
                "about.html",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.webkit)
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.jgit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

tasks.register("printVersionName") {
    val name = android.defaultConfig.versionName
    doLast { println(name) }
}
