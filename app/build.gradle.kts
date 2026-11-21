plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
val translationAssets = tasks.register<Sync>("prepareTranslationAssets") {
    from("src/main/res") { include("values*/strings.xml") }
    into(layout.buildDirectory.dir("generated/translationAssets/translations"))
}

android {
    namespace = "omnibeat.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "omnibeat.app"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.gradleProperty("app.versionCode").get().toInt()
        versionName = providers.gradleProperty("app.versionName").get()

        ndk {
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
    }
    signingConfigs {
        // Stable key for the accessibility fork's debug builds, so each new APK installs as an
        // update over the last one. It only signs this personal fork's ".a11y" app, never the
        // upstream OmniBeat app ID.
        create("a11y") {
            storeFile = file("a11y-debug.keystore")
            storePassword = "omnibeat-a11y"
            keyAlias = "a11y"
            keyPassword = "omnibeat-a11y"
        }
    }
    buildTypes {
        debug {
            // Installs alongside OmniBeat from F-Droid instead of clashing with it.
            applicationIdSuffix = ".a11y"
            versionNameSuffix = "-a11y"
            signingConfig = signingConfigs.getByName("a11y")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets.getByName("main").assets.directories.add(
        layout.buildDirectory.dir("generated/translationAssets").get().asFile.absolutePath,
    )
}

tasks.named("preBuild").configure { dependsOn(translationAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.reorderable)
    debugImplementation(libs.compose.ui.tooling)
}
