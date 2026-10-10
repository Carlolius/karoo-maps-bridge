plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.carlolius.karoomapsbridge.karoo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carlolius.karoomapsbridge.karoo"
        minSdk = 26 // Karoo 2 runs Android 8.1 (API 27)
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.0"
    }

    // Release signing comes from the environment (see build.sh --release); never from the repo.
    signingConfigs {
        System.getenv("RELEASE_KEYSTORE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.hammerhead.karoo.ext)
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json (Android's is a stub in unit tests)
}
