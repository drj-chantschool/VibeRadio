plugins {
    // AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is not applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version comes from the release tag: release.yml strips the leading "v" from a
// v1.2.3 / v1.2.3-beta.4 tag and passes it as -Pviberadio.versionName. Local and CI
// builds without the property are 0.0.0-dev.
val devVersionName = "0.0.0-dev"
val appVersionName: String = providers.gradleProperty("viberadio.versionName").getOrElse(devVersionName)

/**
 * Maps a version name to a versionCode that sorts the same way, so every newer tag is an
 * upgrade: MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + stage, where stage is
 * alpha.N = N, beta.N = 30 + N, rc.N = 60 + N (N in 0..29) and a final release = 99.
 * MINOR and PATCH must be below 100.
 */
fun versionCodeOf(name: String): Int {
    if (name == devVersionName) return 1
    val match = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.(0|[1-9]\d*))?""")
        .matchEntire(name)
        ?: error("versionName '$name' must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-(alpha|beta|rc).N")
    val (major, minor, patch, stage, n) = match.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100) { "versionName '$name': MINOR and PATCH must be below 100" }
    val stageOffset = when (stage) {
        "" -> 99
        else -> {
            require(n.toInt() < 30) { "versionName '$name': pre-release number must be below 30" }
            mapOf("alpha" to 0, "beta" to 30, "rc" to 60).getValue(stage) + n.toInt()
        }
    }
    val code = major.toLong() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + stageOffset
    require(code in 2..2_100_000_000) { "versionName '$name' gives versionCode $code, outside 2..2100000000" }
    return code.toInt()
}

android {
    namespace = "app.viberadio"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.viberadio"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
    }

    // Release signing reads the keystore from the environment (release.yml decodes it from
    // repo secrets). Without VIBERADIO_KEYSTORE_PATH the release APK is built unsigned.
    val keystorePath = providers.environmentVariable("VIBERADIO_KEYSTORE_PATH").orNull
    val releaseSigning = keystorePath?.let { path ->
        signingConfigs.create("release") {
            storeFile = file(path)
            storePassword = providers.environmentVariable("VIBERADIO_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("VIBERADIO_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("VIBERADIO_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            // Debug builds install alongside the release-signed app instead of clashing with it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    testImplementation(libs.junit)
}
