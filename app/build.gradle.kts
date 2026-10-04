import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.goodboy13.milton"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.goodboy13.milton"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.0.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropsFile = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: project.file("keystore.properties").takeIf { it.exists() }
    val keystoreProps = Properties().apply {
        if (keystorePropsFile != null && keystorePropsFile.exists()) {
            load(FileInputStream(keystorePropsFile))
        }
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
            val storePass = keystoreProps.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
            val keyAl = keystoreProps.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
            val keyPass = keystoreProps.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")

            if (storePath != null && storePass != null && keyAl != null && keyPass != null) {
                val candidateFile = if (File(storePath).isAbsolute) File(storePath) else file(storePath)
                if (candidateFile.exists()) {
                    storeFile = candidateFile
                    storePassword = storePass
                    keyAlias = keyAl
                    keyPassword = keyPass
                }
            } else if (file("release.keystore").exists()) {
                storeFile = file("release.keystore")
                storePassword = "sketchman123"
                keyAlias = "sketchman"
                keyPassword = "sketchman123"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Low latency stylus front buffering
    implementation("androidx.graphics:graphics-core:1.0.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
