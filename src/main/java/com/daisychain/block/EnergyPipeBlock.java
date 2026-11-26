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
public final class EnergyPipeBlock extends BasePipeBlock {
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
        newState = newState.setValue(HAS_DATA, state.getValue(HAS_DATA)).setValue(COVER, state.getValue(COVER));
        PipeDataBlockEntity oldData = getData(level, pos, state);
        CompoundTag saved = oldData != null ? oldData.saveWithoutMetadata(level.registryAccess()) : null;
        boolean placed;
        swappingTier = true;
        try {
            placed = level.setBlock(pos, newState, Block.UPDATE_ALL);
        } finally {
            swappingTier = false;
        }
        if (!placed) {
            return false; // review: the world refused the change, so the old pipe stands and the player keeps the new one
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

    @Override
    protected boolean checksNewSides() {
        return true;
    }

    /**
     * Step 169: a side that just connected to a block that gives energy but won't take any (a generator, a solar panel)
     * starts as a pull side. Blocks that take energy (machines, batteries, energy cubes, other mods' cables) stay insert
     * sides, so the pipe never drains a battery on its own. Some generators say they can take energy too (Mekanism's, on
     * their output face): such a block counts as a generator only if it has room and still refuses 1,000 FE (asked as a
     * simulation, nothing moves). A full battery can't be told apart that way, so it stays an insert side. Step 181:
     * blocks in the tag daisychain:energy_auto_pull (flux points, which push their power out) start as pull sides too. Asked once,
     * one tick after the side connected; a handler that throws counts as "no".
     */
    @Override
    protected boolean startsAsPull(ServerLevel level, BlockPos pos, Direction side, BlockState neighborState) {
        if (neighborState.is(com.daisychain.registry.ModTags.ENERGY_NEVER_AUTO_PULL)) {
            return false; // another mod's cable or pipe: which way it should run is the player's call
        }
        if (neighborState.is(com.daisychain.registry.ModTags.ENERGY_AUTO_PULL)) {
            // Step 181: a block that pushes its power out (a flux point), which only a pull side's energy input accepts.
            return true;
        }
        try {
            net.neoforged.neoforge.energy.IEnergyStorage storage =
                    level.getCapability(Capabilities.EnergyStorage.BLOCK, pos.relative(side), side.getOpposite());
            if (storage == null || !storage.canExtract()) {
                return false;
            }
            if (!storage.canReceive()) {
                return true;
            }
            int max = storage.getMaxEnergyStored();
            return max > 0 && storage.getEnergyStored() < max && storage.receiveEnergy(1_000, true) == 0;
        } catch (RuntimeException e) {
            return false; // another mod's handler misbehaved: leave the side as it is
        }
    }
}
