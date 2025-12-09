@file:Suppress("VulnerableLibrariesLocal")

import java.util.Date

plugins {
    `maven-publish`
    id("net.minecraftforge.gradle") version "6.0.+"
    id("org.parchmentmc.librarian.forgegradle") version "1.+"
    id("org.jetbrains.kotlin.jvm") version "2.0.0"
}

version = "1.0.0"
group = "com.mcdigital.ecosystem"
base.archivesName.set("mcdigitalecosystem")

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

println("Java: ${System.getProperty("java.version")}, JVM: ${System.getProperty("java.vm.version")} (${System.getProperty("java.vendor")}), Arch: ${System.getProperty("os.arch")}")

minecraft {
    mappings("parchment", "2023.06.26-1.20.1")
    
    runs {
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("forge.enabledGameTestNamespaces", "mcdigitalecosystem")
            mods {
                create("mcdigitalecosystem") {
                    source(sourceSets.main.get())
                }
            }
        }

        create("server") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("forge.enabledGameTestNamespaces", "mcdigitalecosystem")
            mods {
                create("mcdigitalecosystem") {
                    source(sourceSets.main.get())
                }
            }
        }

        create("gameTestServer") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            property("forge.enabledGameTestNamespaces", "mcdigitalecosystem")
            mods {
                create("mcdigitalecosystem") {
                    source(sourceSets.main.get())
                }
            }
        }

        create("data") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            args("--mod", "mcdigitalecosystem", "--all", "--output", file("src/generated/resources/"), "--existing", file("src/main/resources/"))
            mods {
                create("mcdigitalecosystem") {
                    source(sourceSets.main.get())
                }
            }
        }
    }
}

sourceSets.main.get().resources.srcDir("src/generated/resources")

repositories {
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
    }
    mavenCentral()
}

dependencies {
    minecraft("net.minecraftforge:forge:1.20.1-47.2.0")
    implementation("thedarkcolour:kotlinforforge:4.5.0")
    // VNC client is implemented directly using RFB protocol
    // No external dependency needed
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = listOf(
        "minecraft_version",
        "minecraft_version_range",
        "forge_version",
        "forge_version_range",
        "loader_version_range",
        "mod_id",
        "mod_name",
        "mod_version",
        "mod_authors",
        "mod_description"
    ).associateWith { project.findProperty(it) as String }

    inputs.properties(replaceProperties)

    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(replaceProperties + mapOf("project" to project))
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            mapOf(
                "Specification-Title" to project.findProperty("mod_id") as String,
                "Specification-Vendor" to project.findProperty("mod_authors") as String,
                "Specification-Version" to "1",
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to project.findProperty("mod_authors") as String,
                "Implementation-Timestamp" to Date().toString()
            )
        )
    }
    
    // Bundle QEMU binaries from system installation
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    
    val qemuBinaryName = when {
        os.contains("win") -> "qemu-system-x86_64.exe"
        else -> "qemu-system-x86_64"
    }
    val qemuImgName = qemuBinaryName.replace("qemu-system-x86_64", "qemu-img")
    
    val qemuResourceDir = when {
        os.contains("win") -> "qemu/windows"
        os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "qemu/macos-arm64"
        os.contains("mac") -> "qemu/macos-x86_64"
        else -> "qemu/linux"
    }
    
    // Try to find QEMU from common locations
    val possibleQemuPaths = when {
        os.contains("mac") -> listOf("/opt/homebrew/bin/$qemuBinaryName", "/usr/local/bin/$qemuBinaryName")
        os.contains("win") -> listOf("C:\\Program Files\\qemu\\$qemuBinaryName")
        else -> listOf("/usr/bin/$qemuBinaryName", "/usr/local/bin/$qemuBinaryName")
    }
    
    for (qemuPath in possibleQemuPaths) {
        val qemuFile = file(qemuPath)
        if (qemuFile.exists()) {
            from(qemuFile) {
                into(qemuResourceDir)
                rename { qemuBinaryName }
            }
            println("Bundling QEMU from: $qemuPath")
            
            // Also bundle qemu-img
            val qemuImgFile = file(qemuPath.replace(qemuBinaryName, qemuImgName))
            if (qemuImgFile.exists()) {
                from(qemuImgFile) {
                    into(qemuResourceDir)
                    rename { qemuImgName }
                }
                println("Bundling qemu-img from: ${qemuImgFile.absolutePath}")
            }
            break
        }
    }
    
    finalizedBy("reobfJar")
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            artifact(tasks.jar.get())
        }
    }
    repositories {
        maven {
            url = uri("file://${project.projectDir}/mcmodsrepo")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    kotlinOptions {
        jvmTarget = "17"
    }
}
