package com.daisychain.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Item tags Daisychain reads.
 *
 * WRENCHES is the shared "c:tools/wrench" tag that most tech mods put their wrenches in.
 * NeoForge only added a ready-made constant for it in later 21.1 builds, so we build the
 * same tag key ourselves. That keeps Daisychain working on older NeoForge 21.1 versions.
 */
public final class ModTags {
    public static final TagKey<Item> WRENCHES =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    private ModTags() {
    }
}
