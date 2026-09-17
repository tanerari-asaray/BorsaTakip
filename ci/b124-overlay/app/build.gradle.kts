plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "tr.borsatakip.v5"
    compileSdk = 35

    defaultConfig {
        applicationId = "tr.borsatakip.v5"
        minSdk = 26
        targetSdk = 35
        versionCode = 124
        versionName = "5.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Public endpoint only. API keys/tokens are intentionally NOT embedded in the APK.
        val configuredBackendUrl = listOf(
            System.getenv("BORSA_BACKEND_URL"),
            System.getenv("BORSA_PRODUCTION_BACKEND_URL"),
            providers.gradleProperty("BORSA_BACKEND_URL").orNull,
            providers.gradleProperty("BORSA_PRODUCTION_BACKEND_URL").orNull
        ).firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.removeSuffix("/")
            .orEmpty()
        if (configuredBackendUrl.isNotBlank()) {
            val parsedBackendUrl = runCatching { java.net.URI(configuredBackendUrl) }.getOrElse {
                error("BORSA_BACKEND_URL/BORSA_PRODUCTION_BACKEND_URL geçerli bir URI olmalıdır.")
            }
            check(
                parsedBackendUrl.isAbsolute &&
                    parsedBackendUrl.scheme.equals("https", ignoreCase = true) &&
                    !parsedBackendUrl.host.isNullOrBlank() &&
                    parsedBackendUrl.userInfo == null &&
                    parsedBackendUrl.fragment == null
            ) {
                "BORSA_BACKEND_URL/BORSA_PRODUCTION_BACKEND_URL yalnız güvenli HTTPS ve geçerli host içeren bir Production Backend URL olmalıdır."
            }
        }
        val escapedBackendUrl = configuredBackendUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "PRODUCTION_BACKEND_URL", "\"$escapedBackendUrl\"")
    }

    val releaseStorePath = System.getenv("BORSA_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("BORSA_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("BORSA_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("BORSA_KEY_PASSWORD")
    val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("releaseSecure") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            if (isReleaseTaskRequested) {
                check(hasReleaseSigning) { "Release APK üretimi için güvenli imzalama değişkenleri zorunludur." }
                signingConfig = signingConfigs.getByName("releaseSecure")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
