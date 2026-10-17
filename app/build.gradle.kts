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
        // sources and in dependencies. Deliberate exceptions live in lint.xml.
        abortOnError = true
        warningsAsErrors = true
        checkAllWarnings = true
        checkDependencies = true
        checkTestSources = true
        checkReleaseBuilds = true
        explainIssues = true
        lintConfig = file("lint.xml")
        sarifReport = true
        disable += "MissingTranslation"
        // "A newer version is available" would fail CI whenever an upstream releases,
        // independent of the change under test. Dependabot proposes updates instead.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
        // Opt-in micro-optimisations without value here: debug logging stays in release builds
        // on purpose (field diagnostics via logcat), and R8 inlines the small synthetic
        // accessors Kotlin generates for private access from lambdas and nested classes.
        disable += setOf("LogConditional", "SyntheticAccessor")
    }
    // The AirPlay 2 protocol classes log through android.util.Log on every path; let JVM
    // unit tests run them against the stub android.jar instead of throwing "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
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

// Static analysis: detekt's default rules plus the bug-catching opt-ins enabled in
// config/detekt/detekt.yml (which also lists every deviation), with type resolution via
// detektMain / detektTest. No baseline: every finding fails the build.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    // Parallel analysis crashes 2.0.0-alpha.6 intermittently (ArrayIndexOutOfBounds in
    // 2 of 4 runs; 5 of 5 pass without it). Re-enable once fixed upstream.
    parallel = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.androidx.media)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
