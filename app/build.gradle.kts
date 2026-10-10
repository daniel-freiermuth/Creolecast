import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// Release signing secrets live in keystore.properties, a git-ignored file at the repo
// root (see .gitignore) - never in gradle.properties, which is tracked in version
// control. Populate it locally (or via CI secrets) with:
//   MYAPP_RELEASE_STORE_FILE=/path/to/release.keystore
//   MYAPP_RELEASE_STORE_PASSWORD=...
//   MYAPP_RELEASE_KEY_ALIAS=...
//   MYAPP_RELEASE_KEY_PASSWORD=...
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.creolecast.app"
    compileSdk = 37

    signingConfigs {
        create("release") {
            val storeFileProp = keystoreProperties.getProperty("MYAPP_RELEASE_STORE_FILE")
            if (storeFileProp != null) {
                storeFile = file(storeFileProp)
                storePassword = keystoreProperties.getProperty("MYAPP_RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("MYAPP_RELEASE_KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("MYAPP_RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.creolecast.app"
        minSdk = 34
        targetSdk = 35
        versionCode = 15
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.getProperty("MYAPP_RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Deterministic builds — strip timestamps and ordering variance from the APK
    // so the same source always produces a byte-identical artifact.
    lint {
        // Every warning fails the build, including checks that are off by default, in test
        // sources and in dependencies. Pre-existing findings live in lint-baseline.xml.
        abortOnError = true
        warningsAsErrors = true
        checkAllWarnings = true
        checkDependencies = true
        checkTestSources = true
        checkReleaseBuilds = true
        explainIssues = true
        baseline = file("lint-baseline.xml")
        sarifReport = true
        disable += "MissingTranslation"
        // "A newer version is available" would fail CI whenever an upstream releases,
        // independent of the change under test. Dependabot proposes updates instead.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions {
        // Every Kotlin compiler warning (deprecations, redundant checks, ...) fails the build.
        allWarningsAsErrors = true
    }
}

// Static analysis: every detekt rule (allRules), with type resolution via detektMain /
// detektTest. Deviations live in config/detekt/detekt.yml; pre-existing findings are
// grandfathered in the baseline files, so only new code has to be clean.
detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    parallel = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-websockets:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-logging:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
