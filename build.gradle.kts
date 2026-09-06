plugins {
    // AGP 9 provides built-in Kotlin support; no standalone kotlin-android plugin.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
