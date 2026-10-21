import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support: org.jetbrains.kotlin.android is NOT applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Phone companion: signs in / takes an API key on the phone and sends it to the watch over the
// Wear Data Layer. The Data Layer only routes between apps with the SAME applicationId and signing
// key, so the flavors and applicationIds mirror :wear exactly.
// Release signing (docs/RELEASING.md): CI passes CFW_KEYSTORE_FILE / CFW_KEYSTORE_PASSWORD /
// CFW_KEY_ALIAS / CFW_KEY_PASSWORD; locally the same keys may live in android/keystore.properties
// (gitignored) as storeFile / storePassword / keyAlias / keyPassword. Without them the release
// build is left unsigned. :wear and :phone must use the SAME key (Wear Data Layer requirement).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun releaseSecret(prop: String, env: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)
val releaseKeystore = releaseSecret("storeFile", "CFW_KEYSTORE_FILE")
val cfwVersionName = providers.gradleProperty("cfw.versionName").get()
val cfwVersionCode = providers.gradleProperty("cfw.versionCode").get().toInt()

android {
    namespace = "com.claudeforwatch.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.claudeforwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = cfwVersionCode * 10 + 1
        versionName = cfwVersionName
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

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = releaseSecret("storePassword", "CFW_KEYSTORE_PASSWORD")
                keyAlias = releaseSecret("keyAlias", "CFW_KEY_ALIAS")
                keyPassword = releaseSecret("keyPassword", "CFW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
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
