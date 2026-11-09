import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.google.services)
  alias(libs.plugins.chaquopy)
}

android {
  namespace = "com.example"
  compileSdk = 37

  defaultConfig {
    applicationId = "ru.FsFqX.Muzix"
    minSdk = 24
    targetSdk = 36
    versionCode = 53
    versionName = "1.1.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  flavorDimensions += "abi"
  productFlavors {
    create("arm32") {
      dimension = "abi"
      ndk {
        abiFilters.clear()
        abiFilters.add("armeabi-v7a")
      }
    }
    create("arm64") {
      dimension = "abi"
      ndk {
        abiFilters.clear()
        abiFilters.add("arm64-v8a")
      }
    }
    create("appleArm64") {
      dimension = "abi"
      applicationId = "com.apple.android.music"
      ndk {
        abiFilters.clear()
        abiFilters.add("arm64-v8a")
      }
    }
  }

  signingConfigs {
    create("release") {
      val ks = file("${rootDir}/release.keystore")
      if (ks.exists()) {
        storeFile = ks
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "muzix123"
        keyAlias = System.getenv("KEY_ALIAS") ?: "muzix"
        keyPassword = System.getenv("KEY_PASSWORD") ?: "muzix123"
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (file("${rootDir}/release.keystore").exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      isMinifyEnabled = false
      isShrinkResources = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }

  androidResources {
    localeFilters.addAll(listOf("ru", "en"))
  }

  packaging {
    resources {
      excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/DEPENDENCIES",
        "/META-INF/LICENSE*",
        "/META-INF/NOTICE*",
        "/META-INF/*.version"
      )
    }
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

googleServices {
  missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
}


dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.graphics.shapes)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.media)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation("androidx.profileinstaller:profileinstaller:1.4.1")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

chaquopy {
  defaultConfig {
    pip {
      options("--find-links", "$projectDir/wheels", "--no-index")
      install("yt-dlp")
      install("mutagen")
    }
  }
  productFlavors {
    getByName("arm32") {
      version = "3.11"
      buildPython("C:/Users/ezik0/AppData/Local/Programs/Python/Python311/python.exe")
    }
    getByName("arm64") {
      version = "3.12"
      buildPython("C:/Users/ezik0/AppData/Local/Programs/Python/Python312/python.exe")
    }
    getByName("appleArm64") {
      version = "3.12"
      buildPython("C:/Users/ezik0/AppData/Local/Programs/Python/Python312/python.exe")
    }
  }
}


