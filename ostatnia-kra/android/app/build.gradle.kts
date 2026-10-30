plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "pl.ostatniakra"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.ostatniakra"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Same fixed sideloading key as Łagodna Zamieć, so updates install over the previous build.
    signingConfigs {
        create("app") {
            storeFile = file("lz.keystore")
            storeType = "pkcs12"
            storePassword = "lagodnazamiec"
            keyAlias = "lz"
            keyPassword = "lagodnazamiec"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
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
    implementation("androidx.webkit:webkit:1.11.0")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
