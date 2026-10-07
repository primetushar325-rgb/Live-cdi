plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.jetbrains.kotlin)
}

android {
  namespace = "com.pedro.rtmp"
  compileSdk = 36
  defaultConfig { minSdk = 24; lint.targetSdk = 36 }
  buildTypes { release { isMinifyEnabled = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { jvmToolchain(17) }
}

dependencies {
  implementation(libs.kotlinx.coroutines.android)
  api(project(":common"))
}
