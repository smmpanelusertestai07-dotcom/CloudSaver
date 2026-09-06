import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.cloudsaver"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.cloudsaver"
        minSdk = 29
        targetSdk = 36
        // Semantic version, and a versionCode derived from it rather than
        // hand-incremented: major*10000 + minor*100 + patch. It is always
        // monotonic, it never collides across branches, and it leaves room
        // for 99 minors and 99 patches without ever needing a reset.
        //   3.0.0 -> 30000
        versionName = "9.13.0"
        versionCode = versionName!!.split(".").let { (major, minor, patch) ->
            major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Tamper evidence: CI passes -PsigningCertSha256=<sha256 of the release
        // signing cert> before building; empty (dev builds) disables the check.
        val certSha = (project.findProperty("signingCertSha256") as? String).orEmpty()
        buildConfigField("String", "EXPECTED_CERT_SHA256", "\"$certSha\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        // The app ships one language on purpose (13.F), so there is nothing to
        // translate and nothing to fall out of sync; these stay warnings rather
        // than gates in case a stray qualifier folder is ever added.
        warning += setOf("MissingTranslation", "ExtraTranslation")
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        // The app ships one language on purpose (13.F). The libraries under
        // it ship ninety; this keeps their translations out of the APK, so
        // the file says what the About page says.
        localeFilters += "en"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // Settings rows each carry a leading glyph. R8 keeps only the icons that
    // are actually referenced, so the shipped dex grows by those alone.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.biometric)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.muxer)
    implementation(libs.media3.common)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    // Instrumented end-to-end suite: runs on a real emulator in CI.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Many of this project's rules are source-text rules: they open repository
// files and assert properties of the words in them - that no chip repeats
// another, that the About card names every permission the manifest holds,
// that the release matrix's counts match the tree, that the CI workflow never
// publishes a private key.
//
// Gradle knew about none of those files. Editing strings.xml and re-running
// the tests reported `testDebugUnitTest UP-TO-DATE` and replayed the previous
// verdict, so a copy rule could pass over text it had never read - which is
// exactly how the first proof of two new rules "passed" while the fault sat
// in the file. CI was only ever safe by accident, because it checks out fresh
// and has no cache to reuse.
//
// Declared here so a local run tells the truth. If a rule reads a file as
// text, that file belongs in this list.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file(".github/workflows/build.yml"),
        rootProject.file("RELEASE_MATRIX.md"),
        file("src/main/AndroidManifest.xml"),
        file("src/main/res/values/strings.xml")
    )
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("sourceTextRuleFiles")
    inputs.dir(file("src/main/kotlin"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("sourceTextRuleSources")
}

// CI helper: prints the app version name.
tasks.register("printVersionName") {
    val versionName = android.defaultConfig.versionName
    doLast { println(versionName) }
}
