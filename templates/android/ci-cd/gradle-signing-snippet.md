# Gradle release-signing snippet (`app/build.gradle.kts`)

Reads the keystore from **env vars** (CI) with a local **`keystore.properties`** fallback (dev
machine). Pair with `release.yml` in this folder. Kotlin DSL.

At the top of the file:
```kotlin
import java.util.Properties   // the `java` accessor otherwise shadows this package in .kts
```

Inside `android { ... }`:
```kotlin
defaultConfig {
    // ...
    // CI passes a monotonically increasing code; local builds use the literal.
    versionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 1
    versionName = System.getenv("APP_VERSION_NAME") ?: "1.0"
}

signingConfigs {
    create("release") {
        // Credentials live in keystore.properties at the repo root (git-ignored — BACK IT UP
        // with upload-keystore.jks). Env vars override for CI.
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
        signingConfig = signingConfigs.getByName("release")
        // isMinifyEnabled = true  // turn on R8 + shrinkResources before production
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

`keystore.properties` (git-ignore it):
```properties
storeFile=upload-keystore.jks
storePassword=********
keyAlias=upload
keyPassword=********
```

Add to `.gitignore`:
```
upload-keystore.jks
keystore.properties
```
