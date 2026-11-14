import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val runtimeProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun serviceOrigin(name: String, localHttp: Boolean): String {
    val value = runtimeProperties.getProperty(name, "").trim().trimEnd('/')
    if (value.isNotBlank()) {
        val uri = URI(value)
        require((uri.scheme == "https" || localHttp && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) &&
            uri.host != null && uri.userInfo == null && uri.path.isEmpty() && uri.query == null && uri.fragment == null)
    }
    return value
}
val releaseLearningOrigin = serviceOrigin("LEARNING_API_ORIGIN", false)
val debugLearningOrigin = serviceOrigin("LEARNING_API_DEBUG_ORIGIN", true).ifBlank { releaseLearningOrigin }

android {
    namespace = "io.github.driveenglishlab.pleos"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.driveenglishlab.pleos"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LEARNING_API_ORIGIN", "\"$releaseLearningOrigin\"")
    }

    buildTypes.getByName("debug") { buildConfigField("String", "LEARNING_API_ORIGIN", "\"$debugLearningOrigin\"") }
    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:pleos"))

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.compose)
    implementation(libs.hilt.android)
    implementation(libs.media3.session)
    implementation(libs.serialization.json)
    ksp(libs.hilt.compiler)
}
