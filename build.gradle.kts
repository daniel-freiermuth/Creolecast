// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

// Formatting: ktlint via Spotless (the dedicated ktlint plugins don't support AGP 9's
// built-in Kotlin). Style comes from .editorconfig. `spotlessApply` fixes, `spotlessCheck` verifies.
spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
