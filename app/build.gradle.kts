import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    java
}

repositories {
    mavenCentral()
}

val compileSdk = 36
val androidSdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
    .orElse(providers.environmentVariable("ANDROID_HOME"))
    .orNull
    ?: error("Set ANDROID_SDK_ROOT or ANDROID_HOME to the Android SDK path before running Gradle.")
val androidJar = file("$androidSdkRoot/platforms/android-$compileSdk/android.jar")
require(androidJar.exists()) { "Android platform jar not found at ${androidJar.absolutePath}" }

val buildToolsDir = file("$androidSdkRoot/build-tools").listFiles()
    ?.filter(File::isDirectory)
    ?.maxByOrNull(File::getName)
    ?: error("No Android build-tools found under $androidSdkRoot/build-tools")

val aapt2 = file("${buildToolsDir.absolutePath}/aapt2")
val d8 = file("${buildToolsDir.absolutePath}/d8")
val zipalign = file("${buildToolsDir.absolutePath}/zipalign")
val apksigner = file("${buildToolsDir.absolutePath}/apksigner")

listOf(aapt2, d8, zipalign, apksigner).forEach {
    require(it.exists()) { "Required Android build tool not found at ${it.absolutePath}" }
}

dependencies {
    compileOnly(files(androidJar))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

val manifestFile = layout.projectDirectory.file("src/main/AndroidManifest.xml")
val apkWorkDir = layout.buildDirectory.dir("intermediates/apk/debug")
val apkOutputDir = layout.buildDirectory.dir("outputs/apk/debug")
val dexOutputDir = apkWorkDir.map { it.dir("dex") }
val unsignedApk = apkWorkDir.map { it.file("app-debug-unsigned.apk") }
val alignedApk = apkWorkDir.map { it.file("app-debug-aligned.apk") }
val debugApk = apkOutputDir.map { it.file("SteamHapticsSinger-debug.apk") }
val debugKeystore = apkWorkDir.map { it.file("debug.keystore") }
val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }

val packageReleaseResources = tasks.register<Exec>("packageReleaseResources") {
    dependsOn(tasks.named("classes"))
    inputs.file(manifestFile)
    outputs.file(unsignedApk)
    doFirst {
        apkWorkDir.get().asFile.mkdirs()
    }
    commandLine(
        aapt2.absolutePath,
        "link",
        "-o",
        unsignedApk.get().asFile.absolutePath,
        "-I",
        androidJar.absolutePath,
        "--manifest",
        manifestFile.asFile.absolutePath
    )
}

val dexReleaseClasses = tasks.register<Exec>("dexReleaseClasses") {
    dependsOn(tasks.named("jar"))
    inputs.file(jarFile)
    outputs.dir(dexOutputDir)
    doFirst {
        dexOutputDir.get().asFile.mkdirs()
    }
    commandLine(
        d8.absolutePath,
        "--lib",
        androidJar.absolutePath,
        "--output",
        dexOutputDir.get().asFile.absolutePath,
        jarFile.get().asFile.absolutePath
    )
}

val packageReleaseApk = tasks.register<Exec>("packageReleaseApk") {
    dependsOn(packageReleaseResources, dexReleaseClasses)
    inputs.file(unsignedApk)
    inputs.file(dexOutputDir.map { it.file("classes.dex") })
    commandLine(
        "zip",
        "-qj",
        unsignedApk.get().asFile.absolutePath,
        dexOutputDir.get().file("classes.dex").asFile.absolutePath
    )
}

val generateDebugKeystore = tasks.register<Exec>("generateDebugKeystore") {
    outputs.file(debugKeystore)
    onlyIf { !debugKeystore.get().asFile.exists() }
    doFirst {
        apkWorkDir.get().asFile.mkdirs()
    }
    commandLine(
        "keytool",
        "-genkeypair",
        "-storetype",
        "PKCS12",
        "-keystore",
        debugKeystore.get().asFile.absolutePath,
        "-storepass",
        "android",
        "-keypass",
        "android",
        "-alias",
        "androiddebugkey",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "10000",
        "-dname",
        "CN=Android Debug,O=Android,C=US"
    )
}

val zipalignReleaseApk = tasks.register<Exec>("zipalignReleaseApk") {
    dependsOn(packageReleaseApk)
    inputs.file(unsignedApk)
    outputs.file(alignedApk)
    doFirst {
        apkWorkDir.get().asFile.mkdirs()
    }
    commandLine(
        zipalign.absolutePath,
        "-f",
        "4",
        unsignedApk.get().asFile.absolutePath,
        alignedApk.get().asFile.absolutePath
    )
}

tasks.register<Exec>("assembleDebugApk") {
    dependsOn(zipalignReleaseApk, generateDebugKeystore)
    inputs.file(alignedApk)
    inputs.file(debugKeystore)
    outputs.file(debugApk)
    doFirst {
        apkOutputDir.get().asFile.mkdirs()
    }
    commandLine(
        apksigner.absolutePath,
        "sign",
        "--ks",
        debugKeystore.get().asFile.absolutePath,
        "--ks-pass",
        "pass:android",
        "--ks-key-alias",
        "androiddebugkey",
        "--key-pass",
        "pass:android",
        "--out",
        debugApk.get().asFile.absolutePath,
        alignedApk.get().asFile.absolutePath
    )
}

tasks.named("build") {
    dependsOn("assembleDebugApk")
}
