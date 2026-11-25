package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.item.EnergyPipeItem;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import javax.annotation.Nullable;

/**
 * Colors pipes by tint index. The ring behind each pull cap uses tint index = that side's
 * Direction.get3DDataValue(), the insert ring 6 + side; step 155: both in the pipe's line colour.
 * Colors are worked out when the chunk is rebuilt, never per frame, so this costs nothing while you look around.
 * Client only (registered only on the client).
 */
// Registration events are mod-bus events; FML 1.21.1 sends them to the mod bus automatically.
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class PipeColors {
    /** Upgrade colours (none, iron, gold, diamond, netherite, nether star), RGB: the Pipe sides screen's marks. */
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
    /** Tint index of the energy line (0-5 are pull rings, 6-11 insert rings). */
    public static final int BAND_TINT = 12;
    /** Tint index of the energy pipe tube. */
    public static final int BODY_TINT = 13;
    /** Tint index of the energy pipe pull cap. Step 149: no model uses it any more (caps take the body tint). */
    public static final int CAP_TINT = 14;
    /**
     * Step 146: tint index of the middle line of item / fluid / universal pipes (their body uses BODY_TINT).
     * Not BAND_TINT: PipeBakedModel draws every BAND_TINT quad full-bright (the energy glow).
     */
    public static final int LINE_TINT = 15;

    private PipeColors() {
    }

    /** The colour of an upgrade level (0 = none), for the Pipe sides screen's upgrade marks. */
    public static int tierColor(int tier) {
        return tier >= 0 && tier < TIER_COLORS.length ? TIER_COLORS[tier] : TIER_COLORS[0];
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

    /** The color of a pipe's tint index, as the world draws it (level / pos may be null: no cover colours then). */
    public static int color(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        return ringColor(state, level, pos, tintIndex);
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        // Step 146: the item / fluid / universal pipe items in their pipe colours.
        event.register((stack, tintIndex) -> {
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem item)) {
                return -1;
            }
            int slot = PipeTints.slot(BasePipeBlock.kindOf(item.getBlock().defaultBlockState()));
            if (slot < 0) {
                return -1;
            }
            return tintIndex == BODY_TINT ? 0xFF000000 | PipeTints.body(slot)
                    : tintIndex == LINE_TINT ? 0xFF000000 | PipeTints.line(slot) : -1;
        }, ModItems.ITEM_PIPE.get(), ModItems.FLUID_PIPE.get(), ModItems.UNIVERSAL_PIPE.get());
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
        if (tintIndex >= PipeBakedModel.COVER_TINT_OFFSET) {
            // Step 126: a covered pipe draws its cover's quads, so their tints are the cover's (grass, leaves, water...).
            // Step 129: those quads carry COVER_TINT_OFFSET (PipeBakedModel), so with a wrench in hand, when the pipe
            // is drawn too, lower tint indexes are the pipe's own and get the pipe colors below.
            PipeDataBlockEntity covered = level == null || pos == null ? null : BasePipeBlock.getData(level, pos, state);
            BlockState cover = covered == null ? null : covered.cover();
            return cover == null ? -1 : Minecraft.getInstance().getBlockColors()
                    .getColor(cover, level, pos, tintIndex - PipeBakedModel.COVER_TINT_OFFSET);
        }
        if (tintIndex == BODY_TINT || tintIndex == LINE_TINT) {
            int slot = PipeTints.slot(BasePipeBlock.kindOf(state));
            if (slot >= 0) {
                return tintIndex == BODY_TINT ? PipeTints.body(slot) : PipeTints.line(slot);
            }
        }
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
        // Step 155: every pull and insert ring (0-5 pull, 6-11 insert) is the pipe's line colour, as bright as the line:
        // energy pipes their tier's line colour, item / fluid / universal pipes theirs (the player's own colour too).
        // Rings no longer show upgrades (Jade and the side's screen do). Step 156: their textures are grained around
        // the line texture's brightness, so the line tint draws them as bright as the line.
        if (energyTier >= 0) {
            return bandColor(energyTier);
        }
        int slot = PipeTints.slot(BasePipeBlock.kindOf(state));
        return slot >= 0 ? PipeTints.ring(slot) : TIER_COLORS[0];
    }
}
