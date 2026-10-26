import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The Compose UI both applications draw from — the player first.
 *
 * Library versions are pinned to the Compose Multiplatform release whose
 * Android artifacts are the androidx ones the phone app already runs (CMP
 * 1.10.x -> androidx 1.10.x, material3 1.7.3 -> androidx material3 1.3.1), so
 * depending on this module never moves the phone's Compose underneath it. The
 * desktop resolves its own newer versions over these at the top of its graph.
 *
 * The pin is the *Android* anchor only. On the JVM it is a floor, not a
 * ceiling — and there it matters which version compiles this module, because
 * the desktop resolves ui-desktop 1.12.0 / skiko 0.150.x at runtime. Skia's
 * Matrix33 became an inline float[9] between skiko 0.9 and 0.15
 * (`makeTranslate` went from returning a Matrix33 to a name-mangled float[]
 * overload), so bytecode compiled against 0.9 linked its object call and
 * crashed with NoSuchMethodError on the desktop the first time a lyric line
 * grew. Compiling the module against the version the desktop actually
 * resolves keeps the compile-time link and the runtime link the same.
 */
val cmp = "1.10.3"

/** What the desktop graph resolves; see the comment above. */
val CMP_DESKTOP =
    // Haze 1.7.x, which the Windows build uses (see desktopApp/build.gradle.kts), was compiled
    // against a ShaderBrush.createShader that returns skia's Shader; 1.12 changed that return
    // type, so on 1.12 every blur dies with NoSuchMethodError. Windows stays on the plugin's
    // own 1.10.3 / skiko 0.9; only Linux moves to 1.12.
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "1.10.3" else "1.12.0"

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "LayaKit"
            export(project(":shared")) // Swift implements :shared's KaraokeNative
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api("org.jetbrains.compose.runtime:runtime:$cmp")
            api("org.jetbrains.compose.foundation:foundation:$cmp")
            api("org.jetbrains.compose.animation:animation:$cmp")
            api("org.jetbrains.compose.ui:ui:$cmp")
            api("org.jetbrains.compose.material3:material3:1.7.3")
            api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            api("org.jetbrains.compose.components:components-resources:$cmp")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
            api("io.coil-kt.coil3:coil-compose:3.0.4")
            api("dev.chrisbanes.haze:haze:1.3.1")
            api("dev.chrisbanes.haze:haze-materials:1.3.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        // Compiled against what the desktop actually resolves, not the phone's
        // pin — see the CMP_DESKTOP comment at the top of this file. Skia's
        // Matrix33 changed shape between skiko 0.9 and 0.15, and bytecode built
        // against 0.9 NoSuchMethodError'd the desktop's 0.15 at draw time.
        jvmMain.dependencies {
            api("org.jetbrains.compose.ui:ui-desktop:$CMP_DESKTOP")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
        }
        androidMain.dependencies {
            // The phone's own versions, so nothing here moves them.
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("androidx.appcompat:appcompat:1.7.0")
            implementation("androidx.palette:palette-ktx:1.0.0")
        }
        iosMain.dependencies {
            // The 1.7.3 pin above is the Android anchor. On iOS a klib built against
            // Compose 1.7 fails at runtime against ui 1.10, so use the release paired with $cmp.
            api("org.jetbrains.compose.material3:material3:1.10.0-alpha05")
            // Same reason, per library: newest releases still built against Compose <= $cmp
            // (haze 1.7.2 -> ui 1.10.0, coil 3.4.0 -> foundation 1.9.3, lifecycle 2.9.6 -> CMP 1.10 line).
            // material-icons-extended 1.7.3 is the last release there is (frozen), so it stays.
            api("dev.chrisbanes.haze:haze:1.7.2")
            api("dev.chrisbanes.haze:haze-materials:1.7.2")
            api("io.coil-kt.coil3:coil-compose:3.4.0")
            // Native has no ServiceLoader, so the fetcher is registered by hand in MainViewController.
            api("io.coil-kt.coil3:coil-network-ktor3:3.4.0")
            // Artist signatures are usually SVG.
            api("io.coil-kt.coil3:coil-svg:3.4.0")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
        }
    }
}

// The signature API's default address, from the same SIGNATURE_API_URL Android bakes into BuildConfig
// (local.properties, then the environment). Empty is supported: the setting then asks for one.
val genSignatureApiUrl by tasks.registering {
    val out = layout.buildDirectory.dir("generated/signatureApi")
    val localFile = rootProject.file("local.properties")
    inputs.files(localFile).optional()
    inputs.property("env", providers.environmentVariable("SIGNATURE_API_URL").orElse(""))
    outputs.dir(out)
    doLast {
        val props = Properties().apply { if (localFile.exists()) localFile.inputStream().use { load(it) } }
        val url = (props.getProperty("SIGNATURE_API_URL") ?: System.getenv("SIGNATURE_API_URL") ?: "")
            .trim().trimEnd('/').replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val f = out.get().file("com/music/laya/ios/SignatureApiDefault.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.music.laya.ios\n\n// Generated by sharedUi/build.gradle.kts from SIGNATURE_API_URL.\ninternal const val SIGNATURE_API_URL = \"$url\"\n")
    }
}
kotlin.sourceSets.named("iosMain") { kotlin.srcDir(genSignatureApiUrl) }

android {
    // lintVital (run on every release build) prints Kotlin-metadata errors: AGP 8.10 lint embeds Kotlin 2.1. Full `lint` still runs on demand.
    lint { checkReleaseBuilds = false }
    namespace = "com.music.laya.sharedui"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.music.laya.sharedui.resources"
    generateResClass = always
}