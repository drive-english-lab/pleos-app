import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

val supabaseUrl = localProperties.getProperty("SUPABASE_URL", "").trim().trimEnd('/')
val supabaseKey = localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY", "").trim()

require(supabaseUrl.isBlank() == supabaseKey.isBlank()) {
    "Set both SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in local.properties."
}
if (supabaseUrl.isNotBlank()) {
    val uri = URI(supabaseUrl)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
        uri.path.isEmpty() && uri.query == null && uri.fragment == null && uri.port == -1
    ) {
        "SUPABASE_URL must be an HTTPS origin, such as https://your-project.supabase.co."
    }
    require(supabaseKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) {
        "SUPABASE_PUBLISHABLE_KEY must be a Supabase publishable key."
    }
}

android {
    namespace = "io.github.driveenglishlab.pleos.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabaseKey\"")
    }
    buildFeatures { buildConfig = true }

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
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    implementation(libs.datastore.preferences)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
