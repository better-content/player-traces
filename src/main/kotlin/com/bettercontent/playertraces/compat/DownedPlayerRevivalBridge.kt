package com.bettercontent.playertraces.compat

import net.minecraft.world.entity.player.Player
import net.minecraftforge.fml.ModList
import java.util.concurrent.atomic.AtomicBoolean

/** Optional integration gate that keeps Revival classes unloaded when the provider is absent. */
object DownedPlayerRevivalBridge {
    private const val MOD_ID = "downed_player_revival"
    private val registered = AtomicBoolean()

    fun isDowned(player: Player): Boolean {
        if (!ModList.get().isLoaded(MOD_ID)) return false
        return DownedPlayerRevivalIntegration.isDowned(player)
    }

    fun registerIfPresent() {
        if (!ModList.get().isLoaded(MOD_ID) || !registered.compareAndSet(false, true)) return
        DownedPlayerRevivalIntegration.register()
    }
}
