package com.daisychain.block;

import com.daisychain.config.DaisychainConfig;
import com.daisychain.item.FacadeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step 130 (facades): sneak + right-click a pipe with facades covers many pipes at once.
 * <ul>
 *   <li>The walk starts at the clicked pipe and follows linked arms, nearest first, through pipes of the <b>same block
 *   only</b> (Brandon: same type; each energy tier counts as its own type). It never enters unloaded chunks.</li>
 *   <li>Which pipes change follows the clicked pipe: clicked pipe uncovered → the uncovered ones; clicked pipe covered
 *   with block X → the ones covered with X (their old facades go back to the player). Other pipes are walked through
 *   but left alone. Holding a facade of X itself does nothing.</li>
 *   <li>At most {@code facades.max_mass_apply} pipes per click (server config); one facade per pipe, stopping when
 *   the stack runs out (creative uses none). Build rights and claims are checked for every pipe (step 132: claim
 *   mods through the place event, see ClaimCheck).</li>
 * </ul>
 * {@link #selection} is plain reading, so the client can use it later for the highlight (step 131).
 */
public final class FacadeMassApply {
    /** Most pipes one walk looks at (matching or not), so a click on a huge network stays cheap. */
    static final int MAX_VISITED = 4096;
    private static final Direction[] DIRECTIONS = Direction.values();

    private FacadeMassApply() {
    }

    /**
     * The pipes a click with this facade would change, nearest first (the clicked pipe first when it matches), at most
     * max. Empty if the pipe is already covered with the facade's block or the facade is invalid.
     */
    public static List<BlockPos> selection(Level level, BlockPos clicked, @Nullable BlockState facadeCover, int max) {
        List<BlockPos> result = new ArrayList<>();
        BlockState start = level.getBlockState(clicked);
        if (!(start.getBlock() instanceof FlowPipeBlock) || !FacadeItem.canCover(facadeCover) || max <= 0) {
            return result;
        }
        BlockState match = coverAt(level, clicked, start); // null = uncovered pipes
        if (match == facadeCover) {
            return result;
        }
        Block type = start.getBlock();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(clicked);
        queue.add(clicked);
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && result.size() < max) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            if (coverAt(level, pos, state) == match) {
                result.add(pos);
            }
            for (Direction direction : DIRECTIONS) {
                if (state.getValue(FlowPipeBlock.propertyFor(direction)) == SideState.NONE) {
                    continue;
                }
                next.setWithOffset(pos, direction);
                if (visited.size() >= MAX_VISITED || visited.contains(next) || !level.isLoaded(next)) {
                    continue;
                }
                BlockState other = level.getBlockState(next);
                if (other.getBlock() == type
                        && other.getValue(FlowPipeBlock.propertyFor(direction.getOpposite())) != SideState.NONE) {
                    BlockPos found = next.immutable();
                    visited.add(found);
                    queue.add(found);
                }
            }
        }
        return result;
    }

    /** Server: does the click. The stack is the player's facades (used up here). */
    public static void apply(ServerLevel level, BlockPos clicked, Player player, ItemStack stack) {
        apply(level, clicked, player, stack, DaisychainConfig.MAX_FACADE_MASS_APPLY.get());
    }

    /** As above with the limit given (game tests). */
    public static void apply(ServerLevel level, BlockPos clicked, Player player, ItemStack stack, int max) {
        BlockState cover = FacadeItem.coverOf(stack);
        if (!FacadeItem.canCover(cover)) {
            player.displayClientMessage(FacadeItem.refusedMessage(cover), true);
            return;
        }
        BlockState clickedState = level.getBlockState(clicked);
        BlockState match = coverAt(level, clicked, clickedState);
        if (match == cover) {
            player.displayClientMessage(Component.translatable("message.daisychain.facade_same"), true);
            return;
        }
        boolean creative = player.getAbilities().instabuild;
        int changed = 0;
        for (BlockPos pos : selection(level, clicked, cover, max)) {
            if (!creative && stack.isEmpty()) {
                break;
            }
            if (!ClaimCheck.mayChange(level, pos, player)) {
                continue; // claimed or protected: skip this pipe, keep going
            }
            FlowPipeBlock.ensureData(level, pos);
            PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, level.getBlockState(pos));
            if (data == null || data.cover() != match) {
                continue; // no settings storage, or changed since the walk: nothing used
            }
            data.setCover(cover);
            if (!creative) {
                stack.shrink(1);
            }
            changed++;
        }
        if (match != null && changed > 0) {
            // The replaced covers, all of block X, back to the player in full stacks.
            int left = changed;
            while (left > 0) {
                ItemStack back = FacadeItem.of(match, 1);
                int count = Math.min(left, back.getMaxStackSize());
                back.setCount(count);
                FlowPipeBlock.giveOrDrop(player, back);
                left -= count;
            }
        }
        if (changed > 0) {
            SoundType sound = cover.getSoundType(level, clicked, player);
            level.playSound(null, clicked, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                    sound.getPitch() * 0.8F);
        }
        player.displayClientMessage(changed == 0
                ? Component.translatable("message.daisychain.facade_mass_none")
                : Component.translatable(match == null ? "message.daisychain.facade_mass_covered"
                        : "message.daisychain.facade_mass_replaced", changed), true);
    }

    @Nullable
    private static BlockState coverAt(Level level, BlockPos pos, BlockState state) {
        PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, state);
        return data == null ? null : data.cover();
    }
}
