package com.bettercontent.traces.client

import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

object TracesClientConfig {
    val revealToggle = KeyMapping("key.traces.reveal", GLFW.GLFW_KEY_G, "key.categories.traces")
    val placeAnnotation = KeyMapping("key.traces.annotation", GLFW.GLFW_KEY_N, "key.categories.traces")
}
