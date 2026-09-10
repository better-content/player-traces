package com.bettercontent.playertraces.client

import org.violetmoon.quark.base.QuarkClient
import org.violetmoon.quark.base.handler.ContributorRewardHandler
import org.violetmoon.quark.base.network.message.RequestEmoteMessage
import org.violetmoon.quark.content.tweaks.client.emote.EmoteHandler

/** Loaded only after Forge confirms the pinned Quark/Zeta client API is present. */
internal object QuarkEmoteIntegration {
    fun availableEmotes(): List<String> = EmoteHandler.emoteMap.entries
        .filter { it.value.tier <= ContributorRewardHandler.localPatronTier }
        .map { it.key }
        .distinct()
        .sorted()

    fun request(emote: String) {
        QuarkClient.ZETA_CLIENT.sendToServer(RequestEmoteMessage(emote))
    }
}
