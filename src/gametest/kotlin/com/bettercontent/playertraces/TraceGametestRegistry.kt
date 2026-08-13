package com.bettercontent.playertraces

import net.minecraftforge.event.RegisterGameTestsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = TracesMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
object TraceGametestRegistry {
    @SubscribeEvent
    fun registerGameTests(event: RegisterGameTestsEvent) {
        event.register(TraceGametestProbe::class.java)
    }
}
