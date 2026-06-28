plugins {
    // Latest (canary) toolchain — matches Android Studio's chosen versions and
    // compiles against the android-37 platform. Canary moves fast; if you want a
    // lower-maintenance, set-and-forget baseline, pin to AGP 8.x / Kotlin 2.0.x /
    // compileSdk 36 instead.
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
