import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signature release : lue depuis keystore.properties (en local) ou depuis les
// variables d'environnement SHORTSTOP_* (en CI). Sans l'un ni l'autre, l'APK
// release est produit non signé.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "SHORTSTOP_KEYSTORE_FILE")

android {
    namespace = "com.perso.shortstop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.perso.shortstop"
        minSdk = 26
        targetSdk = 35
        // Surchargés par la CI à partir du tag (ex. v1.2.3 → 1.2.3 / 10203)
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "SHORTSTOP_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SHORTSTOP_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SHORTSTOP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
