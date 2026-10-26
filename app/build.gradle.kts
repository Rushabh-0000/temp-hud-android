plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.tempoverlay.mangohud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tempoverlay.mangohud"
        minSdk = 30
        targetSdk = 36
        versionCode = (providers.gradleProperty("VERSION_CODE").orNull ?: "18").toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orNull ?: "0.7.7"
    }

    buildFeatures {
        buildConfig = false
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    packaging {
        resources {
            // Runtime code/resources are handled by R8 and resource shrinking below.
            // Remove only metadata that is never read by the app at runtime.
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.version",
                "META-INF/version-control-info.textproto",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // Manifest components are kept by Android's generated R8 rules.
            // Avoid broad custom keep rules so R8 can remove unused library code.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseStorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

