package com.daisychain.block;

import com.daisychain.filter.FluidGhost;
import com.daisychain.filter.ItemFilter;
import com.daisychain.filter.MatchMode;
import com.daisychain.item.UpgradeItem;
import com.daisychain.network.EnergyInput;
import com.daisychain.network.NetworkManager;
import com.daisychain.registry.ModItems;
import com.daisychain.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;

/**
 * Per-side settings for one pipe. Only exists on pipes where a player changed something
 * (blockstate has_data=true), so plain pipes stay as cheap as a normal block. Never ticks.
 */
public final class PipeDataBlockEntity extends BlockEntity {
    /** Setting ids used by the settings screen and its network packet. */
    public static final int SETTING_REDSTONE = 0;
    public static final int SETTING_DISTRIBUTION = 1;
    public static final int SETTING_SELF_FEED = 2;
    /** 0 = allow listed items only, 1 = deny listed items. */
    public static final int SETTING_FILTER_DENY = 3;
    /** Max items per transfer; 0 = "Max" (whatever the upgrade allows). Always capped by the upgrade. */
    public static final int SETTING_BATCH = 4;
    /** Leave at least this many of each item in the source; 0 = off. */
    public static final int SETTING_KEEP = 5;
    /** Stop sending an item to a destination once it holds this many of it; 0 = off. */
    public static final int SETTING_FILL = 6;
    /** Insert sides: destinations with a higher priority get items first. */
    public static final int SETTING_PRIORITY = 7;
    /** Insert sides: the insert filter denies its listed items instead of accepting only them. */
    public static final int SETTING_INSERT_DENY = 8;
    /** Insert sides: the destination stops accepting an item once it holds this many of it; 0 = off. */
    public static final int SETTING_MAX_STOCK = 9;
    /** Pull sides (step 33a): 1 = this resource is not pulled from this side (universal pipes). */
    public static final int SETTING_PULL_OFF = 10;
    /** Insert sides (step 33a): 1 = this destination accepts none of this resource (universal pipes). */
    public static final int SETTING_INSERT_OFF = 11;
    /**
     * Insert sides (step 40): when this destination accepts anything, by the redstone signal reaching the pipe.
     * RedstoneMode ALWAYS, ON_WITH_SIGNAL or OFF_WITH_SIGNAL (Pulse is a pull-side mode only).
     */
    public static final int SETTING_INSERT_REDSTONE = 12;
    /** Insert redstone modes: Always, On with signal, Off with signal. */
    public static final int INSERT_REDSTONE_MODES = 3;
    public static final int MIN_PRIORITY = -999;
    public static final int MAX_PRIORITY = 999;

    /** Item pipe limits; fluid and energy pipes allow more (PipeKind.maxBatch / maxStock, in mB or FE). */
    public static final int MAX_BATCH = 1_000_000;
    public static final int MAX_STOCK = 100_000;

    /** Per-resource arrays hold one value per side per resource: index = resource * 6 + side (step 31). */
    private static final int SLOTS = PipeKind.RESOURCE_COUNT * 6;
    /** Saved / synced names of the per-resource sub-tags in a Settings Copier (items, fluids, energy). */
    private static final String[] RESOURCE_KEYS = {"Items", "Fluids", "Energy"};

    private byte extractMask;
    private byte disconnectMask;
    private final byte[] redstone = new byte[6];
    /** Insert sides' redstone mode (step 40), separate from the pull sides' so switching a side's type changes nothing. */
    private final byte[] insertRedstone = new byte[6];
    private final byte[] distribution = new byte[6];
    private byte selfFeedMask;
    /** Installed upgrade level per side (0 = none). Kept even if the side stops pulling; dropped when the pipe breaks. */
    private final byte[] upgrades = new byte[6];
    /** Insert sides (normal sides facing an inventory): priority, shared by every resource of the side. */
    private final int[] priority = new int[6];
    /** One bit per side per resource (bit = resource * 6 + side): the filter denies its listed entries. Synced. */
    private int filterDenyBits;
    private final int[] batch = new int[SLOTS];
    private final int[] keep = new int[SLOTS];
    private final int[] fill = new int[SLOTS];
    private final int[] maxStock = new int[SLOTS];
    /** Like filterDenyBits, for insert filters. */
    private int insertDenyBits;
    /** One bit per side per resource (step 33a): this pull side doesn't pull that resource. Synced. */
    private int pullOffBits;
    /** One bit per side per resource (step 33a): this insert side accepts none of that resource. Synced. */
    private int insertOffBits;
    /** Insert filter per side per resource, created when first edited. Saved to disk, not synced (like pull filters). */
    private final ItemFilter[] insertFilters = new ItemFilter[SLOTS];
    /**
     * Pull filter per side per resource, created when first edited. Saved to disk but NOT sent in block updates
     * (rendering doesn't need them); an open Pull side screen gets them through its slots instead. Null = no filter.
     */
    private final ItemFilter[] filters = new ItemFilter[SLOTS];
    /**
     * One bit per side whose pull filter (any resource) has entries. Synced to clients (the filters themselves aren't),
     * so both sides agree on which pull sides stay drawn without an inventory.
     */
    private byte filterMask;

    public PipeDataBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PIPE_DATA.get(), pos, state);
    }

    /**
     * Step 109: energy inputs of this pipe's pull sides (see EnergyInput), made when another block first asks. Forge
     * 1.20.1 hands capabilities out as LazyOptionals; a side's is invalidated when it stops being a pull side
     * (refreshEnergyInputs) and all of them when the block entity goes away (invalidateCaps).
     */
    @SuppressWarnings("unchecked")
    private final LazyOptional<IEnergyStorage>[] energyInputs = new LazyOptional[6];

    /**
     * Step 109: the energy storage this pipe shows on one side: only pull sides of energy and universal pipes have one
     * (it takes pushed energy and passes it straight on). Null on every other side.
     */
    @Nullable
    public IEnergyStorage energyInput(@Nullable Direction side) {
        return energyInputOptional(side).resolve().orElse(null);
    }

    private LazyOptional<IEnergyStorage> energyInputOptional(@Nullable Direction side) {
        if (side == null || isRemoved() || !offersEnergyInput(getBlockState(), side)) {
            return LazyOptional.empty();
        }
        int i = side.get3DDataValue();
        LazyOptional<IEnergyStorage> input = energyInputs[i];
        if (input == null) {
            EnergyInput storage = new EnergyInput(this, side);
            input = LazyOptional.of(() -> storage);
            energyInputs[i] = input;
        }
        return input;
    }

    private static boolean offersEnergyInput(BlockState state, Direction side) {
        return state.getBlock() instanceof FlowPipeBlock pipe && pipe.kind().carries(PipeKind.ENERGY_RES)
                && state.getValue(FlowPipeBlock.propertyFor(side)) == SideState.EXTRACT;
    }

    /**
     * Step 109: the pipe's block state changed (FlowPipeBlock.onPlace, with the new state, which this block entity
     * doesn't hold yet): sides that no longer offer an energy input drop the one they handed out.
     */
    public void refreshEnergyInputs(BlockState newState) {
        for (Direction side : Direction.values()) {
            int i = side.get3DDataValue();
            if (energyInputs[i] != null && !offersEnergyInput(newState, side)) {
                energyInputs[i].invalidate();
                energyInputs[i] = null;
            }
        }
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY) {
            LazyOptional<IEnergyStorage> input = energyInputOptional(side);
            if (input.isPresent()) {
                return input.cast();
            }
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        for (int i = 0; i < energyInputs.length; i++) {
            if (energyInputs[i] != null) {
                energyInputs[i].invalidate();
                energyInputs[i] = null;
            }
        }
    }

    private static int slot(Direction side, int resource) {
        return resource * 6 + side.get3DDataValue();
    }

    /** True if this pipe's sides carry that resource (callers from packets use this to refuse anything else). */
    public boolean carries(int resource) {
        return kind().carries(resource);
    }

    // ---------------------------------------------------------------- masks

    public int extractMask() {
        return extractMask;
    }

    public int disconnectMask() {
        return disconnectMask;
    }

    public boolean isDisconnected(Direction side) {
        return (disconnectMask & (1 << side.get3DDataValue())) != 0;
    }

    /**
     * Step 41: one bit per side, set when the wrench turned an insert side into a pull side (cleared when it came from
     * off). The wrench's next click goes on to the other end: insert -> pull -> off, off -> pull -> insert. Server
     * only; saved, not synced.
     */
    private byte pullFromInsertMask;

    /** Step 44: which view the pipe's side screens open in (shared by all players). 0 = the viewer's own default. */
    public static final int VIEW_DEFAULT = 0;
    public static final int VIEW_SIMPLE = 1;
    public static final int VIEW_ADVANCED = 2;
    private byte viewMode;

    public int viewMode() {
        return viewMode;
    }

    /** Server: the Advanced key was used. Synced so the next screen (any player) opens the same way. */
    public void setViewMode(int mode) {
        if (mode < VIEW_DEFAULT || mode > VIEW_ADVANCED || mode == viewMode) {
            return;
        }
        viewMode = (byte) mode;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    public boolean pullCameFromInsert(Direction side) {
        return (pullFromInsertMask & (1 << side.get3DDataValue())) != 0;
    }

    public void setPullCameFromInsert(Direction side, boolean fromInsert) {
        int bit = 1 << side.get3DDataValue();
        pullFromInsertMask = (byte) (fromInsert ? pullFromInsertMask | bit : pullFromInsertMask & ~bit);
        setChanged();
    }

    public void setMasks(int extract, int disconnect) {
        extractMask = (byte) (extract & 0b111111);
        disconnectMask = (byte) (disconnect & 0b111111);
        setChanged();
        syncExtractor();
    }

    // ---------------------------------------------------------------- side settings

    public RedstoneMode redstoneMode(Direction side) {
        return RedstoneMode.byId(redstone[side.get3DDataValue()]);
    }

    public Distribution distribution(Direction side) {
        return Distribution.byId(distribution[side.get3DDataValue()]);
    }

    /** Max amount per transfer chosen for this side and resource; 0 = whatever the upgrade allows. */
    public int batch(Direction side, int resource) {
        return batch[slot(side, resource)];
    }

    /** Amount of each item / fluid (or energy) to leave in the source; 0 = off. */
    public int keepInSource(Direction side, int resource) {
        return keep[slot(side, resource)];
    }

    /** True for settings that belong to insert sides (the rest belong to pull sides). */
    public static boolean isInsertSetting(int setting) {
        return setting == SETTING_PRIORITY || setting == SETTING_INSERT_DENY || setting == SETTING_MAX_STOCK
                || setting == SETTING_INSERT_OFF || setting == SETTING_INSERT_REDSTONE;
    }

    /** Insert side redstone mode (step 40): Always (default), On with signal or Off with signal. */
    public RedstoneMode insertRedstoneMode(Direction side) {
        int value = insertRedstone[side.get3DDataValue()];
        return value < INSERT_REDSTONE_MODES ? RedstoneMode.byId(value) : RedstoneMode.ALWAYS;
    }

    /** False if this pull side is switched off for that resource. */
    public boolean pullsResource(Direction side, int resource) {
        return (pullOffBits & (1 << slot(side, resource))) == 0;
    }

    /** False if this insert side is switched off for that resource (the destination gets none of it). */
    public boolean acceptsResource(Direction side, int resource) {
        return (insertOffBits & (1 << slot(side, resource))) == 0;
    }

    /** True for settings with one value per resource (the rest are shared by the whole side). */
    public static boolean isResourceSetting(int setting) {
        return setting == SETTING_FILTER_DENY || setting == SETTING_BATCH || setting == SETTING_KEEP
                || setting == SETTING_FILL || setting == SETTING_INSERT_DENY || setting == SETTING_MAX_STOCK
                || setting == SETTING_PULL_OFF || setting == SETTING_INSERT_OFF;
    }

    /** Insert side Max stock for this resource (0 = off). */
    public int maxStock(Direction side, int resource) {
        return maxStock[slot(side, resource)];
    }

    /** Insert side priority (shared by every resource): higher gets things first; 0 by default. */
    public int priority(Direction side) {
        return priority[side.get3DDataValue()];
    }

    public boolean insertFilterDenies(Direction side, int resource) {
        return (insertDenyBits & (1 << slot(side, resource))) != 0;
    }

    /** The insert filter object (may be empty), or null if this side never had one. Read when a network is built. */
    @Nullable
    public ItemFilter insertFilter(Direction side, int resource) {
        return insertFilters[slot(side, resource)];
    }

    /** True if any side has an insert setting, so the pipe must keep its block entity. */
    public boolean hasInsertSettings() {
        if (insertDenyBits != 0 || insertOffBits != 0) {
            return true;
        }
        for (int i = 0; i < 6; i++) {
            if (priority[i] != 0 || insertRedstone[i] != 0) {
                return true;
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            if (maxStock[i] != 0 || (insertFilters[i] != null && insertFilters[i].isActive())) {
                return true;
            }
        }
        return false;
    }

    /** Destinations stop receiving an item / fluid (or energy) once they hold this much of it; 0 = off. */
    public int fillDestinationTo(Direction side, int resource) {
        return fill[slot(side, resource)];
    }

    public boolean allowsSelfFeed(Direction side) {
        return (selfFeedMask & (1 << side.get3DDataValue())) != 0;
    }

    /** A setting's value; per-resource settings read that resource's value, shared ones ignore it. */
    public int getSetting(Direction side, int setting, int resource) {
        return switch (setting) {
            case SETTING_REDSTONE -> redstoneMode(side).ordinal();
            case SETTING_DISTRIBUTION -> distribution(side).ordinal();
            case SETTING_SELF_FEED -> allowsSelfFeed(side) ? 1 : 0;
            case SETTING_FILTER_DENY -> filterDenies(side, resource) ? 1 : 0;
            case SETTING_BATCH -> batch(side, resource);
            case SETTING_KEEP -> keepInSource(side, resource);
            case SETTING_FILL -> fillDestinationTo(side, resource);
            case SETTING_PRIORITY -> priority(side);
            case SETTING_INSERT_DENY -> insertFilterDenies(side, resource) ? 1 : 0;
            case SETTING_MAX_STOCK -> maxStock(side, resource);
            case SETTING_PULL_OFF -> pullsResource(side, resource) ? 0 : 1;
            case SETTING_INSERT_OFF -> acceptsResource(side, resource) ? 0 : 1;
            case SETTING_INSERT_REDSTONE -> insertRedstoneMode(side).ordinal();
            default -> 0;
        };
    }

    /**
     * Applies one setting. Out-of-range values and resources this pipe doesn't carry are ignored. Server only; syncs to
     * clients. Number limits follow the resource (items, mB or FE).
     */
    public void applySetting(Direction side, int setting, int value, int resource) {
        if (!carries(resource)) {
            return;
        }
        int i = side.get3DDataValue();
        int r = slot(side, resource);
        int keptBefore = keptPullMask();
        PipeKind units = PipeKind.ofResource(resource);
        switch (setting) {
            case SETTING_REDSTONE -> {
                if (value < 0 || value >= RedstoneMode.values().length) return;
                redstone[i] = (byte) value;
            }
            case SETTING_DISTRIBUTION -> {
                if (value < 0 || value >= Distribution.values().length) return;
                distribution[i] = (byte) value;
            }
            case SETTING_SELF_FEED -> {
                if (value != 0 && value != 1) return;
                selfFeedMask = (byte) (value == 1 ? selfFeedMask | (1 << i) : selfFeedMask & ~(1 << i));
            }
            case SETTING_FILTER_DENY -> {
                if (value != 0 && value != 1) return;
                filterDenyBits = value == 1 ? filterDenyBits | (1 << r) : filterDenyBits & ~(1 << r);
            }
            case SETTING_BATCH -> {
                if (value < 0 || value > units.maxBatch()) return;
                batch[r] = value;
            }
            case SETTING_KEEP -> {
                if (value < 0 || value > units.maxStock()) return;
                keep[r] = value;
            }
            case SETTING_FILL -> {
                if (value < 0 || value > units.maxStock()) return;
                fill[r] = value;
            }
            case SETTING_PRIORITY -> {
                if (value < MIN_PRIORITY || value > MAX_PRIORITY) return;
                priority[i] = value;
            }
            case SETTING_INSERT_DENY -> {
                if (value != 0 && value != 1) return;
                insertDenyBits = value == 1 ? insertDenyBits | (1 << r) : insertDenyBits & ~(1 << r);
            }
            case SETTING_MAX_STOCK -> {
                if (value < 0 || value > units.maxStock()) return;
                maxStock[r] = value;
            }
            case SETTING_PULL_OFF -> {
                if (value != 0 && value != 1) return;
                pullOffBits = value == 1 ? pullOffBits | (1 << r) : pullOffBits & ~(1 << r);
            }
            case SETTING_INSERT_OFF -> {
                if (value != 0 && value != 1) return;
                insertOffBits = value == 1 ? insertOffBits | (1 << r) : insertOffBits & ~(1 << r);
            }
            case SETTING_INSERT_REDSTONE -> {
                if (value < 0 || value >= INSERT_REDSTONE_MODES) return;
                insertRedstone[i] = (byte) value;
            }
            default -> {
                return;
            }
        }
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
            NetworkManager.get(serverLevel).wakeExtractor(worldPosition);
            if (keptPullMask() != keptBefore) {
                FlowPipeBlock.refreshArms(serverLevel, worldPosition); // a pull side with no inventory may appear/disappear
            }
            if (isInsertSetting(setting)) {
                // Destinations read these when the network is built: rebuild it.
                NetworkManager.get(serverLevel).onPipeChanged(worldPosition);
            }
        }
    }

    /** What this pipe carries (decides which resources its sides have). */
    public PipeKind kind() {
        return FlowPipeBlock.kindOf(getBlockState());
    }

    // ---------------------------------------------------------------- filters

    public boolean filterDenies(Direction side, int resource) {
        return (filterDenyBits & (1 << slot(side, resource))) != 0;
    }

    /** The side's filter if it has at least one entry, else null (everything passes). Server: used every transfer. */
    @Nullable
    public ItemFilter activeFilter(Direction side, int resource) {
        ItemFilter filter = filters[slot(side, resource)];
        return filter != null && filter.isActive() ? filter : null;
    }

    /** Pull filters or insert filters. Every filter-editing method below takes which one. */
    private ItemFilter[] filterArray(boolean insert) {
        return insert ? insertFilters : filters;
    }

    /** What the side's filter slot shows (a copy that also says how it matches), or empty. */
    public ItemStack filterEntry(Direction side, int slot, boolean insert, int resource) {
        ItemFilter filter = filterArray(insert)[slot(side, resource)];
        return filter == null ? ItemStack.EMPTY : filter.display(slot);
    }

    /** True if the side's pull filter (or insert filter) for this resource has at least one entry. */
    public boolean hasFilterEntries(Direction side, boolean insert, int resource) {
        ItemFilter filter = filterArray(insert)[slot(side, resource)];
        return filter != null && filter.isActive();
    }

    /**
     * Server only. Lists a ghost copy in plain item mode (never takes the real item). Empty clears the entry.
     * Ignored if the item is already listed in plain item mode in another slot. Items go only in item filters, fluid
     * ghosts only in fluid filters; energy has no filter.
     */
    public void setFilterEntry(Direction side, int slot, ItemStack stack, boolean insert, int resource) {
        if (slot < 0 || slot >= ItemFilter.SIZE || level == null || !carries(resource)) {
            return;
        }
        PipeKind units = PipeKind.ofResource(resource);
        if (!stack.isEmpty() && (!units.hasFilter() || FluidGhost.isGhost(stack) != (units == PipeKind.FLUID))) {
            return;
        }
        ItemFilter[] array = filterArray(insert);
        int i = slot(side, resource);
        if (array[i] == null) {
            if (stack.isEmpty()) {
                return;
            }
            array[i] = new ItemFilter();
        }
        if (array[i].set(slot, stack, level.registryAccess())) {
            onFilterChanged(insert);
        }
    }

    /**
     * Server only. Changes how one entry matches (already validated by the caller: mode, tag exists, menu open).
     * The filter itself refuses impossible or duplicate entries.
     */
    public void setFilterMatch(Direction side, int slot, MatchMode mode, @Nullable ResourceLocation tag, boolean insert,
                               int resource) {
        if (!carries(resource)) {
            return;
        }
        ItemFilter filter = filterArray(insert)[slot(side, resource)];
        if (filter != null && filter.setMatch(slot, mode, tag)) {
            onFilterChanged(insert);
        }
    }

    /**
     * Server only. Sets one filter entry's own Batch / Keep / Fill (ItemFilter.UNSET = use the side's value).
     * The filter refuses empty entries and out-of-range values.
     */
    public void setFilterNumber(Direction side, int slot, int which, int value, boolean insert, int resource) {
        if (!carries(resource)) {
            return;
        }
        ItemFilter filter = filterArray(insert)[slot(side, resource)];
        if (filter != null && filter.setNumber(slot, which, value)) {
            onFilterChanged(insert);
        }
    }

    /** Server only. Empties the side's filter for this resource (so everything passes again). */
    public void clearFilter(Direction side, boolean insert, int resource) {
        if (!carries(resource)) {
            return;
        }
        ItemFilter filter = filterArray(insert)[slot(side, resource)];
        if (filter != null && filter.isActive()) {
            filter.clear();
            onFilterChanged(insert);
        }
    }

    private void onFilterChanged(boolean insert) {
        if (insert) {
            setChanged();
            if (level instanceof ServerLevel serverLevel) {
                // A destination's insert filter is read when the network is built: rebuild it.
                NetworkManager.get(serverLevel).onPipeChanged(worldPosition);
            }
            return;
        }
        byte before = filterMask;
        filterMask = computeFilterMask();
        setChanged();
        if (before != filterMask && level instanceof ServerLevel serverLevel) {
            FlowPipeBlock.refreshArms(serverLevel, worldPosition); // a pull side with no inventory may appear/disappear
        }
        if (level instanceof ServerLevel serverLevel) {
            // Items that were blocked may pass now (or the other way round): run the side again soon.
            NetworkManager.get(serverLevel).wakeExtractor(worldPosition);
        }
    }

    /** Sides whose pull filter has entries for any resource. */
    private byte computeFilterMask() {
        int mask = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (filters[i] != null && filters[i].isActive()) {
                mask |= 1 << (i % 6);
            }
        }
        return (byte) mask;
    }

    /** Folds 18 per-resource bits (resource * 6 + side) into 6 side bits. */
    private static int sidesOf(int bits) {
        return (bits | (bits >>> 6) | (bits >>> 12)) & 0b111111;
    }

    /**
     * Pull sides with anything set: an upgrade, a filter, or any setting changed from its default (redstone,
     * distribution, send back, filter mode, batch, keep, fill, for any resource). These keep their arm and cap even
     * with no inventory next to them (zerobit's choice, step 21f). All of these are synced, so clients compute the same
     * mask.
     */
    public int keptPullMask() {
        return pullSettingSides() & extractMask;
    }

    /**
     * Step 67 (zerobit): true if any side stores pull settings or an upgrade, pulling or not. Such a pipe keeps its
     * block entity, so a side switched away from Pull (by accident or not) gets everything back when it pulls again.
     */
    public boolean hasPullSettings() {
        return pullSettingSides() != 0;
    }

    /** Sides with any pull setting or upgrade stored (see keptPullMask), whatever their mode now. */
    private int pullSettingSides() {
        int mask = filterMask | selfFeedMask | sidesOf(filterDenyBits) | sidesOf(pullOffBits);
        for (int i = 0; i < 6; i++) {
            if (upgrades[i] != 0 || redstone[i] != 0 || distribution[i] != 0) {
                mask |= 1 << i;
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            if (batch[i] != 0 || keep[i] != 0 || fill[i] != 0) {
                mask |= 1 << (i % 6);
            }
        }
        return mask;
    }

    // ---------------------------------------------------------------- Settings Copier (step 24)

    /**
     * One side's pull settings (insert = false) or insert settings, for the Settings Copier. Shared settings are at the
     * top; each resource this pipe carries gets its own sub-tag (Items / Fluids / Energy) with its filter mode, amounts
     * and filter. Pull settings include the upgrade level, so pasting can install the same tier from the player's
     * inventory. Missing keys mean "default".
     */
    public CompoundTag copySide(Direction side, boolean insert, HolderLookup.Provider registries) {
        int i = side.get3DDataValue();
        CompoundTag tag = new CompoundTag();
        if (insert) {
            tag.putInt("Priority", priority[i]);
            tag.putByte("Redstone", insertRedstone[i]);
        } else {
            tag.putByte("Redstone", redstone[i]);
            tag.putByte("Distribution", distribution[i]);
            tag.putBoolean("SendBack", allowsSelfFeed(side));
            tag.putByte("Upgrade", upgrades[i]);
        }
        PipeKind kind = kind();
        for (int resource = 0; resource < PipeKind.RESOURCE_COUNT; resource++) {
            if (!kind.carries(resource)) {
                continue;
            }
            int r = slot(side, resource);
            CompoundTag part = new CompoundTag();
            ItemFilter filter;
            part.putBoolean("Off", insert ? !acceptsResource(side, resource) : !pullsResource(side, resource));
            if (insert) {
                part.putBoolean("Deny", insertFilterDenies(side, resource));
                part.putInt("MaxStock", maxStock[r]);
                filter = insertFilters[r];
            } else {
                part.putBoolean("Deny", filterDenies(side, resource));
                part.putInt("Batch", batch[r]);
                part.putInt("Keep", keep[r]);
                part.putInt("Fill", fill[r]);
                filter = filters[r];
            }
            if (filter != null && filter.isActive()) {
                part.put("Filter", filter.save(registries));
            }
            tag.put(RESOURCE_KEYS[resource], part);
        }
        return tag;
    }

    /**
     * The copied values for one resource: its sub-tag, or (copies made before step 31 held one resource at the top) the
     * top-level tag for the pipe's own resource.
     */
    public static CompoundTag copiedResource(CompoundTag tag, int resource, PipeKind kind) {
        if (tag.contains(RESOURCE_KEYS[resource], Tag.TAG_COMPOUND)) {
            return tag.getCompound(RESOURCE_KEYS[resource]);
        }
        return kind.resource() == resource ? tag : new CompoundTag();
    }

    /**
     * Server only. Replaces one side's pull settings (insert = false) or insert settings with copied ones, filters
     * included. Every value is checked again (the tag comes from an item, which a creative player can edit): out-of-range
     * values become the default. Upgrades are not touched here; the caller installs them from the player's inventory.
     */
    public void pasteSide(Direction side, boolean insert, CompoundTag tag, HolderLookup.Provider registries) {
        int i = side.get3DDataValue();
        int bit = 1 << i;
        int keptBefore = keptPullMask();
        if (insert) {
            int newPriority = tag.getInt("Priority");
            priority[i] = newPriority >= MIN_PRIORITY && newPriority <= MAX_PRIORITY ? newPriority : 0;
            int newRedstone = tag.getByte("Redstone"); // copies made before step 40 have none: Always
            insertRedstone[i] = (byte) (newRedstone >= 0 && newRedstone < INSERT_REDSTONE_MODES ? newRedstone : 0);
        } else {
            int newRedstone = tag.getByte("Redstone");
            redstone[i] = (byte) (newRedstone >= 0 && newRedstone < RedstoneMode.values().length ? newRedstone : 0);
            int newDistribution = tag.getByte("Distribution");
            distribution[i] = (byte) (newDistribution >= 0 && newDistribution < Distribution.values().length
                    ? newDistribution : 0);
            selfFeedMask = (byte) (tag.getBoolean("SendBack") ? selfFeedMask | bit : selfFeedMask & ~bit);
        }
        PipeKind kind = kind();
        for (int resource = 0; resource < PipeKind.RESOURCE_COUNT; resource++) {
            if (!kind.carries(resource)) {
                continue;
            }
            CompoundTag part = copiedResource(tag, resource, kind);
            PipeKind units = PipeKind.ofResource(resource);
            int r = slot(side, resource);
            ItemFilter filter = new ItemFilter();
            filter.load(part.getList("Filter", Tag.TAG_COMPOUND), registries);
            ItemFilter pasted = filter.isActive() && units.hasFilter() ? filter : null;
            boolean deny = part.getBoolean("Deny");
            boolean off = part.getBoolean("Off");
            if (insert) {
                insertOffBits = off ? insertOffBits | (1 << r) : insertOffBits & ~(1 << r);
                maxStock[r] = inRange(part.getInt("MaxStock"), units.maxStock());
                insertDenyBits = deny ? insertDenyBits | (1 << r) : insertDenyBits & ~(1 << r);
                insertFilters[r] = pasted;
            } else {
                pullOffBits = off ? pullOffBits | (1 << r) : pullOffBits & ~(1 << r);
                filterDenyBits = deny ? filterDenyBits | (1 << r) : filterDenyBits & ~(1 << r);
                batch[r] = inRange(part.getInt("Batch"), units.maxBatch());
                keep[r] = inRange(part.getInt("Keep"), units.maxStock());
                fill[r] = inRange(part.getInt("Fill"), units.maxStock());
                filters[r] = pasted;
            }
        }
        filterMask = computeFilterMask();
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
            NetworkManager.get(serverLevel).wakeExtractor(worldPosition);
            if (keptPullMask() != keptBefore) {
                FlowPipeBlock.refreshArms(serverLevel, worldPosition); // a pull side with no inventory may appear/disappear
            }
            if (insert) {
                // Destinations read their insert settings when the network is built: rebuild it.
                NetworkManager.get(serverLevel).onPipeChanged(worldPosition);
            }
        }
    }

    private static int inRange(int value, int max) {
        return value >= 0 && value <= max ? value : 0;
    }

    // ---------------------------------------------------------------- upgrades

    public int upgradeLevel(Direction side) {
        return upgrades[side.get3DDataValue()];
    }

    public boolean hasUpgrades() {
        for (byte level : upgrades) {
            if (level != 0) {
                return true;
            }
        }
        return false;
    }

    /** Server only. Callers hand out or take the matching item themselves. */
    public void setUpgradeLevel(Direction side, int level) {
        if (level < 0 || level > 5) {
            return;
        }
        boolean hadOne = upgrades[side.get3DDataValue()] != 0;
        upgrades[side.get3DDataValue()] = (byte) level;
        setChanged();
        if (this.level instanceof ServerLevel serverLevel) {
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
            NetworkManager.get(serverLevel).wakeExtractor(worldPosition);
            if (hadOne != (level != 0)) {
                FlowPipeBlock.refreshArms(serverLevel, worldPosition); // a pull side with no inventory may appear/disappear
            }
        }
    }

    /** Drops every installed upgrade as an item at the pipe and clears them. Never deletes upgrades. */
    public void dropUpgrades(Level world, BlockPos pos) {
        for (int i = 0; i < upgrades.length; i++) {
            UpgradeItem item = ModItems.upgradeFor(upgrades[i]);
            if (item != null) {
                FlowPipeBlock.dropAt(world, pos, new ItemStack(item)); // review R2: not popResource (doTileDrops)
            }
            upgrades[i] = 0;
        }
        setChanged();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onLoad() {
        super.onLoad();
        syncExtractor();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // Called when the pipe is broken and when its chunk unloads.
        if (level instanceof ServerLevel serverLevel) {
            NetworkManager manager = NetworkManager.getExisting(serverLevel);
            if (manager != null) {
                manager.setExtractor(worldPosition, 0, null);
            }
        }
    }

    /** Tells the network manager which sides of this pipe pull. Server only. */
    /**
     * Step 93: takes over another pipe's settings (a tier swap). Same as loading from disk, then the pull sides are
     * registered again at once (onLoad may already have run with no settings). Server only.
     */
    void takeOver(CompoundTag saved) {
        load(saved);
        setChanged();
        syncExtractor();
    }

    private void syncExtractor() {
        if (level instanceof ServerLevel serverLevel && !isRemoved()) {
            NetworkManager.get(serverLevel).setExtractor(worldPosition, extractMask, this);
        }
    }

    // ---------------------------------------------------------------- save / sync

    /** Disk save: the settings plus the filters. */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        HolderLookup.Provider registries = null; // 1.20.1: item NBT needs no registry lookup
        saveSettings(tag);
        saveFilters(tag, "Filters", filters, registries);
        saveFilters(tag, "InsertFilters", insertFilters, registries);
        tag.putByte("PullFromInsert", pullFromInsertMask);
    }

    private static void saveFilters(CompoundTag tag, String key, ItemFilter[] array, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            if (array[i] != null && array[i].isActive()) {
                CompoundTag entry = new CompoundTag();
                entry.putByte("Side", (byte) (i % 6));
                entry.putByte("Resource", (byte) (i / 6));
                entry.put("Entries", array[i].save(registries));
                list.add(entry);
            }
        }
        if (!list.isEmpty()) {
            tag.put(key, list);
        }
    }

    /**
     * Everything clients need (sent in block updates): no filters.
     * Review R14 (2026-11-09): a value that is all zero (its default) is left out, and every loader already reads a
     * missing key as zero, in old and new versions alike. A pipe with one plain pull side saves and sends a few bytes
     * instead of about 600. "Extract" is always written: a client ignores an update with nothing in it, and would
     * then keep the values it had.
     */
    private void saveSettings(CompoundTag tag) {
        tag.putByte("Extract", extractMask);
        putByteIfSet(tag, "Disconnect", disconnectMask);
        putBytesIfSet(tag, "Redstone", redstone);
        putBytesIfSet(tag, "Distribution", distribution);
        putByteIfSet(tag, "SelfFeed", selfFeedMask);
        putBytesIfSet(tag, "Upgrades", upgrades);
        putIntIfSet(tag, "FilterDeny", filterDenyBits);
        putByteIfSet(tag, "FilterMask", filterMask);
        putIntsIfSet(tag, "Batch", batch);
        putIntsIfSet(tag, "Keep", keep);
        putIntsIfSet(tag, "Fill", fill);
        putIntsIfSet(tag, "Priority", priority);
        putIntIfSet(tag, "InsertDeny", insertDenyBits);
        putIntsIfSet(tag, "MaxStock", maxStock);
        putIntIfSet(tag, "PullOff", pullOffBits);
        putIntIfSet(tag, "InsertOff", insertOffBits);
        putBytesIfSet(tag, "InsertRedstone", insertRedstone);
        putByteIfSet(tag, "View", viewMode);
    }

    // Each writes the same kind of tag as before (the loaders tell old saves apart by the kind of tag).
    private static void putByteIfSet(CompoundTag tag, String key, byte value) {
        if (value != 0) {
            tag.putByte(key, value);
        }
    }

    private static void putIntIfSet(CompoundTag tag, String key, int value) {
        if (value != 0) {
            tag.putInt(key, value);
        }
    }

    private static void putBytesIfSet(CompoundTag tag, String key, byte[] values) {
        for (byte value : values) {
            if (value != 0) {
                tag.putByteArray(key, values);
                return;
            }
        }
    }

    private static void putIntsIfSet(CompoundTag tag, String key, int[] values) {
        for (int value : values) {
            if (value != 0) {
                tag.putIntArray(key, values);
                return;
            }
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        HolderLookup.Provider registries = null; // 1.20.1: item NBT needs no registry lookup
        extractMask = (byte) (tag.getByte("Extract") & 0b111111);
        disconnectMask = (byte) (tag.getByte("Disconnect") & 0b111111);
        copyValidated(tag.getByteArray("Redstone"), redstone, RedstoneMode.values().length);
        copyValidated(tag.getByteArray("InsertRedstone"), insertRedstone, INSERT_REDSTONE_MODES); // none before step 40
        int view = tag.getByte("View");
        viewMode = (byte) (view >= VIEW_DEFAULT && view <= VIEW_ADVANCED ? view : VIEW_DEFAULT);
        if (tag.contains("PullFromInsert")) { // disk only: block updates to clients don't carry it
            pullFromInsertMask = (byte) (tag.getByte("PullFromInsert") & 0b111111);
        }
        copyValidated(tag.getByteArray("Distribution"), distribution, Distribution.values().length);
        selfFeedMask = (byte) (tag.getByte("SelfFeed") & 0b111111);
        copyValidated(tag.getByteArray("Upgrades"), upgrades, 6);
        int[] savedPriority = tag.getIntArray("Priority");
        for (int i = 0; i < 6; i++) {
            int value = i < savedPriority.length ? savedPriority[i] : 0;
            priority[i] = value >= MIN_PRIORITY && value <= MAX_PRIORITY ? value : 0;
        }
        // Saves from before step 31 hold one value per side for the pipe's own resource: put them there.
        PipeKind kind = kind();
        int legacyResource = kind.firstResource();
        filterDenyBits = loadBits(tag, "FilterDeny", legacyResource, kind);
        insertDenyBits = loadBits(tag, "InsertDeny", legacyResource, kind);
        pullOffBits = loadBits(tag, "PullOff", legacyResource, kind);
        insertOffBits = loadBits(tag, "InsertOff", legacyResource, kind);
        loadNumbers(tag.getIntArray("Batch"), batch, legacyResource, kind, true);
        loadNumbers(tag.getIntArray("Keep"), keep, legacyResource, kind, false);
        loadNumbers(tag.getIntArray("Fill"), fill, legacyResource, kind, false);
        loadNumbers(tag.getIntArray("MaxStock"), maxStock, legacyResource, kind, false);
        loadFilters(tag, "InsertFilters", insertFilters, legacyResource, kind, registries);
        // Block updates to clients don't include filters, so a missing list just means "none here".
        boolean hadFilters = loadFilters(tag, "Filters", filters, legacyResource, kind, registries);
        // Disk saves have the filters themselves; client updates only carry the mask.
        filterMask = hadFilters ? computeFilterMask() : (byte) (tag.getByte("FilterMask") & 0b111111);
    }

    /** Reads per-resource bits; an old byte value (6 side bits) belongs to the pipe's own resource. */
    private static int loadBits(CompoundTag tag, String key, int legacyResource, PipeKind kind) {
        int bits = tag.getTagType(key) == Tag.TAG_BYTE ? (tag.getByte(key) & 0b111111) << (legacyResource * 6)
                : tag.getInt(key);
        int allowed = 0;
        for (int resource = 0; resource < PipeKind.RESOURCE_COUNT; resource++) {
            if (kind.carries(resource)) {
                allowed |= 0b111111 << (resource * 6);
            }
        }
        return bits & allowed;
    }

    /**
     * Copies saved numbers in, each checked against its resource's limit (items, mB or FE); resources this pipe doesn't
     * carry stay 0. An old 6-value array belongs to the pipe's own resource.
     */
    private static void loadNumbers(int[] from, int[] to, int legacyResource, PipeKind kind, boolean isBatch) {
        java.util.Arrays.fill(to, 0);
        boolean legacy = from.length == 6;
        for (int i = 0; i < SLOTS; i++) {
            int resource = i / 6;
            if (!kind.carries(resource)) {
                continue;
            }
            int index = legacy ? (resource == legacyResource ? i % 6 : -1) : i;
            int value = index >= 0 && index < from.length ? from[index] : 0;
            PipeKind units = PipeKind.ofResource(resource);
            to[i] = inRange(value, isBatch ? units.maxBatch() : units.maxStock());
        }
    }

    /** Loads a filter list into the array (cleared first). Returns true if the tag had the list at all. */
    private static boolean loadFilters(CompoundTag tag, String key, ItemFilter[] array, int legacyResource,
                                       PipeKind kind, HolderLookup.Provider registries) {
        java.util.Arrays.fill(array, null);
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (int k = 0; k < list.size(); k++) {
            CompoundTag entry = list.getCompound(k);
            int side = entry.getByte("Side");
            int resource = entry.contains("Resource") ? entry.getByte("Resource") : legacyResource;
            if (side >= 0 && side < 6 && kind.carries(resource) && PipeKind.ofResource(resource).hasFilter()) {
                ItemFilter filter = new ItemFilter();
                filter.load(entry.getList("Entries", Tag.TAG_COMPOUND), registries);
                array[resource * 6 + side] = filter.isActive() ? filter : null;
            }
        }
        return !list.isEmpty();
    }

    private static void copyValidated(byte[] from, byte[] to, int valueCount) {
        for (int i = 0; i < to.length; i++) {
            byte value = i < from.length ? from[i] : 0;
            to[i] = value >= 0 && value < valueCount ? value : 0;
        }
    }

    // Clients need the settings too: for predicted pipe arms and for the settings screen.
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveSettings(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Client: settings arrived from the server. If an upgrade changed, redraw this block so the
     * ring behind the pull cap shows the new tier color. Other setting changes don't redraw anything.
     */
    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        int before = packedUpgrades();
        super.onDataPacket(net, packet);
        if (level != null && level.isClientSide() && packedUpgrades() != before) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_IMMEDIATE);
        }
    }

    /** All six upgrade levels in one int (3 bits each), to spot changes without allocating. */
    private int packedUpgrades() {
        int packed = 0;
        for (int i = 0; i < upgrades.length; i++) {
            packed |= (upgrades[i] & 0b111) << (i * 3);
        }
        return packed;
    }
}
