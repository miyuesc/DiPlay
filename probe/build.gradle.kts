plugins {
    alias(libs.plugins.android.application)
}

// No dependency on common/shared: probing must not load authentication or vendor IPC.
android {
    namespace = "com.shihab.diplay.probe"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.shihab.diplay.c11probe"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
