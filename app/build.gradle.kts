plugins { id("com.android.application") }

android {
    namespace = "com.likefolder.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.likefolder.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.10.0")
    testImplementation("junit:junit:4.13.2")
}
