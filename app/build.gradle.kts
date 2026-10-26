import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ilya.dzshka"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ilya.dzshka"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.9.9"
    }
    // Данные подписи — в keystore.properties (не в git). Файл и .jks есть только на машине сборки.
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { s -> load(s) }
    }
    signingConfigs {
        create("rel") {
            storeFile = rootProject.file(ksProps.getProperty("storeFile") ?: "keystore/dzshka.jks")
            storePassword = ksProps.getProperty("storePassword") ?: System.getenv("DZSHKA_STORE_PASSWORD")
            keyAlias = ksProps.getProperty("keyAlias") ?: "dzshka"
            keyPassword = ksProps.getProperty("keyPassword") ?: System.getenv("DZSHKA_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("rel")
        }
    }
    flavorDimensions += "role"
    productFlavors {
        create("full") {
            dimension = "role"
            buildConfigField("boolean", "HELPER", "false")
            resValue("string", "app_name", "ДЗшка")
        }
        create("helper") {
            dimension = "role"
            applicationIdSuffix = ".helper"
            versionNameSuffix = "-helper"
            buildConfigField("boolean", "HELPER", "true")
            resValue("string", "app_name", "ДЗшка Помощник")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("shots.dir", rootProject.file("shots").absolutePath)
                System.getenv("NTFY_URL")?.let { u -> it.systemProperty("ntfy.url", u) }
                it.maxHeapSize = "3g"
            }
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(bom)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
