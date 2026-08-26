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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // Keep the normal Shizuku provider, but use upstream Shizuku+ API AARs
    // built from source by GitHub Actions instead of relying on JitPack.
    implementation(libs.shizuku.provider) {
        exclude(group = "dev.rikka.shizuku", module = "api")
        exclude(group = "dev.rikka.shizuku", module = "shared")
        exclude(group = "dev.rikka.shizuku", module = "aidl")
    }
    implementation(files("libs/shizuku-plus-aidl-release.aar"))
    implementation(files("libs/shizuku-plus-shared-release.aar"))
    implementation(files("libs/shizuku-plus-api-release.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
}
