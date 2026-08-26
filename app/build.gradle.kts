plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.crimson.pixelshade"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.crimson.pixelshade"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Built/staged by GitHub Actions from upstream ShizukuPlus-API.
    implementation(fileTree("libs") { include("*.aar") })

    debugImplementation(libs.androidx.compose.ui.tooling)
}
