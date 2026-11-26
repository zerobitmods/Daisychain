package com.daisychain.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

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

    /** Step 125: blocks that can't be facades even though they are full cubes (empty by default; for pack makers). */
    public static final TagKey<Block> NOT_FACADES =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("daisychain", "not_facades"));

    /**
     * Step 161: blocks a redstone pipe connects to (as an output) although they give no signal themselves: lamps,
     * pistons, doors... Anything that gives a signal (levers, dust, repeaters) connects without being listed.
     */
    public static final TagKey<Block> REDSTONE_PIPE_CONNECTS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("daisychain", "redstone_pipe_connects"));

    /**
     * Step 163: blocks that only give a signal (levers, buttons, redstone blocks...). A redstone pipe side that newly
     * connects to one starts as an input instead of an output.
     */
    public static final TagKey<Block> REDSTONE_PIPE_INPUTS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("daisychain", "redstone_pipe_inputs"));

    /**
     * Step 169: energy blocks a new energy pipe side never starts pulling from, though they answer like a generator:
     * other mods' cables and pipes (they say they take energy, then refuse it), so the pipe can't tell which way they
     * should run. Optional entries; pack makers can add more.
     */
    public static final TagKey<Block> ENERGY_NEVER_AUTO_PULL =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("daisychain", "energy_never_auto_pull"));

    /**
     * Step 181: energy blocks a new energy pipe side always starts pulling from. For blocks that push their power out
     * and answer "neither gives nor takes" when asked (a Flux Networks flux point). Not a general rule: a Flux Plug that
     * hasn't joined its network yet gives the same answers but wants power. Optional entries; pack makers can add more.
     */
    public static final TagKey<Block> ENERGY_AUTO_PULL =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("daisychain", "energy_auto_pull"));

    private ModTags() {
    }
}
