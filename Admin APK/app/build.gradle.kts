plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pln.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pln.admin"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Obrolan native — daftar percakapan & gelembung pesan
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
