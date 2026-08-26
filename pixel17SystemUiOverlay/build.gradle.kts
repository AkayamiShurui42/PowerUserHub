plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.poweruserhub.pixel17.systemui.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poweruserhub.pixel17.systemui.overlay"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
