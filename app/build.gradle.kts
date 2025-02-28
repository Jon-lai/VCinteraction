plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.jon.vcinteraction"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jon.vcinteraction"
        minSdk = 24
        //noinspection EditedTargetSdkVersion
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation("androidx.camera:camera-core:1.4.0-alpha01") // or later version
    implementation("androidx.camera:camera-camera2:1.4.0-alpha01") // or later version
    implementation("androidx.camera:camera-lifecycle:1.4.0-alpha01") // or later version
    implementation("androidx.camera:camera-view:1.4.0-alpha01") // or later version
    implementation("androidx.camera:camera-extensions:1.4.0-alpha01") // or later version
    implementation ("androidx.camera:camera-video:1.4.0-alpha01")// or later version
    implementation ("com.squareup.okhttp3:okhttp:4.10.0")

}