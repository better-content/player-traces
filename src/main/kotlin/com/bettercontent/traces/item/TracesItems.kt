package com.bettercontent.traces.item

import com.bettercontent.traces.TracesMod
import net.minecraft.world.item.Item
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object TracesItems {
    val REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, TracesMod.MOD_ID)

    val PROBE_ITEM: RegistryObject<Item> = REGISTRY.register("foot_traffic_probe") {
        FootTrafficProbeItem(Item.Properties().stacksTo(1))
    }
}
