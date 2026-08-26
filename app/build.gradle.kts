plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.poweruserhub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poweruserhub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)

    // Built from upstream thejaustin/ShizukuPlus-API by GitHub Actions.
    // The Plus provider remains backward compatible with stock Shizuku while exposing
    // the enhanced Overlay/Window Manager bridges when a Shizuku+ server is connected.
    implementation(files("libs/shizuku-plus-aidl.aar"))
    implementation(files("libs/shizuku-plus-shared.aar"))
    implementation(files("libs/shizuku-plus-api.aar"))
    implementation(files("libs/shizuku-plus-provider.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
}
