import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release imzası yalnız GitHub Secrets verilən zaman aktiv olur (workflow "Release (Play AAB)")
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.nibrascode.pdf"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nibrascode.pdf"
        minSdk = 26
        targetSdk = 36
        // İlk Play buraxılışı. Hər yeni Play yükləməsində versionCode-u 1 artır
        versionCode = 2
        versionName = "1.0.1"
        // Telefonlar üçün kifayətdir; APK həcmini kiçildir (OCR native kitabxanaları)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // emulyator testləri üçün
            ndk { abiFilters += listOf("x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        generateLocaleConfig = false
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // PDF yazmaq, səhifə əməliyyatları və mətn axtarışı (Apache 2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Cihazda OCR (Tesseract 5, Apache 2.0) — dil faylları ilk istifadədə yüklənir
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")
}
