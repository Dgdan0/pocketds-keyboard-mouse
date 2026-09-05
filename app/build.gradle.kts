plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketds.kbm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketds.kbm"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
    }

    buildTypes {
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
    // Gesture, key and physics logic lives in plain Kotlin classes that take
    // primitives rather than View/MotionEvent/Context, so it can be tested on
    // the JVM without a device or Robolectric. Verifying this app on hardware is
    // slow and awkward enough that anything decidable off-device should be.
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Spring/fling physics for the floating bubble. Hand-rolled easing on a
    // drag-and-release interaction never lands on a good feel; this exposes
    // damping and stiffness directly, which is what actually needs tuning.
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    // Inline autofill: builds the suggestion style the autofill service
    // renders its chips with. The framework side is API 30+.
    implementation("androidx.autofill:autofill:1.1.0")
}
