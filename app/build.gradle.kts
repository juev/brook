plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "org.evsyukov.brook"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.evsyukov.brook"
        minSdk = 33
        targetSdk = 37
        // A release takes its version from the tag, and every release raises the code: an updater
        // that compares codes sees no update otherwise. A candidate 1.2.3-rc.4 gets 1020304, the
        // final 1.2.3 gets 1020399, above all of its candidates.
        val version = providers.gradleProperty("brook.version").orElse("0.1.0").get()
        val match = Regex("""(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?""").matchEntire(version)
            ?: error("brook.version $version is neither 1.2.3 nor 1.2.3-rc.4")
        val (major, minor, patch, rc) = match.destructured
        val candidate = rc.toIntOrNull() ?: 99
        require(minor.toInt() < 100 && patch.toInt() < 100 && candidate in 1..99 && rc != "99") {
            "brook.version $version does not fit the version code"
        }
        versionCode = ((major.toInt() * 100 + minor.toInt()) * 100 + patch.toInt()) * 100 + candidate
        versionName = version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key comes from the environment (CI secrets); without it the release stays unsigned.
    val keystore = providers.environmentVariable("BROOK_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("BROOK_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BROOK_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BROOK_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // The release code, shrunk by R8, signed with the debug key and allowed plain HTTP to a local
        // server: for trying a release against scripts/dev-freshgo.sh on the emulator.
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".qa"
            matchingFallbacks += "release"
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
        unitTests.isIncludeAndroidResources = true
        // `-Pbrook.live=http://127.0.0.1:8080` runs LiveGReaderTest against scripts/dev-freshgo.sh.
        val live = providers.gradleProperty("brook.live").orElse("")
        unitTests.all { it.systemProperty("brook.live", live.get()) }
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        // The network security config of debug: plain HTTP to the emulator host.
        getByName("qa").res.directories.add("src/debug/res")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.readability4j)
    implementation(libs.jsoup)
    implementation(libs.androidsvg)
    implementation(libs.unifiedpush.connector)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.paging.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
