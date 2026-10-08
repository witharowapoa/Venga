plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each GitHub build gets a higher version number so a new APK installs over the old one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "au.nick.venga"
    compileSdk = 34

    defaultConfig {
        applicationId = "au.nick.venga"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed personal signing key, so updates install over the existing app and keep your progress.
    // Keep the repository private.
    signingConfigs {
        create("personal") {
            storeFile = file("venga.keystore")
            storePassword = "vengaMadrid2026"
            keyAlias = "venga"
            keyPassword = "vengaMadrid2026"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
