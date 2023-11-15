/*
 * SPDX-FileCopyrightText: 2023 Pixel Updater contributors
 * SPDX-FileCopyrightText: 2022-2023 Andrew Gunnerson
 * SPDX-FileContributor: Modified by Pixel Updater contributors
 * SPDX-License-Identifier: GPL-3.0-only
 * Based on BCR code.
 */

import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.google.protobuf.gradle.proto
import org.eclipse.jgit.api.ArchiveCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.archive.TarFormat
import org.eclipse.jgit.lib.ObjectId
import org.jetbrains.kotlin.backend.common.pop
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

buildscript {
    dependencies {
        "classpath"(libs.jgit)
        "classpath"(libs.jgit.archive)
        "classpath"(libs.json)
    }
}

typealias VersionTriple = Triple<String?, Int, ObjectId>

fun describeVersion(git: Git): VersionTriple {
    // jgit doesn't provide a nice way to get strongly-typed objects from its `describe` command
    val describeStr = git.describe().setLong(true).call()

    return if (describeStr != null) {
        val pieces = describeStr.split('-').toMutableList()
        val commit = git.repository.resolve(pieces.pop().substring(1))
        val count = pieces.pop().toInt()
        val tag = pieces.joinToString("-")

        Triple(tag, count, commit)
    } else {
        val log = git.log().call().iterator()
        val head = log.next()
        var count = 1

        while (log.hasNext()) {
            log.next()
            ++count
        }

        Triple(null, count, head.id)
    }
}

fun getVersionCode(triple: VersionTriple): Int {
    val tag = triple.first
    val (major, minor) = if (tag != null) {
        if (!tag.startsWith('v')) {
            throw IllegalArgumentException("Tag does not begin with 'v': $tag")
        }

        val pieces = tag.substring(1).split('.')
        if (pieces.size != 2) {
            throw IllegalArgumentException("Tag is not in the form 'v<major>.<minor>': $tag")
        }

        Pair(pieces[0].toInt(), pieces[1].toInt())
    } else {
        Pair(0, 0)
    }

    // 8 bits for major version, 8 bits for minor version, and 8 bits for git commit count
    assert(major in 0 until 1.shl(8))
    assert(minor in 0 until 1.shl(8))
    assert(triple.second in 0 until 1.shl(8))

    return major.shl(16) or minor.shl(8) or triple.second
}

fun getVersionName(git: Git, triple: VersionTriple): String {
    val tag = triple.first ?: "NONE"

    return buildString {
        append(tag)

        if (triple.second > 0) {
            append(".r")
            append(triple.second)

            append(".g")
            git.repository.newObjectReader().use {
                append(it.abbreviate(triple.third).name())
            }
        }
    }
}

fun getSelectedDevice(): String {
    val adbExecutable = android.adbExecutable

    // Retrieve the target device serial number from the command line
    val targetDeviceSerialFromCommandLine = project.findProperty("s") as String?

    // Retrieve the target device serial number from the environment variable
    val targetDeviceSerialFromEnv = System.getenv("s")

    // Determine the selected device based on priority
    val selectedDevice = targetDeviceSerialFromCommandLine ?: targetDeviceSerialFromEnv
        ?: run {
            // If no target device is specified, determine the selected device
            // by listing available devices and selecting the first one
            val commandDevices = "${adbExecutable.absolutePath} devices"
            val processDevices = Runtime.getRuntime().exec(commandDevices)
            processDevices.waitFor()
            val readerDevices = BufferedReader(InputStreamReader(processDevices.inputStream))
            val devices = readerDevices.lines()
                .filter { it.endsWith("device") }
                .map { it.split('\t')[0] }
                .toList()
            devices.firstOrNull() ?: ""
        }

    return selectedDevice
}

val git = Git.open(File(rootDir, ".git"))!!
val gitVersionTriple = describeVersion(git)
val gitVersionCode = getVersionCode(gitVersionTriple)
val gitVersionName = getVersionName(git, gitVersionTriple)
val gitBranch = git.repository.branch

val projectUrl = "https://github.com/PixelUpdater/PixelUpdater"

val extraDir = layout.buildDirectory.map { it.dir("extra") }
val archiveDir = extraDir.map { it.dir("archive") }

android {
    namespace = "com.github.pixelupdater.pixelupdater"

    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "26.0.10792818"

    defaultConfig {
        applicationId = "com.github.pixelupdater.pixelupdater"
        minSdk = 33
        targetSdk = 34
        versionCode = gitVersionCode
        versionName = gitVersionName
        resourceConfigurations.addAll(listOf(
            "en",
        ))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PROJECT_URL_AT_COMMIT",
            "\"${projectUrl}/tree/${gitVersionTriple.third.name}\"")
    }
    sourceSets {
        getByName("main") {
            assets {
                srcDir(archiveDir)
            }
            proto {
                srcDir(File(rootDir, "protobuf"))
            }
        }
    }
    signingConfigs {
        create("release") {
            val keystore = System.getenv("RELEASE_KEYSTORE")
            storeFile = if (keystore != null) { File(keystore) } else { null }
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSPHRASE")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSPHRASE")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_17)
        targetCompatibility(JavaVersion.VERSION_17)
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }
    lint {
        // The translations are always going to lag behind new strings being
        // added to values/strings.xml
        disable += "MissingTranslation"
    }
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }

    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)
    implementation(libs.jsoup)
    implementation(libs.jsr305)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsu.core)
    implementation(libs.material)
    implementation(libs.protobuf.javalite)
}

val archive = tasks.register("archive") {
    inputs.property("gitVersionTriple.third", gitVersionTriple.third)

    val outputFile = archiveDir.map { it.file("archive.tar") }
    outputs.file(outputFile)

    doLast {
        val format = "tar_for_task_$name"

        ArchiveCommand.registerFormat(format, TarFormat())
        try {
            outputFile.get().asFile.outputStream().use {
                git.archive()
                    .setTree(git.repository.resolve(gitVersionTriple.third.name))
                    .setFormat(format)
                    .setOutputStream(it)
                    .call()
            }
        } finally {
            ArchiveCommand.unregisterFormat(format)
        }
    }
}

android.applicationVariants.all {
    val variant = this
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val variantDir = extraDir.map { it.dir(variant.name) }

    // https://stackoverflow.com/a/60849081/434343
    outputs.all {
        val output = this as BaseVariantOutputImpl
        if (output.outputFileName == "${project.name}-${variant.name}.apk") {
            output.outputFileName = "${rootProject.name}.apk"
        }
    }

    variant.preBuildProvider.configure {
        dependsOn(archive)
    }

    val moduleProp = tasks.register("moduleProp${capitalized}") {
        inputs.property("projectUrl", projectUrl)
        inputs.property("gitBranch", gitBranch)
        inputs.property("rootProject.name", rootProject.name)
        inputs.property("variant.applicationId", variant.applicationId)
        inputs.property("variant.name", variant.name)
        inputs.property("variant.versionCode", variant.versionCode)
        inputs.property("variant.versionName", variant.versionName)

        val outputFile = variantDir.map { it.file("module.prop") }
        outputs.file(outputFile)

        doLast {
            val props = LinkedHashMap<String, String>()
            props["id"] = variant.applicationId
            props["name"] = rootProject.name
            props["version"] = variant.versionName
            props["versionCode"] = variant.versionCode.toString()
            props["author"] = "Pixel Updater contributors"
            props["description"] = "Pixel OTA updater"
            props["updateJson"] = "${projectUrl}/raw/update-links/${variant.name}.json"

            outputFile.get().asFile.writeText(
                props.map { "${it.key}=${it.value}" }.joinToString("\n"))
        }
    }

    val permissionsXml = tasks.register("permissionsXml${capitalized}") {
        inputs.property("variant.applicationId", variant.applicationId)

        val outputFile = variantDir.map { it.file("privapp-permissions-${variant.applicationId}.xml") }
        outputs.file(outputFile)

        doLast {
            outputFile.get().asFile.writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <permissions>
                    <privapp-permissions package="${variant.applicationId}">
                        <permission name="android.permission.ACCESS_CACHE_FILESYSTEM" />
                        <permission name="android.permission.MANAGE_CARRIER_OEM_UNLOCK_STATE" />
                        <permission name="android.permission.MANAGE_USER_OEM_UNLOCK_STATE" />
                        <permission name="android.permission.READ_OEM_UNLOCK_STATE" />
                        <permission name="android.permission.REBOOT" />
                    </privapp-permissions>
                </permissions>
            """.trimIndent())
        }
    }

    val configXml = tasks.register("configXml${capitalized}") {
        inputs.property("variant.applicationId", variant.applicationId)

        val outputFile = variantDir.map { it.file("config-${variant.applicationId}.xml") }
        outputs.file(outputFile)

        doLast {
            outputFile.get().asFile.writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <config>
                    <allow-in-power-save package="${variant.applicationId}" />
                    <hidden-api-whitelisted-app package="${variant.applicationId}" />
                </config>
            """.trimIndent())
        }
    }

    tasks.register<Zip>("zip${capitalized}") {
        inputs.property("rootProject.name", rootProject.name)
        inputs.property("variant.applicationId", variant.applicationId)
        inputs.property("variant.name", variant.name)
        inputs.property("variant.versionName", variant.versionName)

        archiveFileName.set("${rootProject.name}-${variant.versionName}-${variant.name}.zip")
        // Force instantiation of old value or else this will cause infinite recursion
        destinationDirectory.set(destinationDirectory.dir(variant.name).get())

        // Make the zip byte-for-byte reproducible (note that the APK is still not reproducible)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

        dependsOn.add(variant.assembleProvider)

        from(moduleProp.map { it.outputs })
        from(permissionsXml.map { it.outputs }) {
            into("system/etc/permissions")
        }
        from(configXml.map { it.outputs }) {
            into("system/etc/sysconfig")
        }
        from(variant.outputs.map { it.outputFile }) {
            into("system/priv-app/${rootProject.name}")
        }

        val moduleDir = File(projectDir, "module")

        for (script in arrayOf("update-binary", "updater-script")) {
            from(File(moduleDir, script)) {
                into("META-INF/com/google/android")
            }
        }

        from(File(moduleDir, "boot_common.sh"))
        from(File(moduleDir, "customize.sh"))
        from(File(moduleDir, "post-fs-data.sh"))

        from(File(rootDir, "LICENSE"))
        from(File(rootDir, "README.md"))
    }

    tasks.register("sign${capitalized}Zip") {
        dependsOn.add("zip${capitalized}")
        val output = tasks.named("zip${capitalized}").get().outputs.files.singleFile
        val sig = File("$output.sig")
        doLast {
            if (sig.exists()) {
                sig.delete()
            }
            exec {
                commandLine("ssh-keygen", "-Y", "sign", "-f", System.getenv("SIGNING_KEY"), "-P", System.getenv("SIGNING_KEY_PASSPHRASE"), "-n", "file", output)
            }
        }
    }

    tasks.register("push${capitalized}App") {
        inputs.property("rootProject.name", rootProject.name)
        inputs.property("variant.applicationId", variant.applicationId)

        dependsOn.add(variant.assembleProvider)

        val output = variant.outputs.map { it.outputFile }[0]
        doLast {
            val selectedDevice = getSelectedDevice()
            exec {
                commandLine(android.adbExecutable, "-s", selectedDevice, "push", output, "/data/local/tmp")
            }
            exec {
                commandLine(android.adbExecutable, "-s", selectedDevice, "shell", "su", "-c", "cp", "/data/local/tmp/${output.name}", "/data/adb/modules/${variant.applicationId}/system/priv-app/${rootProject.name}")
            }
            exec {
                commandLine(android.adbExecutable, "-s", selectedDevice, "shell", "am", "force-stop", variant.applicationId)
            }
            exec {
                commandLine(android.adbExecutable, "-s", selectedDevice, "shell", "am", "start", "-n", "${variant.applicationId}/${variant.applicationId}.settings.SettingsActivity")
            }
        }
    }

    tasks.register("push${capitalized}Zip") {
        dependsOn.add("zip${capitalized}")
        val output = tasks.named("zip${capitalized}").get().outputs.files.singleFile
        doLast {
            val selectedDevice = getSelectedDevice()
            exec {
                println("Pushing ${output.name} to /data/local/tmp on device $selectedDevice ...")
                commandLine(android.adbExecutable, "-s", selectedDevice, "push", output, "/data/local/tmp")
            }
        }
    }

    tasks.register("flash${capitalized}") {
        dependsOn.add("push${capitalized}Zip")
        val output = tasks.named("zip${capitalized}").get().outputs.files.singleFile
        doLast {
            val selectedDevice = getSelectedDevice()
            exec {
                println("Flashing ${output.name} to device $selectedDevice ...")
                commandLine(android.adbExecutable, "-s", selectedDevice, "shell", "su", "-c", "magisk --install-module /data/local/tmp/${output.name}")
            }
            exec {
                println("Rebooting device $selectedDevice ...")
                commandLine(android.adbExecutable, "-s", selectedDevice, "reboot")
            }
        }
    }

    tasks.register("updateJson${capitalized}") {
        inputs.property("projectUrl", projectUrl)
        inputs.property("rootProject.name", rootProject.name)
        inputs.property("variant.name", variant.name)
        inputs.property("variant.versionCode", variant.versionCode)
        inputs.property("variant.versionName", variant.versionName)

        val outputDir = tasks.named("zip${capitalized}").get().outputs.files.singleFile.parentFile.parentFile
        val jsonFile = File(outputDir, "${variant.name}.json")

        outputs.file(jsonFile)

        doLast {
            val root = JSONObject()
            root.put("version", variant.versionName)
            root.put("versionCode", variant.versionCode)
            root.put("zipUrl", "${projectUrl}/releases/download/${variant.versionName}/${rootProject.name}-${variant.versionName}-${variant.name}.zip")
            root.put("changelog", "${projectUrl}/raw/update-links/changelog.md")

            jsonFile.writer().use {
                root.write(it, 4, 0)
            }
        }
    }
}
