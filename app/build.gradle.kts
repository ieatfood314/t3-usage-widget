import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: ~/.config/t3usage/keystore.properties (storeFile/storePassword/keyAlias/keyPassword).
val keystoreProps = Properties().apply {
    val file = file(System.getProperty("user.home") + "/.config/t3usage/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "ca.heeney.t3usage"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.heeney.t3usage"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}
