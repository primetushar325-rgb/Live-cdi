// Mihad Live - root build file.
// The streaming core (RootEncoder, Apache-2.0) is vendored under vendor/re-*.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin) apply false
}
