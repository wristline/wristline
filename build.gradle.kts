// Declaring the Kotlin plugins here (apply false) puts KGP 2.4.x on the shared build classpath,
// so AGP's built-in Kotlin support uses it instead of the older KGP AGP depends on.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
