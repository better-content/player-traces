package com.bettercontent.playertraces

import com.bettercontent.playertraces.config.TracesConfig
import com.bettercontent.playertraces.events.TracesForgeEvents
import com.bettercontent.playertraces.commands.TracesCommands
import com.bettercontent.playertraces.item.TracesItems
import com.bettercontent.playertraces.logic.TraceQueryService
import com.bettercontent.playertraces.network.TracesNetwork
import com.bettercontent.playertraces.server.TraceServerRuntime
import com.bettercontent.playertraces.storage.TraceStorageManager
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import java.util.WeakHashMap

@Mod(TracesMod.MOD_ID)
class TracesMod {
    init {
        val modBus = FMLJavaModLoadingContext.get().modEventBus
        TracesItems.REGISTRY.register(modBus)
        MinecraftForge.EVENT_BUS.register(TracesForgeEvents)
        MinecraftForge.EVENT_BUS.register(TracesCommands)

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, TracesConfig.serverSpec)
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, TracesConfig.clientSpec)

        TracesNetwork.register()
    }

    companion object {
        const val MOD_ID = "player_traces"

        private val runtimes = WeakHashMap<MinecraftServer, TraceServerRuntime>()

        fun getRuntime(server: MinecraftServer): TraceServerRuntime {
            return runtimes.getOrPut(server) { TraceServerRuntime(server) }
        }

        fun removeRuntime(server: MinecraftServer) {
            runtimes.remove(server)?.close()
        }

        fun storageFor(server: MinecraftServer, level: ServerLevel): TraceStorageManager =
            getRuntime(server).storage(level)

        fun queryService(): TraceQueryService = TraceQueryService()
    }
}
