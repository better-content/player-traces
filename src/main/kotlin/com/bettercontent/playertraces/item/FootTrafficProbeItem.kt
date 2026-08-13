package com.bettercontent.playertraces.item

import com.bettercontent.playertraces.TracesMod
import com.bettercontent.playertraces.logic.TraceQueryService
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import kotlin.math.roundToInt

class FootTrafficProbeItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: net.minecraft.world.InteractionHand): InteractionResultHolder<ItemStack> {
        if (!level.isClientSide) {
            report(level, player)
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide)
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player ?: return InteractionResult.PASS
        if (!level.isClientSide) {
            report(level, player)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    private fun report(level: Level, player: Player) {
        if (level is net.minecraft.server.level.ServerLevel) {
            val potential = TraceQueryService().trafficPotential(level, player.blockPosition())
            val aliveDensity = if (potential.traceCount == 0) 0 else ((potential.aliveCount.toFloat() / potential.traceCount.toFloat()) * 100f).roundToInt()
            val mean = String.format("%.3f", potential.meanStrength)
            val msg = Component.literal(
                "Foot Traffic Probe => " +
                    "trace=${potential.traceCount}, alive=${potential.aliveCount}, " +
                    "aliveDensity=${aliveDensity}%, seq=${potential.sequenceCount}, max=${potential.maxStrength}, mean=$mean, " +
                    "local=${potential.localSurvivingStrength}, region=${potential.regionalSurvivingStrength}, " +
                    "server=${potential.serverSurvivingStrength}, regionShare=${potential.regionalShare}, " +
                    "serverShare=${potential.serverShare}, percentile=${potential.percentile}"
            )
                .withStyle(ChatFormatting.AQUA)
            player.displayClientMessage(msg, true)
        }
    }
}
