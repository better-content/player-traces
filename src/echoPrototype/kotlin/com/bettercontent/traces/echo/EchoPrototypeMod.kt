package com.bettercontent.traces.prototype

import com.bettercontent.traces.echo.EchoClip

import net.minecraft.client.KeyMapping
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import org.slf4j.LoggerFactory
import org.lwjgl.glfw.GLFW

@Mod(EchoPrototypeMod.MOD_ID)
class EchoPrototypeMod {
    init {
        FMLJavaModLoadingContext.get().modEventBus.addListener(EchoPrototypeKeys::register)
        MinecraftForge.EVENT_BUS.register(EchoPrototypeController)
        MinecraftForge.EVENT_BUS.register(EchoPoseCapture)
        SyntheticBendAnimation.register()
        LoggerFactory.getLogger("TracesEchoPrototype").info("ECHO_PROTOTYPE_READY controls=F4,F6,F7,F8 sampleRate={}", EchoClip.SAMPLE_RATE)
    }

    companion object {
        const val MOD_ID = "traces_echo_prototype"
    }
}

internal object EchoPrototypeKeys {
    val quarkWave = KeyMapping("key.traces_echo_prototype.quark_wave", GLFW.GLFW_KEY_F4, CATEGORY)
    val syntheticBend = KeyMapping("key.traces_echo_prototype.synthetic", GLFW.GLFW_KEY_F6, CATEGORY)
    val duration = KeyMapping("key.traces_echo_prototype.duration", GLFW.GLFW_KEY_F7, CATEGORY)
    val record = KeyMapping("key.traces_echo_prototype.record", GLFW.GLFW_KEY_F8, CATEGORY)

    fun register(event: RegisterKeyMappingsEvent) {
        event.register(quarkWave)
        event.register(syntheticBend)
        event.register(duration)
        event.register(record)
    }

    private const val CATEGORY = "key.categories.traces_echo_prototype"
}
