package com.daisychain.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Item tags Daisychain reads.
 *
 * On 1.20.1 Forge, mods put their wrenches in forge:tools/wrench (most: Mekanism, AE2, Pipez, EnderIO, LaserIO...)
 * and some only in forge:wrenches. Both count as a wrench.
 */
public final class ModTags {
    public static final TagKey<Item> WRENCHES =
            TagKey.create(Registries.ITEM, new ResourceLocation("forge", "tools/wrench"));
    public static final TagKey<Item> WRENCHES_ALT =
            TagKey.create(Registries.ITEM, new ResourceLocation("forge", "wrenches"));

    private ModTags() {
    }

    public static boolean isWrench(ItemStack stack) {
        return stack.is(WRENCHES) || stack.is(WRENCHES_ALT);
    }
}
