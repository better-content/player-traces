plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("net.minecraftforge.gradle") version "6.0.24"
}

val minecraftVersion = "1.20.1"
val forgeVersion = "47.4.0"
val kotlinVersion = "1.9.24"
val visualValidationRun = providers.gradleProperty("tracesVisual").orNull == "true"
val shaderCompatibilityValidation = providers.gradleProperty("tracesShaderCompat").orNull == "true"

base {
    archivesName.set("traces")
}

version = "0.1.0"
group = "com.bettercontent"

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net")
    if (shaderCompatibilityValidation) {
        flatDir {
            dirs(layout.buildDirectory.dir("shader-compat/deps"))
        }
    }
}

dependencies {
    minecraft("net.minecraftforge:forge:${minecraftVersion}-${forgeVersion}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    minecraftLibrary("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    minecraftLibrary("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    minecraftLibrary("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    minecraftLibrary("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    minecraftLibrary("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    if (shaderCompatibilityValidation) {
        runtimeOnly(fg.deobf("shader.compat:embeddium:0.3.31+mc1.20.1"))
        runtimeOnly(fg.deobf("shader.compat:oculus-mc1.20.1:1.8.0"))
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

sourceSets {
    create("gametest") {
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath + sourceSets["test"].runtimeClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].runtimeClasspath + sourceSets["main"].runtimeClasspath + sourceSets["gametest"].output
        kotlin {
            setSrcDirs(listOf("src/gametest/kotlin"))
        }
        resources {
            setSrcDirs(listOf("src/gametest/resources"))
        }
    }
}

configurations["gametestImplementation"].extendsFrom(configurations["testImplementation"])

minecraft {
    mappings("official", minecraftVersion)

    runs {
        create("client") {
            workingDirectory(project.file(if (visualValidationRun) "build/visual-run" else "run"))
            if (visualValidationRun) {
                args("--quickPlaySingleplayer", "Traces Visual", "--width", "1280", "--height", "720")
                property("traces.visualValidation", "true")
            }
            if (shaderCompatibilityValidation) {
                property("mixin.env.remapRefMap", "true")
                property("mixin.env.refMapRemappingFile", project.file("build/createSrgToMcp/output.srg").absolutePath)
            }
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods {
                create("traces") {
                    source(sourceSets["main"])
                    source(sourceSets["test"])
                }
            }
        }

        create("server") {
            workingDirectory(project.file("run"))
            args("--nogui")
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods {
                create("traces") {
                    source(sourceSets["main"])
                    source(sourceSets["test"])
                }
            }
        }

        create("gameTestServer") {
            workingDirectory(project.file("run"))
            args("--nogui")
            property("forge.enabledGameTestNamespaces", "traces")
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods {
                create("traces") {
                    source(sourceSets["main"])
                    source(sourceSets["gametest"])
                }
            }
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }

    register("verifyFast") {
        group = "verification"
        description = "Compile + unit checks for fast feedback"
        dependsOn("compileKotlin", "compileJava", "test")
    }

    register("headlessGameTest") {
        group = "verification"
        description = "Run dedicated-server gametests headless"
        dependsOn("runGameTestServer")
    }

    register<Copy>("prepareGametestStructures") {
        from("src/gametest/resources/gameteststructures")
        into("run/gameteststructures")
        include("*.snbt")
    }

    register("verifyFull") {
        group = "verification"
        description = "Fast verify and headless GameTest"
        dependsOn("verifyFast", "headlessGameTest")
    }

    register<Sync>("prepareVisualValidation") {
        group = "verification"
        description = "Create an isolated, trace-clean headed visual validation run"
        from("run/saves/New World")
        into(layout.buildDirectory.dir("visual-run/saves/Traces Visual"))
        exclude("session.lock", "data/traces/**")
        doLast {
            val runDir = layout.buildDirectory.dir("visual-run").get().asFile
            val clientConfig = runDir.resolve("config/traces-client.toml")
            clientConfig.parentFile.mkdirs()
            clientConfig.writeText("""
                revealByDefault = false
                guidanceStrengthFloor = 0.03
                referenceDensity = 8.0
                minVisibleAlpha = 0.07
                maxRenderDistance = 6
                maxRenderedMarks = 220
                guidancePulseSpeed = 0.08
                annotationLabelDistance = 32
                visualDiagnostics = true
                worldDesaturation = 0.8
            """.trimIndent() + "\n")
            val serverConfig = runDir.resolve("saves/Traces Visual/serverconfig/traces-server.toml")
            serverConfig.parentFile.mkdirs()
            serverConfig.writeText("""
                saveQueueMax = 64
                shardCacheSize = 128
                referenceDensity = 8.0
                minVisibleAlpha = 0.07
                maxRenderDistance = 6
                rainExposureFactor = 0.91
                devVisualFixture = true
                maxPayloadTraces = 512
            """.trimIndent() + "\n")
        }
    }
}

tasks.matching {
    it.name == "runGameTestServer"
}.configureEach {
    dependsOn("prepareGametestStructures")
}
