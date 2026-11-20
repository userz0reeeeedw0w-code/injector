plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.netscope.core.proxy"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  api(project(":core:shared"))
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  api(libs.netty.all)
  implementation(libs.bouncycastle.bcpkix)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.okhttp)
  testImplementation(libs.junit)
}
