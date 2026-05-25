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

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val executableSuffix = if (isWindows) ".exe" else ""
val batchSuffix = if (isWindows) ".bat" else ""
val javaHome = providers.environmentVariable("JAVA_HOME").orNull
    ?: error("Set JAVA_HOME to a JDK 17 installation before running Gradle.")
val aapt2 = file("${buildToolsDir.absolutePath}/aapt2$executableSuffix")
val d8 = file("${buildToolsDir.absolutePath}/d8$batchSuffix")
val zipalign = file("${buildToolsDir.absolutePath}/zipalign$executableSuffix")
val apksigner = file("${buildToolsDir.absolutePath}/apksigner$batchSuffix")
val keytool = file("$javaHome/bin/keytool$executableSuffix")
val jarTool = file("$javaHome/bin/jar$executableSuffix")

listOf(aapt2, d8, zipalign, apksigner, keytool, jarTool).forEach {
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

val packageDebugResources = tasks.register<Exec>("packageDebugResources") {
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

val dexDebugClasses = tasks.register<Exec>("dexDebugClasses") {
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

val packageDebugApk = tasks.register<Exec>("packageDebugApk") {
    dependsOn(packageDebugResources, dexDebugClasses)
    inputs.file(unsignedApk)
    inputs.file(dexOutputDir.map { it.file("classes.dex") })
    commandLine(
        jarTool.absolutePath,
        "uf",
        unsignedApk.get().asFile.absolutePath,
        "-C",
        dexOutputDir.get().asFile.absolutePath,
        "classes.dex"
    )
}

val generateDebugKeystore = tasks.register<Exec>("generateDebugKeystore") {
    outputs.file(debugKeystore)
    onlyIf { !debugKeystore.get().asFile.exists() }
    doFirst {
        apkWorkDir.get().asFile.mkdirs()
    }
    commandLine(
        keytool.absolutePath,
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

val zipalignDebugApk = tasks.register<Exec>("zipalignDebugApk") {
    dependsOn(packageDebugApk)
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
    dependsOn(zipalignDebugApk, generateDebugKeystore)
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
