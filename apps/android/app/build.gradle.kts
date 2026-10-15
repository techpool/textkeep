plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); alias(libs.plugins.kotlin.kapt) }
fun setting(name: String, fallback: String="") = providers.environmentVariable(name).orElse(providers.gradleProperty(name)).getOrElse(fallback)
android {
    namespace = "io.github.techpool.textkeep"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.techpool.textkeep"; minSdk = 29; targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = setting("RUNNERMESH_BUILD_NUMBER", "28").toInt(); versionName = setting("RUNNERMESH_APP_VERSION", "0.2.0")
        buildConfigField("String", "DEFAULT_SERVER", "\"${setting("TEXTKEEP_SERVER", "")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${setting("FIREBASE_ANDROID_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${setting("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${setting("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${setting("GOOGLE_CLIENT_ID")}\"")
    }
    signingConfigs { create("privateRelease") {
        if(setting("TEXTKEEP_KEYSTORE").isNotEmpty()) {
            storeFile=file(setting("TEXTKEEP_KEYSTORE")); storePassword=setting("TEXTKEEP_STORE_PASSWORD")
            keyAlias=setting("TEXTKEEP_KEY_ALIAS","textkeep"); keyPassword=setting("TEXTKEEP_KEY_PASSWORD")
        }
    } }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix="-debug" }
        release { isMinifyEnabled=true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro"); if(setting("TEXTKEEP_KEYSTORE").isNotEmpty()) signingConfig=signingConfigs.getByName("privateRelease") }
    }
    buildFeatures { compose=true; buildConfig=true }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }

    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform(libs.compose.bom)); implementation("androidx.compose.material3:material3"); implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4"); implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0"); implementation("androidx.core:core-ktx:1.18.0")
    implementation(libs.room.runtime); implementation(libs.room.ktx); kapt(libs.room.compiler); implementation(libs.work)
    implementation(platform("com.google.firebase:firebase-bom:35.0.0")); implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.6.0"); implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.klinkerapps:android-smsmms:5.2.6")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
