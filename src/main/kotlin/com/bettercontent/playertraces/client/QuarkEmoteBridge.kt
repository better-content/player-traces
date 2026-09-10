package com.bettercontent.playertraces.client

import net.minecraftforge.fml.ModList

object QuarkEmoteBridge {
    fun availableEmotes(): List<String> {
        if (!ModList.get().isLoaded("quark")) return emptyList()
        return QuarkEmoteIntegration.availableEmotes()
    }

    fun request(emote: String) {
        require(emote in availableEmotes()) { "the selected Quark emote is no longer available" }
        QuarkEmoteIntegration.request(emote)
    }
}
