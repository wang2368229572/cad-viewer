plugins {
    id("com.android.application")
}

android {
    namespace = "app.cadlook"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.cadlook"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
