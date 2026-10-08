plugins {
    // AGP 9 has built-in Kotlin support: org.jetbrains.kotlin.android is NOT applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Phone companion: signs in / takes an API key on the phone and sends it to the watch over the
// Wear Data Layer. The Data Layer only routes between apps with the SAME applicationId and signing
// key, so the flavors and applicationIds mirror :wear exactly.
android {
    namespace = "com.claudeforwatch.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.claudeforwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "PERSONAL_MODE", "false")
    }

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
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.play.services.wearable)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
