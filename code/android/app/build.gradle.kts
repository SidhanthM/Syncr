plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.syncr.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.syncr.prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDir("../../core/generated/kotlin")
    sourceSets["main"].jniLibs.srcDir("../../core/generated/jniLibs")
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0") // already resolved transitively; named here for the pad's outline reads
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-authoring:1.0.0")
    // Pen-motion prediction for the wet layer only (never stored). Pinned exactly; 1.0.1 is the latest stable.
    implementation("androidx.input:input-motionprediction:1.0.1")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.5")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Opt-in real Rust host gate. Normal unit tests need neither a host core nor a device.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("syncrNativeHost").orNull == "true") {
        systemProperty("jna.library.path", rootProject.file("../core/generated/host").absolutePath)
    } else {
        exclude("**/HostCoreIntegrationTest.class", "**/*HostTest.class")
    }
}

// The Mac-link probe: the phone side of the link as a plain JVM process (src/test/.../link/Probe.kt), for testing against the
// real Mac app without a phone. Needs the host core library. Stdin reaches it, so the pairing code can be typed:
//   ./gradlew -q --console=plain :app:linkProbe --args="127.0.0.1:5050 --code 1234"
tasks.register<JavaExec>("linkProbe") {
    group = "verification"
    description = "Runs the phone's Mac-link client as a host JVM process against host:port (see README)."
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    classpath = files(unitTests.map { it.classpath })
    mainClass.set("dev.syncr.prototype.link.ProbeMainKt")
    systemProperty("jna.library.path", rootProject.file("../core/generated/host").absolutePath)
    workingDir = projectDir
    standardInput = System.`in`
}
