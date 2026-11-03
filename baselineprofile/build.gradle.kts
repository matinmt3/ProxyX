plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "com.aistudio.proxyx.baselineprofile"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  targetProjectPath = ":app"
  experimentalProperties["android.experimental.self-instrumenting"] = true

  defaultConfig {
    minSdk = 28
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
}

dependencies {
  implementation(libs.androidx.benchmark.macro.junit4)
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.test.runner)
  implementation(libs.androidx.test.uiautomator)
}
