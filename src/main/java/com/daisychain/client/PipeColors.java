package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.item.EnergyPipeItem;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;

import javax.annotation.Nullable;

/**
 * Colors the ring behind each pull cap by that side's upgrade tier.
 * The ring model on each side uses tint index = that side's Direction.get3DDataValue().
 * Step 67: the insert groove uses tint index 6 + side. Its texture is white: with no upgrade it gets the pipe type's
 * groove color, with a parked upgrade (kept from when the side pulled) a dimmed tier color.
 * Colors are worked out when the chunk is rebuilt, never per frame, so this costs nothing while you look around.
 * Client only (registered only on the client).
 */
// Registration events are mod-bus events; FML 1.21.1 sends them to the mod bus automatically.
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PipeColors {
    /** Ring color by upgrade level: none, iron, gold, diamond, netherite, nether star. RGB. */
    private static final int[] TIER_COLORS = {
            0x4A4652, // none
            0xA3A6AB, // iron
            0xC29A45, // gold
            0x5BAAA3, // diamond
            0x4E4249, // netherite
            0xDCD5E6, // nether star
    }; // step 42: design spec v10 tints

    /**
     * Step 88 (zerobit's look "D"): energy pipe colors by tier (basic, iron, gold, diamond, netherite, nether star).
     * LINE: the glowing one-pixel line down the middle (tint index 12). BODY: the tube, a dark tint of the tier color with
     * the same smooth shading as the other pipes (tint index 13; its texture is greyscale, lightest = this color). CAP:
     * the pull cap (tint index 14). Nether star's colors come from the vanilla nether star's own palette (pale cyan-grey
     * with a pale yellow centre).
     */
    private static final int[] LINE_COLORS = {0x98BEB2, 0xD6DCE4, 0xF4CA6D, 0x7BE2DB, 0xBEB7BA, 0xFDFFA8};
    private static final int[] BODY_COLORS = {0x5C736F, 0x81858D, 0x927946, 0x4A8888, 0x585159, 0x8B9598};
    private static final int[] CAP_COLORS = {0xA6D0C8, 0xE9F0FF, 0xFFDB7E, 0x86F6F6, 0xD1C9CC, 0xFBFFFF};
    /** Tint index of the energy line (0-5 are pull rings, 6-11 insert grooves). */
    public static final int BAND_TINT = 12;
    /** Tint index of the energy pipe tube. */
    public static final int BODY_TINT = 13;
    /** Tint index of the energy pipe pull cap. */
    public static final int CAP_TINT = 14;
    /** Insert groove of a tiered energy pipe (the basic one keeps the energy groove color). */
    private static final int TIER_GROOVE = 0x1E1E22;

    /** Groove color without an upgrade, per pipe type (PipeKind order: item, fluid, energy, universal). */
    private static final int[] GROOVE_COLORS = {0x2E2E2C, 0x1D2830, 0x1F2C28, 0x1E1E21};

    private PipeColors() {
    }

    /** The ring color of an upgrade level (0 = none). Also used by the Pipe sides screen's upgrade marks. */
    public static int tierColor(int tier) {
        return tier >= 0 && tier < TIER_COLORS.length ? TIER_COLORS[tier] : TIER_COLORS[0];
    }

    private static int grooveColor(BlockState state, int tier) {
        if (tier > 0) {
            // Step 68: parked, so faded: half way to grey, and a little darker than the ring.
            int c = tierColor(tier);
            int r = c >> 16 & 0xFF;
            int g = c >> 8 & 0xFF;
            int b = c & 0xFF;
            int grey = (r * 299 + g * 587 + b * 114) / 1000;
            r = (grey + r) / 2 * 17 / 20;
            g = (grey + g) / 2 * 17 / 20;
            b = (grey + b) / 2 * 17 / 20;
            return r << 16 | g << 8 | b;
        }
        if (EnergyPipeBlock.tierOf(state.getBlock()) > 0) {
            return TIER_GROOVE; // step 86: tiered energy pipes are grey
        }
        int kind = FlowPipeBlock.kindOf(state).ordinal();
        return kind < GROOVE_COLORS.length ? GROOVE_COLORS[kind] : GROOVE_COLORS[0];
    }

    private static int byTier(int[] colors, int tier) {
        return tier >= 0 && tier < colors.length ? colors[tier] : colors[0];
    }

    /** Step 84/88: the glowing line color of an energy pipe tier. */
    public static int bandColor(int tier) {
        return byTier(LINE_COLORS, tier);
    }

    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register(PipeColors::ringColor, ModBlocks.allPipes());
    }

    /** Step 88: the tube color of an energy pipe tier. */
    public static int bodyColor(int tier) {
        return byTier(BODY_COLORS, tier);
    }

    /** Step 88: the pull cap color of an energy pipe tier. */
    public static int capColor(int tier) {
        return byTier(CAP_COLORS, tier);
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            if (!(stack.getItem() instanceof EnergyPipeItem pipe)) {
                return -1;
            }
            return tintIndex == BAND_TINT ? 0xFF000000 | bandColor(pipe.tier())
                    : tintIndex == BODY_TINT ? 0xFF000000 | bodyColor(pipe.tier())
                    : tintIndex == CAP_TINT ? 0xFF000000 | capColor(pipe.tier()) : -1;
        }, ModItems.energyPipes());
    }

    private static int ringColor(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        int energyTier = EnergyPipeBlock.tierOf(state.getBlock());
        if (tintIndex == BAND_TINT) {
            return bandColor(Math.max(0, energyTier));
        }
        if (tintIndex == BODY_TINT) {
            return bodyColor(Math.max(0, energyTier));
        }
        if (tintIndex == CAP_TINT) {
            return capColor(Math.max(0, energyTier));
        }
        if (tintIndex < 0 || tintIndex > 11) {
            return TIER_COLORS[0];
        }
        boolean groove = tintIndex >= 6;
        if (!groove && energyTier >= 0) {
            // Step 84/90: an energy pull ring shows the pipe's own tier, in its line color (the basic pipe too).
            return bandColor(energyTier);
        }
        int tier = 0;
        if (level != null && pos != null) {
            PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, state);
            tier = data == null ? 0 : data.upgradeLevel(Direction.from3DDataValue(tintIndex % 6));
        }
        return groove ? grooveColor(state, tier) : tierColor(tier);
    }
}
