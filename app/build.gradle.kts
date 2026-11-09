plugins { id("com.android.application") }

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull

android {
    namespace = "com.likefolder.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.likefolder.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Local builds can remain unsigned. Release CI supplies the persistent PKCS12 key.
    if (!releaseKeystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storeType = "PKCS12"
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                    ?.takeIf { it.isNotBlank() } ?: error("ANDROID_KEYSTORE_PASSWORD is required for release signing")
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                    ?.takeIf { it.isNotBlank() } ?: error("ANDROID_KEY_ALIAS is required for release signing")
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
                    ?.takeIf { it.isNotBlank() } ?: error("ANDROID_KEY_PASSWORD is required for release signing")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
