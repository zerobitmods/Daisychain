package com.daisychain.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.daisychain.item.EnergyPipeItem;
import com.daisychain.network.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Energy pipe: connects to any block that exposes an energy storage (FE).
 * Step 84 (zerobit): energy pipes come in tiers (0 = basic, then iron, gold, diamond, netherite, nether star; the same
 * levels and speeds as the upgrades). Pipes of every tier connect to each other and form one network; energy going to a
 * machine moves at the speed of the slowest pipe on the best route to it (network/PipeNetwork.routeTiers). Energy pull
 * sides take no upgrade.
 */
public final class EnergyPipeBlock extends FlowPipeBlock {
    public static final int TIERS = 6;

    public static final MapCodec<EnergyPipeBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(0, TIERS - 1).fieldOf("tier").forGetter(EnergyPipeBlock::tier),
            propertiesCodec()).apply(instance, EnergyPipeBlock::new));

    private final int tier;

    public EnergyPipeBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    @Override
    public MapCodec<EnergyPipeBlock> codec() {
        return CODEC;
    }

    /** 0 = basic, 1 iron, 2 gold, 3 diamond, 4 netherite, 5 nether star (UpgradeTier levels). */
    public int tier() {
        return tier;
    }

    /** The tier of an energy pipe block, or -1 for anything else. */
    public static int tierOf(Block block) {
        return block instanceof EnergyPipeBlock energy ? energy.tier : -1;
    }

    /**
     * Step 93 (zerobit): true if sneak + right-clicking this block with this stack swaps the pipe: an energy pipe, and an
     * energy pipe item of a higher tier. Same answer on client and server (both decide whether the click reaches the pipe).
     */
    public static boolean canSwap(BlockState target, ItemStack stack) {
        return target.getBlock() instanceof EnergyPipeBlock pipe && stack.getItem() instanceof EnergyPipeItem item
                && item.tier() > pipe.tier();
    }

    /**
     * Step 93: replaces the energy pipe at pos with the held higher tier. Keeps its arms (same block state values) and
     * its settings (the block entity's data is copied over; upgrades are not dropped and re-added, they just move). Takes
     * one pipe from the stack and gives the old pipe back (creative: neither). Server only. Returns true if it swapped.
     */
    public static boolean swapInPlace(ServerLevel level, BlockPos pos, BlockState state, Player player, ItemStack stack) {
        if (!level.mayInteract(player, pos) || !player.mayBuild()
                || !(stack.getItem() instanceof EnergyPipeItem item) || !canSwap(state, stack)) {
            return false;
        }
        Block newBlock = item.getBlock();
        BlockState newState = newBlock.defaultBlockState();
        for (Direction direction : Direction.values()) {
            newState = newState.setValue(propertyFor(direction), state.getValue(propertyFor(direction)));
        }
        newState = newState.setValue(HAS_DATA, state.getValue(HAS_DATA));
        PipeDataBlockEntity oldData = getData(level, pos, state);
        CompoundTag saved = oldData != null ? oldData.saveWithoutMetadata(level.registryAccess()) : null;
        swappingTier = true;
        try {
            level.setBlock(pos, newState, Block.UPDATE_ALL);
        } finally {
            swappingTier = false;
        }
        if (saved != null && level.getBlockEntity(pos) instanceof PipeDataBlockEntity newData) {
            newData.takeOver(saved, level.registryAccess());
            level.sendBlockUpdated(pos, newState, newState, Block.UPDATE_CLIENTS);
            NetworkManager.get(level).onPipeChanged(pos); // the pull sides' settings are back: rebuild once more
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            ItemStack old = new ItemStack(state.getBlock());
            if (!player.getInventory().add(old)) {
                player.drop(old, false);
            }
        }
        SoundType sound = newState.getSoundType(level, pos, player);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * 0.8F);
        return true;
    }

    /**
     * Step 98 (FTB Ultimine): swaps every loaded energy pipe in positions that the stack can swap, one pipe each, and
     * stops when the stack runs out (survival). Skips anything else (other blocks, equal or higher tiers). Returns how
     * many were swapped. Server only.
     */
    public static int swapMany(ServerLevel level, Player player, ItemStack stack, Iterable<BlockPos> positions) {
        int swapped = 0;
        for (BlockPos pos : positions) {
            if (stack.isEmpty()) {
                break;
            }
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (swapInPlace(level, pos, level.getBlockState(pos), player, stack)) {
                swapped++;
            }
        }
        return swapped;
    }

    @Override
    public PipeKind kind() {
        return PipeKind.ENERGY;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, targetPos, targetSide) != null;
    }
}
