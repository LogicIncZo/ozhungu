plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "in.cashlessconsumer.ozhungu"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "in.cashlessconsumer.ozhungu"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        debug {
            // JaCoCo instrumentation for JVM unit tests; report via
            // `./gradlew createDebugUnitTestCoverageReport` (make coverage).
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("dev.zocomputer:ask:0.2.0")
    // ZoApi.ask returns okhttp3.Call — keep the type on the app classpath
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android ships a stub)
    testImplementation("org.json:json:20240303")
}
