plugins {
    // AGP 9 has built-in Kotlin support: org.jetbrains.kotlin.android is NOT applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.claudeforwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.claudeforwatch"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "PERSONAL_MODE", "false")
    }

    // `store`: the only flavor that may be distributed (API key sign-in only).
    // `personal`: unlocks Claude-account sign-in (docs/PROTOCOL.md §1, §1.2). Never publish it.
    flavorDimensions += "distribution"
    productFlavors {
        create("store") {
            dimension = "distribution"
            buildConfigField("boolean", "PERSONAL_MODE", "false")
        }
        create("personal") {
            dimension = "distribution"
            applicationIdSuffix = ".personal"
            versionNameSuffix = "-personal"
            buildConfigField("boolean", "PERSONAL_MODE", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.input)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material3)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.complications.data.source.ktx)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.guava)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)
}
