import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
  val localPropertiesFile = rootProject.file("local.properties")
  if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use(::load)
  }
}

val keystoreProperties = Properties().apply {
  val keystorePropertiesFile = rootProject.file("keystore.properties")
  if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use(::load)
  }
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun releaseProperty(name: String, environmentName: String): String? =
  providers.gradleProperty("plexWear.$name").orNull
    ?: localProperties.getProperty("plexWear.$name")
    ?: keystoreProperties.getProperty(name)
    ?: providers.environmentVariable(environmentName).orNull

fun debugSigningProperty(name: String): String? =
  providers.gradleProperty("androidDebugSigning.$name").orNull
    ?: localProperties.getProperty("androidDebugSigning.$name")

fun localBooleanProperty(name: String): Boolean {
  val value = providers.gradleProperty(name).orNull
    ?: localProperties.getProperty(name)
  return value.equals("true", ignoreCase = true)
}

val debugStoreFile = debugSigningProperty("storeFile")
val debugStorePassword = debugSigningProperty("storePassword") ?: "android"
val debugKeyAlias = debugSigningProperty("keyAlias") ?: "androiddebugkey"
val debugKeyPassword = debugSigningProperty("keyPassword") ?: debugStorePassword
val hasStableDebugSigning = !debugStoreFile.isNullOrBlank()
val debugSignRelease = localBooleanProperty("plexWear.debugSignRelease")

val releaseStoreFile = releaseProperty("storeFile", "PLEX_WEAR_KEYSTORE_FILE")
val releaseStorePassword = releaseProperty("storePassword", "PLEX_WEAR_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseProperty("keyAlias", "PLEX_WEAR_KEY_ALIAS")
val releaseKeyPassword = releaseProperty("keyPassword", "PLEX_WEAR_KEY_PASSWORD")
val hasReleaseSigning = listOf(
  releaseStoreFile,
  releaseStorePassword,
  releaseKeyAlias,
  releaseKeyPassword,
).all { !it.isNullOrBlank() }

fun configuredFile(path: String): File {
  val home = System.getProperty("user.home")
  val expanded = when {
    path == "~" -> home
    path.startsWith("~/") -> "$home/${path.removePrefix("~/")}"
    path.startsWith("\$HOME/") -> "$home/${path.removePrefix("\$HOME/")}"
    path.startsWith("\${user.home}/") -> "$home/${path.removePrefix("\${user.home}/")}"
    else -> path
  }
  return file(expanded)
}

android {
    namespace = "com.nedrichards.plexwear"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nedrichards.plexwear"
        minSdk = 26
        targetSdk = 36
        versionCode = (
          providers.gradleProperty("plexWear.versionCode").orNull
            ?: localProperties.getProperty("plexWear.versionCode")
            ?: "1"
          ).toInt()
        versionName = providers.gradleProperty("plexWear.versionName").orNull
          ?: localProperties.getProperty("plexWear.versionName")
          ?: "1.0"
    }

    signingConfigs {
        if (hasStableDebugSigning) {
            create("stableDebug") {
                storeFile = configuredFile(debugStoreFile!!)
                storePassword = debugStorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = configuredFile(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableDebugSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
            buildConfigField("String", "DEBUG_PLEX_SERVER_URL", (localProperties.getProperty("plex.serverUrl") ?: "").asBuildConfigString())
            buildConfigField("String", "DEBUG_PLEX_TOKEN", (localProperties.getProperty("plex.token") ?: "").asBuildConfigString())
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEBUG_PLEX_SERVER_URL", "\"\"")
            buildConfigField("String", "DEBUG_PLEX_TOKEN", "\"\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                hasReleaseSigning -> signingConfigs.getByName("release")
                debugSignRelease && hasStableDebugSigning -> signingConfigs.getByName("stableDebug")
                debugSignRelease -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.wear.compose.foundation)
  implementation(libs.androidx.wear.compose.material3)
  implementation(libs.androidx.wear.compose.navigation)
  implementation(libs.androidx.wear.input)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
