import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing values come from ~/.gradle/gradle.properties (wristline.*).
// When any of them is missing, release builds fall back to the debug key.
val releaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { providers.gradleProperty("wristline.$it").orNull }
    .takeIf { values -> values.values.all { it != null } }

android {
    namespace = "dev.wristline.watch"
    // compose-bom 2026.09.00, wear compose 1.7.0 and okhttp 5.5.0 require compileSdk >= 37.
    // compileSdk only affects which APIs are visible at build time; targetSdk stays 36 (Wear OS 6).
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.wristline.watch"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile")!!)
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Ship only the locales the app is translated into (matches res/xml/locales_config.xml);
    // drops the other ~80 locales bundled by AndroidX libraries.
    androidResources {
        localeFilters += listOf("en", "ko")
    }

    lint {
        error += "MissingTranslation"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.input)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
