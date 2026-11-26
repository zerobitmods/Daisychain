package com.daisychain.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

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

    /** Step F53: blocks that can't be facades even though they are full cubes (empty by default; for pack makers). */
    public static final TagKey<Block> NOT_FACADES =
            TagKey.create(Registries.BLOCK, new ResourceLocation("daisychain", "not_facades"));

    /**
     * Step F86 (port of 161): blocks a redstone pipe connects to (as an output) although they give no signal themselves:
     * lamps, pistons, doors... Anything that gives a signal (levers, dust, repeaters) connects without being listed.
     */
    public static final TagKey<Block> REDSTONE_PIPE_CONNECTS =
            TagKey.create(Registries.BLOCK, new ResourceLocation("daisychain", "redstone_pipe_connects"));

    /**
     * Step F88 (port of 163): blocks that only give a signal (levers, buttons, redstone blocks...). A redstone pipe side
     * that newly connects to one starts as an input instead of an output.
     */
    public static final TagKey<Block> REDSTONE_PIPE_INPUTS =
            TagKey.create(Registries.BLOCK, new ResourceLocation("daisychain", "redstone_pipe_inputs"));

    /**
     * Step F93 (port of 169): energy blocks a new energy pipe side never starts pulling from, though they answer like a
     * generator: other mods' cables and pipes, so the pipe can't tell which way they should run. Optional entries;
     * pack makers can add more.
     */
    public static final TagKey<Block> ENERGY_NEVER_AUTO_PULL =
            TagKey.create(Registries.BLOCK, new ResourceLocation("daisychain", "energy_never_auto_pull"));

    private ModTags() {
    }

    public static boolean isWrench(ItemStack stack) {
        return stack.is(WRENCHES) || stack.is(WRENCHES_ALT);
    }
}
