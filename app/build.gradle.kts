plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.kotlinSerialization)
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
  namespace = "app.navmaster.truck"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.navmaster.truck"
    minSdk = 29
    targetSdk = 36
    versionCode = buildNumber
    versionName = "0.1.$buildNumber"
    vectorDrawables { useSupportLibrary = true }
    // MapLibre + Valhalla are native: tablets are arm64, the CI emulator is x86_64
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  signingConfigs {
    // Same key on every CI build, so a new APK installs over the previous one on the tablet
    create("ci") {
      storeFile = file("../keystore/navmaster-ci.jks")
      storePassword = "navmaster"
      keyAlias = "navmaster"
      keyPassword = "navmaster"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("ci")
    }
    debug { signingConfig = signingConfigs.getByName("ci") }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    jniLibs { useLegacyPackaging = true }
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugar)

  implementation(libs.core.ktx)
  implementation(libs.activity.compose)
  implementation(libs.lifecycle.runtime)
  implementation(libs.lifecycle.viewmodel)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.coroutines)
  implementation(libs.serialization.json)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons)

  // navigation core (guidance, rerouting, voice) and its MapLibre map
  implementation(libs.ferrostar.core)
  implementation(libs.ferrostar.ui.compose)
  implementation(libs.ferrostar.ui.maplibre)
  implementation(libs.ferrostar.ui.formatters)
  implementation(libs.maplibre.compose)
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

  // offline routing on the tablet
  implementation(libs.valhalla.mobile)
  implementation(libs.valhalla.models)
  implementation(libs.valhalla.models.config)

  implementation(platform(libs.okhttp.bom))
  implementation(libs.okhttp)
}
