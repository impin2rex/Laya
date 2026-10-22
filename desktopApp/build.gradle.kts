import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** What the packages, the launcher and the settings footer all say. */
val appVersion: String = providers.gradleProperty("laya.version").orNull
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    // The same version the Android build ships, from the root build — a hard-coded fallback here
    // went stale and made every local or unversioned build announce itself as 1.8-beta1, which
    // the update checker then offered to "update" to an older release.
    ?: rootProject.extra["layaVersionName"] as String

/** Which platform this build is *for*, which is the host unless told otherwise. */
val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val hostIsLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val hostIsMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
val targetOs: String = (providers.gradleProperty("laya.target").orNull ?: when {
    hostIsWindows -> "windows"
    hostIsLinux -> "linux"
    hostIsMac -> "macos"
    else -> error("Laya desktop supports Linux, Windows, and macOS only")
}).lowercase()

val hostArch = System.getProperty("os.arch").lowercase()
val isArm64 = hostArch == "aarch64" || hostArch == "arm64"

// The installers stamp the app's own version: 1.8.4 stays 1.8.4. Windows metadata needs exactly
// MAJOR.MINOR.BUILD, so a two-part version is padded (1.8 → 1.8.0) and a "-beta1" suffix is left
// out of the numeric version.
val nativePackageVersion = appVersion.substringBefore('-').split('.')
    .let { (it + listOf("0", "0")).take(3) }
    .joinToString(".")

// FFmpeg decodes audio; see DesktopAudioDecoder.
val javacppVersion = "1.5.12"
val ffmpegVersion = "7.1.1-$javacppVersion"
val nativeClassifier = when (targetOs) {
    "windows" -> "windows-x86_64"
    "linux" -> "linux-x86_64"
    "macos" -> if (isArm64) "macosx-arm64" else "macosx-x86_64"
    else -> error("Laya desktop supports Linux, Windows, and macOS only")
}
private fun localProperty(name: String): String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv(name).orEmpty().trim()

val desktopModuleIndexUrl = localProperty("MODULE_INDEX_URL")

// Last.fm signs every request with these, so signing in from inside the app
// needs them at hand. Supplied locally and never committed, as on Android.
val listenTogetherServer = localProperty("LISTEN_TOGETHER_SERVER")
val signatureApiUrl = localProperty("SIGNATURE_API_URL")
val lastfmApiKey = localProperty("LASTFM_API_KEY")
val lastfmSecret = localProperty("LASTFM_SECRET")

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    // Already on the classpath through the NewPipe extractor.
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.zxing:core:3.5.3")

    implementation(project(":shared"))
    // Its own copy of the player and pages; the phone and TV keep :sharedUi.
    implementation(project(":desktopUi"))
    // BotGuard, for the PoTokens YouTube's web clients need: the phone runs it in an Android
    // WebView, the desktop in JavaFX's (WebKit). Per-platform jars carry the natives.
    val javafxClassifier = when (targetOs) {
        "windows" -> "win"
        "macos" -> if (isArm64) "mac-aarch64" else "mac"
        else -> "linux"
    }
    listOf("base", "graphics", "controls", "media", "web").forEach { module ->
        implementation("org.openjfx:javafx-$module:21.0.10:$javafxClassifier")
    }

    // The shared player loads artwork through Coil, as the phone does.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Automix runs the same two models the Android build does. ONNX Runtime stopped shipping
    // osx-x64 natives after 1.23, so an Intel Mac build takes the last release that has them —
    // without it the beat model cannot load there and Automix has no meter.
    // ponytail: Intel Macs stay on 1.23.2 for good; drop the Intel build when that ages out.
    val onnxRuntimeVersion = if (targetOs == "macos" && !isArm64) "1.23.2" else "1.29.0"
    implementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeVersion")

    // Decoding, resampling and demuxing for the audio engine.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:$nativeClassifier")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")

    // The same backdrop blur the Android build uses for its floating bars.
    // On Windows, 1.7.2 keeps this Compose Desktop 1.10.3 app on the matching UI/text/animation
    // runtime. Preserve Linux's existing dependency graph exactly as it was.
    val desktopHazeVersion = if (targetOs == "windows") "1.7.2" else "1.7.3"
    implementation("dev.chrisbanes.haze:haze:$desktopHazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$desktopHazeVersion")

    // The Compose plugin's own `compose.*` accessors are deprecated, so these are the coordinates
    // they stood for. Keep [composeVersion] in step with the plugin version in the root build file;
    // Material 3 and the icons ship on their own numbers.
    val composeVersion = "1.10.3"
    implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
    implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
    // Pinned: this artifact no longer receives updates.
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    // The Skiko runtime rides in on this, and Skiko is per-platform.
    implementation(
        when (targetOs) {
            "windows" -> "org.jetbrains.compose.desktop:desktop-jvm-windows-x64:$composeVersion"
            "macos" -> "org.jetbrains.compose.desktop:desktop-jvm-macos-${if (isArm64) "arm64" else "x64"}:$composeVersion"
            else -> "org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$composeVersion"
        }
    )

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    // Listen Together speaks over a WebSocket; the Discord gateway already uses the same engine.
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // Browsers keep their cookies in SQLite, and importing a session from one is how this app signs
    // in — see DesktopBrowserCookies.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Chromium protects its Windows master key with DPAPI. JNA's platform helpers let the app
    // unwrap that key in the same Windows user session without invoking a shell or exposing it.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Linux desktop media controls use the standard session-bus MPRIS interfaces.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-junixsocket:5.2.0")

    // Same URL deobfuscation helper used by Android's StreamResolver.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // JVM equivalent of Android's QuickJS module host.
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:js:24.1.2")
    implementation("org.graalvm.js:js-language:24.1.2")

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/** Builds the Automix analyser from the same C++ the Android app compiles. */
// Per target, because both write the library under the same name and a Windows cross-build
// otherwise left a `.dll` where the next Linux run looked for its `.so`.
val analysisNativeDir = layout.buildDirectory.dir("native/$targetOs")

/** The tiny Windows frame bridge is independent of the optional analyser and SMTC libraries. */
val windowNativeDir = layout.buildDirectory.dir("native-window/$targetOs")

/**
 * Whether this build is producing the analyser for a platform that is not the one running the
 * build.
 */
val crossBuildingForWindows = targetOs == "windows" && !hostIsWindows

/**
 * An executable's absolute path, from the PATH this build was invoked with.
 *
 * Resolved here rather than left to the child process. Gradle runs external
 * commands from the daemon, whose own PATH is whatever it was started with, so
 * a daemon reused from a shell that had no cmake cannot find the one the
 * current shell does — it fails with a bare "No such file or directory" while
 * `cmake --version` works fine in the terminal beside it.
 */
/**
 * Whether a tool on the PATH can actually be started.
 *
 * A PATH entry can be a wrapper script whose own interpreter is gone; it is a file, it is
 * executable, and it fails only at exec time — which surfaces as a build failure rather than as the
 * missing-tool path this build already handles.
 */
fun runs(tool: File): Boolean = runCatching {
    val process = ProcessBuilder(tool.absolutePath, "--version")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

fun findOnPath(tool: String): File? {
    // Windows keeps the extension on the file and off the command line, so a bare name matches
    // nothing on disk: PATHEXT is the list a shell would have tried.
    val names = if (!hostIsWindows) {
        listOf(tool)
    } else {
        val extensions = System.getenv("PATHEXT").orEmpty()
            .ifBlank { ".COM;.EXE;.BAT;.CMD" }
            .split(';')
            .filter(String::isNotBlank)
        listOf(tool) + extensions.map { tool + it.lowercase() }
    }
    return System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter(String::isNotBlank)
        .flatMap { directory -> names.map { File(directory, it) } }
        // A PATHEXT match is executable by definition, and Windows' own canExecute is unreliable.
        .firstOrNull { it.isFile && (hostIsWindows || it.canExecute()) && runs(it) }
}

fun cmakeBinary(): String = findOnPath("cmake")?.absolutePath ?: "cmake"

val buildAnalysisNative by tasks.registering {
    val cmakeSource = project.file("native")
    val outputDir = analysisNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(rootProject.file("native/analyzer"))
    inputs.dir(rootProject.file("app/src/main/cpp/jni"))
    inputs.dir(project.file("native"))
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        val cmake = findOnPath("cmake")
        if (cmake == null) {
            logger.lifecycle(
                "no usable cmake — building without the Automix analyser. If cmake is installed, " +
                    "a Gradle daemon started before it was on PATH may be in use: ./gradlew --stop",
            )
        }
        val compilerName = if (crossing) "x86_64-w64-mingw32-g++" else "g++"
        val compiler = targetOs != "windows" || findOnPath(compilerName) != null
        val ninja = targetOs != "windows" || findOnPath("ninja") != null
        if (cmake != null && (!compiler || !ninja)) {
            logger.lifecycle(
                "$compilerName or Ninja not found — the Windows build will have no Automix analyser. " +
                    "Install a MinGW C++ compiler and Ninja, then rebuild.",
            )
        }
        cmake != null && compiler && ninja
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = if (targetOs == "windows") {
            findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
                ?: error("the Windows C++ compiler disappeared while building the analyser")
        } else {
            null
        }
        val ninja = if (targetOs == "windows") {
            findOnPath("ninja") ?: error("Ninja disappeared while building the analyser")
        } else {
            null
        }
        providers.exec {
            commandLine(
                buildList {
                    addAll(listOf(cmakeBinary(), "-S", cmakeSource.absolutePath, "-B", outputDir.absolutePath))
                    if (targetOs == "windows") {
                        // The host may not have Visual Studio/nmake. Select the MinGW toolchain we
                        // checked above and discard any generator cached by an earlier configure.
                        addAll(
                            listOf(
                                "--fresh",
                                "-G", "Ninja",
                                "-DCMAKE_MAKE_PROGRAM=${ninja!!.absolutePath}",
                                "-DCMAKE_CXX_COMPILER=${compiler!!.absolutePath}",
                            ),
                        )
                    }
                    add("-DCMAKE_BUILD_TYPE=Release")
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

/**
 * Builds the Win32/DWM frame bridge even when the much larger analyser build is skipped during a
 * desktop UI run. It is intentionally absent from Linux builds, whose window manager remains the
 * sole owner of decoration and behaviour.
 */
val buildWindowNative by tasks.registering {
    val cmakeSource = project.file("native/window")
    val outputDir = windowNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(cmakeSource)
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        if (targetOs != "windows") return@onlyIf false
        val cmake = findOnPath("cmake")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
        val ninja = findOnPath("ninja")
        if (cmake == null || compiler == null || ninja == null) {
            logger.lifecycle(
                "cmake, Ninja, or a MinGW C++ compiler is unavailable — the Windows build will " +
                    "fall back to Compose's frameless window behaviour.",
            )
        }
        cmake != null && compiler != null && ninja != null
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
            ?: error("MinGW C++ compiler disappeared while building the Windows frame")
        val ninja = findOnPath("ninja")
            ?: error("Ninja disappeared while building the Windows frame")
        providers.exec {
            commandLine(
                buildList {
                    addAll(
                        listOf(
                            cmakeBinary(),
                            "-S", cmakeSource.absolutePath,
                            "-B", outputDir.absolutePath,
                            "-G", "Ninja",
                            "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
                            "-DCMAKE_CXX_COMPILER=${compiler.absolutePath}",
                            "-DCMAKE_BUILD_TYPE=Release",
                        ),
                    )
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAnalysisNative, buildWindowNative)
    from(analysisNativeDir) {
        include("*.so", "*.dll", "*.dylib")
        into("native")
    }
    from(windowNativeDir) {
        include("*.dll")
        into("native")
    }
    // The Automix models, taken from the Android module rather than copied into this one.
    from(rootProject.file("app/src/main/assets")) {
        include("*.onnx")
        into("models")
    }
}

// Kotlin 2.4 no longer contributes this JVM target's compiler output to Gradle's plain `jar`
// task in this project. Compose's dev runner and the portable launcher both use that jar, so add
// the output explicitly instead of producing a resources-only application archive.
tasks.named<Jar>("jar") {
    val kotlinClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    dependsOn(kotlinClasses)
    from(kotlinClasses.flatMap { it.destinationDirectory })
}

/** A Windows build that can be assembled here, since an installer cannot be. */
val windowsPortableDir = layout.buildDirectory.dir("windows-portable")

val windowsPortableAssemble by tasks.registering {
    group = "distribution"
    description = "Lays out a Windows-runnable folder: application jars, Windows natives, launcher."
    dependsOn(tasks.named("jar"))
    val stage = windowsPortableDir.map { it.dir("Laya") }
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    val version = appVersion
    val moduleIndex = desktopModuleIndexUrl
    val lastfmKey = lastfmApiKey
    val lastfmSharedSecret = lastfmSecret
    val partyServer = listenTogetherServer
    val signatureUrl = signatureApiUrl
    val mainClass = composeMainClass
    // Declared, not implied.
    inputs.file(appJar)
    inputs.files(runtime)
    inputs.property("version", version)
    inputs.property("moduleIndex", moduleIndex)
    inputs.property("lastfm", lastfmKey.isNotBlank())
    inputs.property("partyServer", partyServer)
    inputs.property("signatureUrl", signatureUrl)
    inputs.property("mainClass", mainClass)
    outputs.dir(stage)
    doLast {
        val target = stage.get().asFile
        val lib = File(target, "lib")
        lib.deleteRecursively()
        lib.mkdirs()

        // Named by coordinate rather than by file name.
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier
            val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
            val name = if (group.isNullOrBlank()) artifact.file.name else "$group.${artifact.file.name}"
            artifact.file.copyTo(File(lib, name), overwrite = true)
        }
        appJar.get().asFile.copyTo(File(lib, "laya-app.jar"), overwrite = true)

        // `lib\*` rather than a spelled-out classpath: the wildcard is what keeps this working when
        // a dependency is added, and the JVM is what expands it.
        File(target, "Laya.bat").writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine("rem Laya, portable. Needs a Java 21+ runtime on PATH or in JAVA_HOME.")
                appendLine("set \"HERE=%~dp0\"")
                appendLine("set \"RUNNER=java\"")
                appendLine("if defined JAVA_HOME set \"RUNNER=%JAVA_HOME%\\bin\\java\"")
                append("\"%RUNNER%\" -Dlaya.version=$version")
                if (moduleIndex.isNotBlank()) append(" -Dlaya.module.index=$moduleIndex")
                if (lastfmKey.isNotBlank() && lastfmSharedSecret.isNotBlank()) {
                    append(" -Dlaya.lastfm.key=$lastfmKey -Dlaya.lastfm.secret=$lastfmSharedSecret")
                }
                if (partyServer.isNotBlank()) append(" -Dlaya.listentogether.server=$partyServer")
                if (signatureUrl.isNotBlank()) append(" -Dlaya.signature.url=$signatureUrl")
                appendLine(" -cp \"%HERE%lib\\*\" $mainClass %*")
                appendLine("endlocal")
            },
        )

        // What "it builds" is not the same as "it runs", and the difference is exactly what shipped
        // a broken archive.
        val jars = lib.listFiles { file: File -> file.name.endsWith(".jar") }.orEmpty()
        fun holds(entry: String) = jars.any { jar ->
            ZipFile(jar).use { zip -> zip.getEntry(entry) != null }
        }
        val problems = buildList {
            if (!holds("com/music/laya/desktop/MainKt.class")) {
                add("the Laya desktop entry point is missing")
            }
            if (!holds("androidx/compose/runtime/Composer.class")) {
                add("the Compose runtime is missing")
            }
            if (jars.none { it.name.contains("skiko-awt-runtime-windows") }) {
                add("the Windows Skiko renderer is missing")
            }
            if (jars.any { it.name.contains("skiko-awt-runtime-linux") }) {
                add("a Linux Skiko renderer is in a Windows archive")
            }
            if (!holds("org/bytedeco/ffmpeg/windows-x86_64/avformat.dll") &&
                jars.none { it.name.contains("ffmpeg") && it.name.contains("windows-x86_64") }
            ) {
                add("the Windows FFmpeg natives are missing")
            }
        }
        check(problems.isEmpty()) { "windowsPortable: " + problems.joinToString("; ") }

        val analyser = File(analysisNativeDir.get().asFile, "laya_analysis.dll").isFile
        logger.lifecycle(
            if (analyser) {
                "windowsPortable: verified — Compose runtime, Windows renderer, Windows FFmpeg, Automix analyser."
            } else {
                "windowsPortable: verified, but no laya_analysis.dll — Automix will not run in this archive. " +
                    "Rebuild with: nix-shell -p cmake pkgsCross.mingwW64.buildPackages.gcc"
            },
        )
    }
}

val windowsPortable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable Windows folder for copying to a Windows machine."
    dependsOn(windowsPortableAssemble)
    from(windowsPortableDir)
    archiveFileName.set("Laya-$appVersion-windows-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doLast { logger.lifecycle("windowsPortable: ${archiveFile.get().asFile.absolutePath}") }
}

val composeMainClass = "com.music.laya.desktop.MainKt"

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor(java.toolchain).map {
            it.metadata.installationPath.asFile.absolutePath
        }.get()
        mainClass = composeMainClass
        jvmArgs("-Xmx512m")
        jvmArgs("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000", "-XX:G1PeriodicGCSystemLoadThreshold=0")
        jvmArgs("-Dlaya.version=$appVersion")
        if (desktopModuleIndexUrl.isNotBlank()) {
            jvmArgs("-Dlaya.module.index=$desktopModuleIndexUrl")
        }
        if (lastfmApiKey.isNotBlank() && lastfmSecret.isNotBlank()) {
            jvmArgs("-Dlaya.lastfm.key=$lastfmApiKey", "-Dlaya.lastfm.secret=$lastfmSecret")
        }
        if (listenTogetherServer.isNotBlank()) {
            jvmArgs("-Dlaya.listentogether.server=$listenTogetherServer")
        }
        if (signatureApiUrl.isNotBlank()) {
            jvmArgs("-Dlaya.signature.url=$signatureApiUrl")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Exe,
                TargetFormat.Msi,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "Laya"
            packageVersion = nativePackageVersion
            description = if (targetOs == "windows") "Laya" else "Aesthetic YouTube Music client"
            vendor = "Laya contributors"
            copyright = "Copyright © 2026 BitChord and Laya contributors"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.dynalink",
                // JavaFX WebEngine returns JavaScript objects through netscape.javascript.JSObject.
                // Without this module the slim production runtime crashes in twkExecuteScript,
                // while development runs work because the full JDK already contains it.
                "jdk.jsobject",
                "jdk.localedata",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            macOS {
                iconFile.set(project.file("packaging/icons/AppIcon.icns"))
                bundleID = "com.music.laya"
                appCategory = "public.app-category.music"
                dockName = "Laya"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }

            linux {
                iconFile.set(project.file("packaging/icons/AppIcon.png"))
                menuGroup = "Audio"
                appCategory = "AudioVideo;Audio;Player"
                debMaintainer = "impin2rex@gmail.com"
                rpmLicenseType = "GPL-3.0"
            }

            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                upgradeUuid = "8cf8f381-55fe-4d99-9116-863b769e2de5"
                menuGroup = "Laya"
                shortcut = true
                dirChooser = false
                perUserInstall = true
            }
        }
    }

}
