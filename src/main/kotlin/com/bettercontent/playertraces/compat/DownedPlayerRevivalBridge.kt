package com.bettercontent.playertraces.compat

import com.bettercontent.playertraces.TracesMod
import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.fml.ModList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Optional integration which deliberately avoids a compile-time dependency on the revival mod. */
object DownedPlayerRevivalBridge {
    private const val MOD_ID = "downed_player_revival"
    private const val DOWNED_EVENT = "com.bettercontent.downedplayerrevival.api.event.PlayerDownedEvent"
    private const val REVIVED_EVENT = "com.bettercontent.downedplayerrevival.api.event.PlayerRevivedEvent"
    private val log = LogUtils.getLogger()
    private val registered = AtomicBoolean()

    fun isDowned(player: Player): Boolean {
        if (!ModList.get().isLoaded(MOD_ID)) return false
        return runCatching {
            val api = Class.forName("com.bettercontent.downedplayerrevival.api.RevivalApi")
            api.getMethod("isDowned", Player::class.java).invoke(null, player) as Boolean
        }.getOrElse {
            log.warn("Could not inspect optional {} downed state", MOD_ID, it)
            false
        }
    }

    fun registerIfPresent() {
        if (!ModList.get().isLoaded(MOD_ID) || !registered.compareAndSet(false, true)) return
        runCatching {
            register(DOWNED_EVENT) { player -> TracesMod.getRuntime(player.server).onPlayerDowned(player) }
            register(REVIVED_EVENT) { player -> TracesMod.getRuntime(player.server).onPlayerRevived(player) }
        }.onSuccess {
            log.info("Enabled optional pre-down death capture integration for {}", MOD_ID)
        }.onFailure {
            registered.set(false)
            log.error("Could not register optional {} event integration", MOD_ID, it)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun register(className: String, handler: (ServerPlayer) -> Unit) {
        val eventClass = Class.forName(className).asSubclass(Event::class.java) as Class<Event>
        MinecraftForge.EVENT_BUS.addListener(
            EventPriority.NORMAL,
            false,
            eventClass,
            Consumer { event ->
                val player = (event as? PlayerEvent)?.entity as? ServerPlayer ?: return@Consumer
                handler(player)
            },
        )
    }
}
