package com.bettercontent.playertraces.client

import net.minecraftforge.fml.ModList

object QuarkEmoteBridge {
    fun availableEmotes(): List<String> {
        if (!ModList.get().isLoaded("quark")) return emptyList()
        return runCatching {
            val handler = Class.forName("org.violetmoon.quark.content.tweaks.client.emote.EmoteHandler")
            val map = field(handler, "emoteMap").get(null) as Map<*, *>
            val reward = Class.forName("org.violetmoon.quark.base.handler.ContributorRewardHandler")
            val localTier = (field(reward, "localPatronTier").get(null) as Number).toInt()
            map.entries.mapNotNull { (key, descriptor) ->
                val tier = descriptor?.javaClass?.getMethod("getTier")?.invoke(descriptor) as? Int ?: return@mapNotNull null
                key?.toString()?.takeIf { tier <= localTier }
            }.distinct().sorted()
        }.onFailure { TracesClientLog.LOGGER.warn("Could not enumerate this client's Quark emotes", it) }.getOrDefault(emptyList())
    }

    fun request(emote: String) {
        require(emote in availableEmotes()) { "the selected Quark emote is no longer available" }
        runCatching {
            val messageClass = Class.forName("org.violetmoon.quark.base.network.message.RequestEmoteMessage")
            val message = messageClass.getConstructor(String::class.java).newInstance(emote)
            val clientClass = Class.forName("org.violetmoon.quark.base.QuarkClient")
            val zetaClient = field(clientClass, "ZETA_CLIENT").get(null)
            val send = zetaClient.javaClass.methods.first { it.name == "sendToServer" && it.parameterCount == 1 }
            send.invoke(zetaClient, message)
        }.getOrElse { throw IllegalStateException("Quark could not start the selected emote", it) }
    }

    private fun field(type: Class<*>, name: String): java.lang.reflect.Field =
        runCatching { type.getField(name) }.getOrElse { type.getDeclaredField(name).also { it.isAccessible = true } }
}
