package com.bettercontent.traces.prototype

import dev.kosmx.playerAnim.api.layered.PlayerAnimationFrame
import dev.kosmx.playerAnim.core.util.Pair
import dev.kosmx.playerAnim.core.util.Vec3f
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess
import kotlin.math.sin

internal object SyntheticBendAnimation {
    var enabled: Boolean = false
        private set

    fun register() {
        PlayerAnimationAccess.REGISTER_ANIMATION_EVENT.register { _, stack ->
            stack.addAnimLayer(10_000, Layer())
        }
    }

    fun toggle(): Boolean {
        enabled = !enabled
        return enabled
    }

    private class Layer : PlayerAnimationFrame() {
        private var ticks = 0f

        override fun tick() {
            ticks += 1f
        }

        override fun isActive(): Boolean = enabled

        override fun setupAnim(tickDelta: Float) {
            resetPose()
            if (!enabled) return
            val wave = sin((ticks + tickDelta) * 0.16f)
            body.bend = Pair(0.75f + wave * 0.18f, 0f)
            body.rot = Vec3f(0f, wave * 0.18f, 0f)
            rightArm.rot = Vec3f(-1.35f + wave * 0.35f, 0.15f, 0.45f)
            leftArm.rot = Vec3f(-0.35f - wave * 0.2f, -0.15f, -0.2f)
            head.rot = Vec3f(0.1f, wave * 0.25f, 0f)
        }
    }
}
