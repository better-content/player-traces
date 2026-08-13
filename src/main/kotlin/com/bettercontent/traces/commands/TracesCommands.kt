package com.bettercontent.traces.commands

import com.bettercontent.traces.TracesMod
import com.bettercontent.traces.logic.TraceQueryService
import com.google.gson.GsonBuilder
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.UUID

import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
object TracesCommands {
    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        val dispatcher = event.dispatcher
        dispatcher.register(
            Commands.literal("traces")
                .then(
                    Commands.literal("debug")
                        .then(
                            Commands.literal("stats").executes { ctx ->
                                val server = ctx.source.server
                                val runtime = TracesMod.getRuntime(server)
                                ctx.source.sendSystemMessage(Component.literal("active levels: ${runtime.storageCount()}"))
                                1
                            }
                        )
                        .then(
                            Commands.literal("nearby").executes { ctx ->
                                val source = ctx.source
                                val player = source.player ?: return@executes 0
                                val level = player.serverLevel()
                                val min = player.blockPosition().offset(-24, 0, -24)
                                val max = player.blockPosition().offset(24, 255, 24)
                                val storage = TracesMod.storageFor(source.server, level)
                                val count = storage.queryAnnotations(min, max).size
                                source.sendSystemMessage(Component.literal("annotations nearby: ${count}"))
                                1
                            }
                        )
                        .then(
                            Commands.literal("probe").executes { ctx ->
                                val source = ctx.source
                                val player = source.player ?: return@executes 0
                                val query = TraceQueryService().trafficPotential(player.serverLevel(), player.blockPosition())
                                val msg = "trace probe traces=${query.traceCount} alive=${query.aliveCount} seq=${query.sequenceCount} mean=${query.meanStrength} " +
                                    "localStrength=${query.localSurvivingStrength} regionStrength=${query.regionalSurvivingStrength} serverStrength=${query.serverSurvivingStrength} " +
                                    "regionalShare=${query.regionalShare} serverShare=${query.serverShare} pct=${query.percentile}"
                                source.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GREEN))
                                1
                            }
                        )
                        .then(
                            Commands.literal("storage").executes { ctx ->
                                val runtime = TracesMod.getRuntime(ctx.source.server)
                                sourceStorageLine(ctx.source, runtime.storageCount())
                                1
                            }
                        )
                        .then(
                            Commands.literal("graph").executes { ctx ->
                                ctx.source.sendSystemMessage(Component.literal("guidance graph active, no synthetic routes emitted"))
                                1
                            }
                        )
                        .then(
                            Commands.literal("annotation").then(
                                Commands.argument("id", StringArgumentType.string()).executes { ctx ->
                                    val id = runCatching { UUID.fromString(StringArgumentType.getString(ctx, "id")) }.getOrNull()
                                    if (id == null) {
                                        ctx.source.sendSystemMessage(Component.literal("annotation id is not a valid uuid"))
                                        return@executes 0
                                    }
                                    val player = ctx.source.player ?: return@executes 0
                                    val runtime = TracesMod.getRuntime(player.server)
                                    val annotation = runtime.storage(player.serverLevel()).annotationById(id)
                                    if (annotation == null) {
                                        ctx.source.sendSystemMessage(Component.literal("annotation not found"))
                                    } else {
                                        val msg = "annotation ${annotation.id} revision=${annotation.revision} text=${annotation.text}"
                                        ctx.source.sendSystemMessage(Component.literal(msg))
                                    }
                                    1
                                }
                            )
                        )
                )
                .then(
                    Commands.literal("export").then(
                        Commands.argument("format", StringArgumentType.word())
                            .executes { ctx ->
                                val player = ctx.source.player ?: return@executes 0
                                val fmt = StringArgumentType.getString(ctx, "format").lowercase(Locale.ROOT)
                                val level = player.serverLevel()
                                val runtime = TracesMod.getRuntime(player.server)
                                val min = player.blockPosition().offset(-160, 0, -160)
                                val max = player.blockPosition().offset(160, 255, 160)
                                val traces = runtime.storage(level).queryTraces(min, max)
                                when (fmt) {
                                    "json" -> {
                                        val obj = mapOf(
                                            "traceCount" to traces.size,
                                            "avgStrength" to (traces.map { it.strength }.average().takeIf { !it.isNaN() } ?: 0.0),
                                        )
                                        val json = GsonBuilder().setPrettyPrinting().create().toJson(obj)
                                        ctx.source.sendSystemMessage(Component.literal(json))
                                    }
                                    "csv" -> {
                                        val lines = StringBuilder("id,x,y,z,strength\n")
                                        traces.forEach { t ->
                                            lines.append(t.id).append(',').append(t.blockPos.x).append(',').append(t.blockPos.y)
                                                .append(',').append(t.blockPos.z).append(',').append(t.strength).append('\n')
                                        }
                                        ctx.source.sendSystemMessage(Component.literal(lines.toString()))
                                    }
                                    else -> {
                                        ctx.source.sendSystemMessage(Component.literal("format must be json or csv"))
                                    }
                                }
                                1
                            }
                    )
                )
                .then(
                    Commands.literal("dev")
                        .requires { it.hasPermission(2) || com.bettercontent.traces.config.TracesConfig.common.devVisualFixture.get() || java.lang.Boolean.getBoolean("traces.visualValidation") }
                        .then(
                            Commands.literal("preparecapture")
                                .executes { ctx ->
                                    val player = ctx.source.player ?: return@executes 0
                                    TracesMod.getRuntime(player.server).preparePlayerCaptureFixture(player.serverLevel(), player)
                                    ctx.source.sendSuccess({ Component.literal("player capture fixture prepared") }, false)
                                    1
                                }
                        )
                        .then(
                            Commands.literal("captureyaw")
                                .then(Commands.argument("degrees", FloatArgumentType.floatArg(-180f, 180f))
                                    .executes { ctx ->
                                        val player = ctx.source.player ?: return@executes 0
                                        TracesMod.getRuntime(player.server).setPlayerCaptureYaw(
                                            player.serverLevel(), player, FloatArgumentType.getFloat(ctx, "degrees"),
                                        )
                                        1
                                    }
                                )
                        )
                        .then(
                            Commands.literal("verifycapture")
                                .executes { ctx ->
                                    val player = ctx.source.player ?: return@executes 0
                                    TracesMod.getRuntime(player.server).verifyPlayerCaptureFixture(player.serverLevel(), player)
                                    ctx.source.sendSuccess({ Component.literal("player capture verified") }, false)
                                    1
                                }
                        )
                        .then(
                            Commands.literal("die")
                                .executes { ctx ->
                                    val player = ctx.source.player ?: return@executes 0
                                    player.kill()
                                    1
                                }
                        )
                        .then(
                            Commands.literal("deathview")
                                .executes { ctx ->
                                    val player = ctx.source.player ?: return@executes 0
                                    TracesMod.getRuntime(player.server).prepareDeathTraceView(player.serverLevel(), player)
                                    1
                                }
                        )
                        .then(
                            Commands.literal("fixture")
                                .executes { ctx ->
                                    if (!com.bettercontent.traces.config.TracesConfig.common.devVisualFixture.get() && !java.lang.Boolean.getBoolean("traces.visualValidation")) {
                                        ctx.source.sendFailure(Component.literal("devVisualFixture is disabled"))
                                        return@executes 0
                                    }
                                    val player = ctx.source.player ?: return@executes 0
                                    TracesMod.getRuntime(player.server).seedVisualFixture(player.serverLevel(), player)
                                    ctx.source.sendSuccess({ Component.literal("visual fixture seeded") }, true)
                                    1
                                }
                        )
                        .then(
                            Commands.literal("connected")
                                .executes { ctx ->
                                    if (!com.bettercontent.traces.config.TracesConfig.common.devVisualFixture.get() && !java.lang.Boolean.getBoolean("traces.visualValidation")) {
                                        ctx.source.sendFailure(Component.literal("devVisualFixture is disabled"))
                                        return@executes 0
                                    }
                                    val player = ctx.source.player ?: return@executes 0
                                    TracesMod.getRuntime(player.server).connectVisualFixture(player.serverLevel(), player)
                                    ctx.source.sendSuccess({ Component.literal("visual fixture connected") }, false)
                                    1
                                }
                        )
                        .then(
                            Commands.literal("disconnected")
                                .executes { ctx ->
                                    if (!com.bettercontent.traces.config.TracesConfig.common.devVisualFixture.get() && !java.lang.Boolean.getBoolean("traces.visualValidation")) {
                                        ctx.source.sendFailure(Component.literal("devVisualFixture is disabled"))
                                        return@executes 0
                                    }
                                    val player = ctx.source.player ?: return@executes 0
                                    TracesMod.getRuntime(player.server).disconnectVisualFixture(player.serverLevel(), player)
                                    ctx.source.sendSuccess({ Component.literal("visual fixture disconnected") }, false)
                                    1
                                }
                        )
                        .then(
                            Commands.literal("occlusion")
                                .executes { ctx ->
                                    if (!com.bettercontent.traces.config.TracesConfig.common.devVisualFixture.get() && !java.lang.Boolean.getBoolean("traces.visualValidation")) {
                                        ctx.source.sendFailure(Component.literal("devVisualFixture is disabled"))
                                        return@executes 0
                                    }
                                    val player = ctx.source.player ?: return@executes 0
                                    TracesMod.getRuntime(player.server).occludeVisualFixture(player.serverLevel())
                                    ctx.source.sendSuccess({ Component.literal("visual occlusion fixture placed") }, false)
                                    1
                                }
                        )
                )
        )
    }

    private fun sourceStorageLine(source: CommandSourceStack, levels: Int) {
        source.sendSystemMessage(Component.literal("storage dimensions loaded: $levels"))
    }
}
