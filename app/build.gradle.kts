import java.util.Properties

buildscript {
  repositories {
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
    google()
  }
  dependencies {
    classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.5")
  }
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.androidx.baselineprofile)
}

apply(plugin = "com.google.devtools.ksp")

extensions.configure<com.google.devtools.ksp.gradle.KspExtension> {
  arg("room.schemaLocation", file("schemas").absolutePath)
  arg("room.incremental", "true")
}

val externalSigningFile = providers.gradleProperty("proxyx.signingConfigFile").orNull
val signingPropertiesFile = externalSigningFile?.let { rootProject.file(it) }
  ?: rootProject.file("keystore.properties")
val signingProperties = Properties().apply {
  check(externalSigningFile == null || signingPropertiesFile.isFile) {
    "Release signing configuration file does not exist."
  }
  if (signingPropertiesFile.exists()) signingPropertiesFile.inputStream().use(::load)
}
fun signingValue(environmentName: String, propertyName: String): String? =
  System.getenv(environmentName)?.takeIf(String::isNotBlank)
    ?: signingProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseKeystorePath = signingValue("KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("STORE_PASSWORD", "storePassword")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
val hasReleaseSigning = listOf(
  releaseKeystorePath,
  releaseStorePassword,
  releaseKeyPassword,
  releaseKeyAlias
).all { !it.isNullOrBlank() }
check(providers.gradleProperty("proxyx.requireReleaseSigning").orNull != "true" || hasReleaseSigning) {
  "A complete release signing configuration is required for this build."
}
val localProperties = Properties().apply {
  val propertiesFile = rootProject.file("local.properties")
  if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}
val telegramProperties = providers.gradleProperty("proxyx.telegramConfigFile").orNull?.let { path ->
  Properties().apply {
    val propertiesFile = rootProject.file(path)
    check(propertiesFile.isFile) { "Telegram configuration file does not exist." }
    propertiesFile.inputStream().use(::load)
  }
} ?: localProperties
val auditInstall = providers.gradleProperty("proxyx.auditInstall").orNull == "true"
check(providers.gradleProperty("proxyx.requireReleaseSigning").orNull != "true" || !auditInstall) {
  "An audit build cannot satisfy publication signing requirements."
}
val telegramApiId = (System.getenv("PROXYX_TELEGRAM_API_ID")
  ?: telegramProperties.getProperty("telegram.apiId", "0")).trim()
val telegramApiHash = (System.getenv("PROXYX_TELEGRAM_API_HASH")
  ?: telegramProperties.getProperty("telegram.apiHash", "")).trim()
val parsedTelegramApiId = telegramApiId.toIntOrNull()?.takeIf { it > 0 } ?: 0
val telegramCredentialsPresent = parsedTelegramApiId > 0 || telegramApiHash.isNotEmpty()
check(!telegramCredentialsPresent || (parsedTelegramApiId > 0 && telegramApiHash.matches(Regex("[0-9a-fA-F]{32}")))) {
  "Set a positive telegram.apiId and its 32-character telegram.apiHash in local.properties."
}
val escapedTelegramApiHash = telegramApiHash
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")
val sourceCatalogUrl = (System.getenv("PROXYX_SOURCE_CATALOG_URL")
  ?: localProperties.getProperty("sourceCatalog.url", "")).trim()
val sourceCatalogPublicKey = (System.getenv("PROXYX_SOURCE_CATALOG_PUBLIC_KEY")
  ?: localProperties.getProperty("sourceCatalog.publicKey", "")).trim()
val releaseManifestUrl = (System.getenv("PROXYX_RELEASE_MANIFEST_URL")
  ?: localProperties.getProperty("releaseManifest.url", "")).trim()
val releasePublicKey = (System.getenv("PROXYX_RELEASE_PUBLIC_KEY")
  ?: localProperties.getProperty("releaseManifest.publicKey", "")).trim()
fun escapedBuildConfigString(value: String): String = value
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = if (auditInstall) "com.aistudio.proxyx.vbnmkw.audit" else "com.aistudio.proxyx.vbnmkw"
    manifestPlaceholders["appLabel"] = if (auditInstall) "ProxyX QA" else "@string/app_name"
    minSdk = 24
    targetSdk = 36
    versionCode = 81
    versionName = "8.0.0-rc5"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
    buildConfigField("int", "TELEGRAM_API_ID", parsedTelegramApiId.toString())
    buildConfigField("String", "RELEASE_MANIFEST_URL", "\"${escapedBuildConfigString(releaseManifestUrl)}\"")
    buildConfigField("String", "RELEASE_PUBLIC_KEY", "\"${escapedBuildConfigString(releasePublicKey)}\"")
    buildConfigField("String", "TELEGRAM_API_HASH", "\"$escapedTelegramApiHash\"")
    buildConfigField(
      "String",
      "SOURCE_CATALOG_URL",
      "\"${escapedBuildConfigString(sourceCatalogUrl)}\""
    )
    buildConfigField(
      "String",
      "SOURCE_CATALOG_PUBLIC_KEY",
      "\"${escapedBuildConfigString(sourceCatalogPublicKey)}\""
    )
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = if (externalSigningFile != null) {
          signingPropertiesFile.parentFile.resolve(requireNotNull(releaseKeystorePath)).canonicalFile
        } else file(requireNotNull(releaseKeystorePath))
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      signingConfig = if (auditInstall) signingConfigs.getByName("debug")
        else signingConfigs.findByName("release")
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
  packaging {
    jniLibs {
      // Keep the universal APK compact while retaining all three TDLib ABIs.
      useLegacyPackaging = true
    }
  }
  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
}

baselineProfile {
  filter {
    include("com.example.**")
    include("org.drinkless.tdlib.**")
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.paging)
  implementation(libs.androidx.paging.runtime)
  implementation(libs.androidx.paging.compose)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.zxing.core)
  implementation(libs.androidx.webkit)

  testImplementation(libs.androidx.core)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)

  baselineProfile(project(":baselineprofile"))

  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.codegen)
}
