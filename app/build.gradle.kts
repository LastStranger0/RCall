import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
} else {
    logger.warn("Файл 'local.properties' не найден. Используются значения по умолчанию.")
}


android {
    namespace = "org.adevelop.rcall"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "org.adevelop.rcall"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk  {
            abiFilters.addAll(listOf("x86_64", "arm64-v8a"))
        }

        buildConfigField("String", "WS_BASE", "\"${localProperties.getProperty("app.ws_base", "")}\"")
        buildConfigField("String", "TURN_URL",  "\"${localProperties.getProperty("app.turn_url", "")}\"")
        buildConfigField("String", "TURN_USER", "\"${localProperties.getProperty("app.turn_user", "")}\"")
        buildConfigField("String", "TURN_PASS", "\"${localProperties.getProperty("app.turn_pass", "")}\"")
        buildConfigField("String", "TURN_REALM", "\"${localProperties.getProperty("app.turn_realm", "")}\"")
        buildConfigField("String", "KEYCHAIN_PASSWORD", "\"${localProperties.getProperty("app.keychain_password", "")}\"")
    }


    buildTypes {
        release {
            isMinifyEnabled =  true
            isShrinkResources =  true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix =  "-dev"
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes.addAll(
                listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*")
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.web.rtc)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}