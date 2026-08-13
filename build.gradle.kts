plugins {
    kotlin("jvm") version "2.2.21"
    id("net.minecraftforge.gradle") version "6.0.24"
}

val minecraftVersion = "1.20.1"
val forgeVersion = "47.4.13"
val visualValidationRun = providers.gradleProperty("tracesVisual").orNull == "true"
val shaderCompatibilityValidation = providers.gradleProperty("tracesShaderCompat").orNull == "true"
val echoPrototypeEnabled = providers.gradleProperty("tracesModCacheDir").isPresent

base {
    archivesName.set("traces")
}

version = "0.1.0"
group = "com.bettercontent"

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    providers.gradleProperty("tracesModCacheDir").orNull?.let { modCacheDir ->
        flatDir {
            dirs(file(modCacheDir))
        }
    }
    if (shaderCompatibilityValidation) {
        flatDir {
            dirs(layout.buildDirectory.dir("shader-compat/deps"))
        }
    }
}

dependencies {
    minecraft("net.minecraftforge:forge:${minecraftVersion}-${forgeVersion}")
    implementation("thedarkcolour:kotlinforforge:4.12.0")
    if (shaderCompatibilityValidation) {
        runtimeOnly(fg.deobf("shader.compat:embeddium:0.3.31+mc1.20.1"))
        runtimeOnly(fg.deobf("shader.compat:oculus-mc1.20.1:1.8.0"))
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

sourceSets {
    create("echoPrototype") {
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath + output
        kotlin {
            setSrcDirs(listOf("src/echoPrototype/kotlin"))
        }
        resources {
            setSrcDirs(listOf("src/echoPrototype/resources"))
        }
    }

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
configurations["echoPrototypeImplementation"].extendsFrom(configurations["implementation"])
configurations["echoPrototypeRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

dependencies {
    if (echoPrototypeEnabled) {
        "echoPrototypeRuntimeOnly"(fg.deobf("echo.prototype:Zeta:1.0-31"))
        "echoPrototypeRuntimeOnly"(fg.deobf("echo.prototype:Quark:4.0-462"))
        "echoPrototypeImplementation"(fg.deobf("echo.prototype:player-animation-lib-forge:1.0.2-rc1+1.20"))
        testImplementation(sourceSets["echoPrototype"].output)
    }
}

if (!echoPrototypeEnabled) {
    sourceSets["test"].kotlin.exclude("**/EchoPrototypeTest.kt")
    tasks.configureEach {
        if (name.contains("EchoPrototype", ignoreCase = true)) enabled = false
    }
}

minecraft {
    mappings("official", minecraftVersion)

    runs {
        val baseClient = create("client") {
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

        create("echoPrototypeClient") {
            parent(baseClient)
            workingDirectory(project.file("build/echo-prototype-run"))
            args("--quickPlaySingleplayer", "Echo Prototype", "--width", "1280", "--height", "720")
            property("traces.echoPrototype", "true")
            property("traces.visualValidation", "true")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", project.file("build/createSrgToMcp/output.srg").absolutePath)
            property("forge.logging.console.level", "info")
            mods {
                create("traces_echo_prototype") {
                    source(sourceSets["echoPrototype"])
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
    jar {
        // Keep the development artifact from ever sharing the pack-facing filename.
        // ForgeGradle's reobfJar consumes this classifier just fine, while the
        // staged runtime JAR below remains the only unclassified artifact.
        archiveClassifier.set("dev")
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    register("verifyFast") {
        group = "verification"
        description = "Compile + unit checks for fast feedback"
        dependsOn("compileKotlin", "compileJava", "test")
    }

    register("verifyEchoPrototype") {
        group = "verification"
        description = "Compile the non-shipping echo prototype and run its focused codec tests"
        dependsOn("compileEchoPrototypeKotlin", "test")
    }

    register<Sync>("prepareEchoPrototypeWorld") {
        group = "verification"
        description = "Prepare the disposable one-world echo prototype client"
        from("run/saves/New World")
        into(layout.buildDirectory.dir("echo-prototype-run/saves/Echo Prototype"))
        exclude("session.lock", "data/traces/**")
        doLast {
            val options = layout.buildDirectory.file("echo-prototype-run/options.txt").get().asFile
            options.parentFile.mkdirs()
            if (!options.exists()) options.writeText("tutorialStep:none\n")
        }
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

val stageRuntimeJar by tasks.registering(Copy::class) {
    group = "build"
    description = "Stages the reobfuscated runtime jar under the canonical pack filename."
    dependsOn(tasks.named("reobfJar"))
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "traces-$version.jar" }
}

tasks.named("assemble") {
    dependsOn(stageRuntimeJar)
}

tasks.matching {
    it.name == "runGameTestServer"
}.configureEach {
    dependsOn("prepareGametestStructures")
}

tasks.matching {
    it.name == "runEchoPrototypeClient"
}.configureEach {
    dependsOn("prepareEchoPrototypeWorld")
}
