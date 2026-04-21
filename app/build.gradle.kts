import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val runtimeDir = rootProject.layout.projectDirectory.dir("runtime")
val sampleProjectAssetsDir = rootProject.layout.projectDirectory.dir("../Maa_bbb/assets")
val sampleCommonAssetsDir = rootProject.layout.projectDirectory.dir("../Maa_bbb/assets/MaaCommonAssets")
val generatedProjectAssetsDir = layout.buildDirectory.dir("generated/projectAssets")
val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtimeAssets")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/jniLibs")
val signingPropertiesFile = rootProject.file("key.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

val prepareSampleProjectAssets by tasks.registering(Sync::class) {
    from(sampleProjectAssetsDir)
    val defaultOcrModelDir = sampleCommonAssetsDir.asFile.resolve("OCR/ppocr_v5/zh_cn")
    if (defaultOcrModelDir.exists()) {
        from(defaultOcrModelDir) {
            into("resource/base/model/ocr")
        }
    }
    into(generatedProjectAssetsDir)
    includeEmptyDirs = true
    doFirst {
        if (!defaultOcrModelDir.exists()) {
            logger.warn(
                "Default OCR model directory not found at ${defaultOcrModelDir.absolutePath}; " +
                    "OCR-based recognitions may be unavailable in the sample runtime.",
            )
        }
    }
    doLast {
        val openingPipeline = generatedProjectAssetsDir.get()
            .file("resource/base/pipeline/进入游戏/打开游戏.json")
            .asFile
        if (openingPipeline.exists()) {
            val original = openingPipeline.readText()
            val patched = original.replace(
                "\"expected\": [\n            \"点击任意处进入游戏\"\n        ],",
                "\"expected\": [\n            \"点击任意处进入游戏\",\n            \"点击任意处进\"\n        ],",
            )
            if (patched != original) {
                openingPipeline.writeText(patched)
            }
        }

        val rewardPipeline = generatedProjectAssetsDir.get()
            .file("resource/base/pipeline/进入游戏/进游戏奖励领取.json")
            .asFile
        if (rewardPipeline.exists()) {
            val original = rewardPipeline.readText()
            val patched = original.replace(
                "\"expected\": [\n            \"领取签到奖励\"\n        ],\n        \"action\": \"Click\",\n        \"target\": [\n            584,\n            520,\n            114,\n            27\n        ]",
                "\"expected\": [\n            \"领取签到奖励\",\n            \"每日签到\",\n            \"研海悠游\",\n            \"第1天\"\n        ],\n        \"action\": \"Click\",\n        \"target\": [\n            654,\n            656,\n            195,\n            41\n        ]",
            )
            if (patched != original) {
                rewardPipeline.writeText(patched)
            }
        }
    }
}

val prepareBundledRuntimeAssets by tasks.registering(Sync::class) {
    from(runtimeDir)
    into(generatedRuntimeAssetsDir.map { it.dir("bundled_runtime") })
    includeEmptyDirs = true
}

val prepareBundledRuntimeJniLibs by tasks.registering(Sync::class) {
    from(runtimeDir.dir("maafw"))
    into(generatedJniLibsDir.map { it.dir("arm64-v8a") })
    include("*.so")
    includeEmptyDirs = false
}

android {
    namespace = "com.maaframework.android.sample.bbb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maaframework.android.sample.bbb"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main").assets.srcDirs(
        "src/main/assets",
        generatedProjectAssetsDir,
        generatedRuntimeAssetsDir,
    )
    sourceSets.getByName("main").jniLibs.srcDirs(generatedJniLibsDir)

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") {
    dependsOn(prepareSampleProjectAssets)
    dependsOn(prepareBundledRuntimeAssets)
    dependsOn(prepareBundledRuntimeJniLibs)
}

dependencies {
    implementation(project(":framework"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
