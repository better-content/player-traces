package com.bettercontent.traces.config

import net.minecraftforge.common.ForgeConfigSpec

object TracesConfig {
    private val serverBuilder = ForgeConfigSpec.Builder()
    val common: Common = Common(serverBuilder)
    val serverSpec: ForgeConfigSpec = serverBuilder.build()

    private val clientBuilder = ForgeConfigSpec.Builder()
    val client: Client = Client(clientBuilder)
    val clientSpec: ForgeConfigSpec = clientBuilder.build()

    class Common(builder: ForgeConfigSpec.Builder) {
        val saveQueueMax = builder.defineInRange("saveQueueMax", 64, 1, 4096)
        val shardCacheSize = builder.defineInRange("shardCacheSize", 128, 16, 2048)
        val referenceDensity = builder.defineInRange("referenceDensity", 8.0, 1.0, 64.0)
        val minVisibleAlpha = builder.defineInRange("minVisibleAlpha", 0.07, 0.0, 1.0)
        val maxRenderDistance = builder.defineInRange("maxRenderDistance", 6, 1, 32)
        val rainExposureFactor = builder.defineInRange("rainExposureFactor", 0.91, 0.01, 1.0)
        val devVisualFixture = builder.define("devVisualFixture", false)
        val maxPayloadTraces = builder.defineInRange("maxPayloadTraces", 512, 64, 4096)
        val maxPayloadAnnotations = builder.defineInRange("maxPayloadAnnotations", 256, 16, 4096)
        val maxBloodPools = builder.defineInRange("maxBloodPoolsPerDimension", 512, 16, 4096)
        val maxDeathEchoes = builder.defineInRange("maxDeathEchoesPerDimension", 128, 8, 1024)
        val maxDeathEchoesPerPlayer = builder.defineInRange("maxDeathEchoesPerPlayer", 8, 1, 64)
        val maxPayloadBloodPools = builder.defineInRange("maxPayloadBloodPools", 64, 1, 256)
        val maxPayloadDeathEchoes = builder.defineInRange("maxPayloadDeathEchoes", 8, 1, 32)
    }

    class Client(builder: ForgeConfigSpec.Builder) {
        val enableRevealDefault = builder.define("revealByDefault", false)
        val guidanceStrengthFloor = builder.defineInRange("guidanceStrengthFloor", 0.03, 0.0, 1.0)
        val referenceDensity = builder.defineInRange("referenceDensity", 8.0, 1.0, 64.0)
        val minVisibleAlpha = builder.defineInRange("minVisibleAlpha", 0.07, 0.0, 1.0)
        val maxRenderDistance = builder.defineInRange("maxRenderDistance", 6, 1, 16)
        val maxRenderedMarks = builder.defineInRange("maxRenderedMarks", 220, 32, 1000)
        val guidancePulseSpeed = builder.defineInRange("guidancePulseSpeed", 0.08, 0.01, 1.0)
        val annotationLabelDistance = builder.defineInRange("annotationLabelDistance", 18, 4, 64)
        val visualDiagnostics = builder.define("visualDiagnostics", false)
    }
}
