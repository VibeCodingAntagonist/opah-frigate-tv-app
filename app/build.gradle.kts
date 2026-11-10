plugins {
    id("com.android.application")
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val opahVersionCode = providers.gradleProperty("opah.versionCode")
    .map(String::toInt)
    .orElse(7001)
    .get()
val opahVersionName = providers.gradleProperty("opah.versionName")
    .orElse("0.7.1")
    .get()

val releaseKeystoreFile = providers.environmentVariable("OPAH_RELEASE_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("OPAH_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("OPAH_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("OPAH_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseKeystoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasAnyReleaseSigningValue = releaseSigningValues.any { !it.isNullOrBlank() }
val hasCompleteReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
val requireReleaseSigning = providers.gradleProperty("opah.requireReleaseSigning")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false
val nativeViewPresentation = providers.gradleProperty("opah.nativeViewPresentation")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: true

if (hasAnyReleaseSigningValue && !hasCompleteReleaseSigning) {
    throw GradleException(
        "Release signing configuration is incomplete. Provide all OPAH_RELEASE_* environment variables.",
    )
}
if (requireReleaseSigning && !hasCompleteReleaseSigning) {
    throw GradleException("A signed release was required, but no complete release signing configuration was provided.")
}

android {
    namespace = "app.opah.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.opah.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = opahVersionCode
        versionName = opahVersionName
        buildConfigField("boolean", "DOCUMENTATION_MODE", "false")
        buildConfigField("boolean", "NATIVE_VIEW_PRESENTATION", nativeViewPresentation.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        if (file("src/documentation").isDirectory && file("src/documentationFixtures").isDirectory) {
            create("documentation") {
                initWith(getByName("release"))
                applicationIdSuffix = ".docs"
                versionNameSuffix = "-documentation"
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += "release"
                buildConfigField("boolean", "DOCUMENTATION_MODE", "true")
            }
        }
    }

    sourceSets {
        if (file("src/documentationFixtures").isDirectory) {
            getByName("test").kotlin.directories.add("src/documentationFixtures/java")
            findByName("documentation")?.kotlin?.directories?.add("src/documentationFixtures/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        lintConfig = file("lint.xml")
        // API 37 is still an Android 17 preview. Core 1.19 and Lifecycle 2.11
        // require compileSdk 37, so the last API-36-compatible releases stay
        // pinned. Gradle 9.5 is AGP 9.3's documented default, and OkHttp 5.3
        // is the latest release identified by Square's official project page.
        disable += setOf(
            "OldTargetApi",
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.8.4")

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
