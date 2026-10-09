plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.assetlib.sdk"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
    // The instrumentation host uses current platform behavior; consumers still choose their app target SDK.
    testOptions { targetSdk = 36 }
    sourceSets["androidTest"].assets.srcDir("src/test/resources")
}
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}
tasks.withType<Test>().configureEach {
    environment("ASSETLIB_PUBLIC_CONFIG_FILE", System.getenv("ASSETLIB_PUBLIC_CONFIG_FILE") ?: "")
}
