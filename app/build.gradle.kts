plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release version comes from -PversionName / -PversionCode (set by CI from the git
// tag); falls back to the dev defaults for local/debug builds.
val appVersionName: String = (findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Release signing is configured only when CI provides a keystore via env; absent it,
// release builds are unsigned and debug/tests are unaffected.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")

android {
    namespace = "tech.whitewolf.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.whitewolf.app"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native OIDC SSO (WWT-67): a public client against wwt-auth (Authelia).
        buildConfigField("String", "OIDC_ISSUER", "\"https://auth.whitewolf.tech\"")
        buildConfigField("String", "OIDC_CLIENT_ID", "\"maileroo-mobile\"")
        buildConfigField("String", "OIDC_REDIRECT_URI", "\"tech.whitewolf.app:/oauth2redirect\"")
        // AppAuth's RedirectUriReceiverActivity binds this custom scheme (the redirect
        // URI's scheme) via the library manifest — no manual <activity> needed.
        manifestPlaceholders["appAuthRedirectScheme"] = "tech.whitewolf.app"
    }

    if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "MAIL_BASE_URL", "\"https://mail.whitewolf.tech\"")
            buildConfigField("String", "NTFY_HOST", "\"ntfy.whitewolf.tech\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "MAIL_BASE_URL", "\"https://mail.whitewolf.tech\"")
            buildConfigField("String", "NTFY_HOST", "\"ntfy.whitewolf.tech\"")
            if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isReturnDefaultValues = true } }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.swiperefreshlayout)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.unifiedpush.connector)
    implementation(libs.appauth)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
