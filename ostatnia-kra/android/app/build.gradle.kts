plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "pl.ostatniakra"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.ostatniakra"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // Same fixed sideloading key as Łagodna Zamieć, so test builds install over the previous one.
        create("app") {
            storeFile = file("lz.keystore")
            storeType = "pkcs12"
            storePassword = "lagodnazamiec"
            keyAlias = "lz"
            keyPassword = "lagodnazamiec"
        }
        // The Google Play upload key: never in the repo, the workflow decodes it from GitHub secrets.
        val releaseStore = System.getenv("KRA_RELEASE_STORE")
        if (releaseStore != null && file(releaseStore).exists()) {
            create("play") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("KRA_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("KRA_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("KRA_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("play") ?: signingConfigs.getByName("app")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("task")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // 1.4+ ships 16 KB page aligned native code (required by Google Play)
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")
}
