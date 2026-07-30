import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.mehdiacho.yap"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "app.greenforrest.yap"
    minSdk = 24
    targetSdk = 36
    // CI (GitHub Actions) passes a monotonically increasing code; local builds use the literal.
    versionCode = System.getenv("YAP_VERSION_CODE")?.toIntOrNull() ?: 1
    versionName = System.getenv("YAP_VERSION_NAME") ?: "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      // Upload key for Play App Signing. Credentials live in keystore.properties at the
      // repo root (git-ignored — BACK IT UP together with upload-keystore.jks). Env vars
      // override for CI.
      val props = Properties()
      val propsFile = rootProject.file("keystore.properties")
      if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }
      storeFile = file(System.getenv("KEYSTORE_PATH")
        ?: "${rootDir}/${props.getProperty("storeFile") ?: "upload-keystore.jks"}")
      storePassword = System.getenv("STORE_PASSWORD") ?: props.getProperty("storePassword")
      keyAlias = System.getenv("KEY_ALIAS") ?: props.getProperty("keyAlias") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD") ?: props.getProperty("keyPassword")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Uses AGP's default auto-generated debug keystore (~/.android/debug.keystore).
      //
      // Separate applicationId so a locally-built copy installs ALONGSIDE the Play build
      // instead of replacing it. This is not cosmetic: a debug-signed APK cannot overwrite
      // a Play-signed one (signature mismatch), so sharing the id would force an uninstall
      // first — and uninstalling wipes app-private storage, which is where recordings live
      // until they upload. That is exactly how a 31-minute recording was nearly lost.
      //
      // Same Firebase project either way: `app.greenforrest.yap.debug` is registered as a
      // second Android app in yap-mehdiacho, so it reads the same Firestore, the same
      // Storage bucket, the same Functions and the same user accounts. Only the package
      // and the signing certificate differ. See docs/LOCAL_BUILDS.md.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-dev"
      isDebuggable = true
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
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices {
  missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
}


// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  // Material Components — provides the Theme.Material3.* window themes used as the Activity theme
  // (matches how edge-to-edge apps like Tachiyomi/Yokai get a truly transparent MIUI nav bar).
  implementation(libs.google.android.material)
  // App lock (Privacy & data → biometric gate). Requires MainActivity : FragmentActivity.
  implementation(libs.androidx.biometric)
  // Play Billing scaffold — flows work once products exist in Play Console.
  implementation(libs.billing.ktx)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation("com.google.firebase:firebase-auth")
  implementation("com.google.firebase:firebase-functions")
  implementation("com.google.firebase:firebase-storage")
  implementation("com.google.firebase:firebase-firestore")
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.26.0")
  implementation("androidx.credentials:credentials:1.3.0")
  implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
  implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
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
  "ksp"(libs.moshi.kotlin.codegen)
}
