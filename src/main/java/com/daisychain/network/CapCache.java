package com.daisychain.network;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;

/**
 * Caches one capability of the block entity next to a pipe (1.20.1 Forge stand-in for NeoForge's BlockCapabilityCache).
 *
 * <ul>
 *   <li>A found handler is reused until its LazyOptional is invalidated or its block entity is removed, so the hot
 *       path is a few field reads and a loaded check, with no allocation.</li>
 *   <li>F41: once per {@link #REVALIDATE_TICKS} it asks the block entity again, because some blocks switch to a
 *       different handler without invalidating the old one (Functional Storage's Controller Access Point offers its
 *       own upgrade slot until it is linked, then the controller's inventory). A different handler replaces the
 *       cached one.</li>
 *   <li>When nothing is there, the lookup is repeated at most once per {@link #RECHECK_TICKS} game ticks, so an empty
 *       side never costs a block entity lookup every tick.</li>
 *   <li>Never loads chunks: an unloaded position gives null.</li>
 *   <li>Calls {@code onInvalidate} when a cached handler goes away (only while {@code isValid} says the owner still
 *       cares), like NeoForge's invalidation listener.</li>
 * </ul>
 */
public final class CapCache<T> {
    /** How often an empty side looks again for a handler that appeared without a neighbor update. */
    private static final int RECHECK_TICKS = 20;
    /** F41: how often a cached handler is compared with what the block entity offers now. */
    static final int REVALIDATE_TICKS = 20;

    private final Capability<T> capability;
    private final ServerLevel level;
    private final BlockPos pos;
    private final Direction side;
    @Nullable
    private final BooleanSupplier isValid;
    @Nullable
    private final Runnable onInvalidate;

    @Nullable
    private T value;
    @Nullable
    private BlockEntity owner;
    @Nullable
    private LazyOptional<T> optional;
    /** Set when the cached handler is a cauldron (no block entity): valid while a cauldron is still there. */
    @Nullable
    private CauldronFluidHandler cauldron;
    /** Game time of the last lookup that found nothing; Long.MIN_VALUE = look now. */
    private long emptySince = Long.MIN_VALUE;
    /** F41: game time the cached handler was last confirmed. */
    private long checkedAt;

    private CapCache(Capability<T> capability, ServerLevel level, BlockPos pos, Direction side,
                     @Nullable BooleanSupplier isValid, @Nullable Runnable onInvalidate) {
        this.capability = capability;
        this.level = level;
        this.pos = pos.immutable();
        this.side = side;
        this.isValid = isValid;
        this.onInvalidate = onInvalidate;
    }

    public static <T> CapCache<T> create(Capability<T> capability, ServerLevel level, BlockPos pos, Direction side) {
        return new CapCache<>(capability, level, pos, side, null, null);
    }

    public static <T> CapCache<T> create(Capability<T> capability, ServerLevel level, BlockPos pos, Direction side,
                                         BooleanSupplier isValid, Runnable onInvalidate) {
        return new CapCache<>(capability, level, pos, side, isValid, onInvalidate);
    }

    /** The handler on that face, or null if there is none or the chunk isn't loaded. */
    @Nullable
    public T getCapability() {
        if (!level.isLoaded(pos)) {
            return null;
        }
        T current = value;
        BlockEntity be = owner;
        if (current != null && be != null && !be.isRemoved()) {
            long now = level.getGameTime();
            if (now - checkedAt < REVALIDATE_TICKS) {
                return current;
            }
            checkedAt = now;
            LazyOptional<T> found = be.getCapability(capability, side);
            if (found == optional) {
                return current;
            }
            T resolved = found.isPresent() ? found.orElse(null) : null;
            if (resolved == current) {
                return current; // a new LazyOptional around the same handler
            }
            // F41: the block offers a different handler (or none) without invalidating the old one.
            invalidate();
            if (onInvalidate != null && (isValid == null || isValid.getAsBoolean())) {
                onInvalidate.run();
            }
            return lookUp();
        }
        if (current != null && cauldron != null) {
            if (cauldron.stillThere()) {
                return current;
            }
            invalidate();
            if (onInvalidate != null && (isValid == null || isValid.getAsBoolean())) {
                onInvalidate.run();
            }
        }
        if (current == null && emptySince != Long.MIN_VALUE && level.getGameTime() - emptySince < RECHECK_TICKS) {
            return null;
        }
        return lookUp();
    }

    /** Forget the cached handler; the next call looks again right away. */
    public void invalidate() {
        value = null;
        owner = null;
        optional = null;
        cauldron = null;
        emptySince = Long.MIN_VALUE;
    }

    @Nullable
    private T lookUp() {
        value = null;
        owner = null;
        optional = null;
        cauldron = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && !be.isRemoved()) {
            LazyOptional<T> found = be.getCapability(capability, side);
            T resolved = found.isPresent() ? found.orElse(null) : null;
            if (resolved != null) {
                value = resolved;
                owner = be;
                optional = found;
                emptySince = Long.MIN_VALUE;
                checkedAt = level.getGameTime();
                // Weak: a network rebuild drops this cache, and the neighbor's listener must not keep it alive.
                WeakReference<CapCache<T>> self = new WeakReference<>(this);
                found.addListener(gone -> {
                    CapCache<T> cache = self.get();
                    if (cache != null) {
                        cache.onGone(gone);
                    }
                });
                return resolved;
            }
        }
        if (be == null && capability == ForgeCapabilities.FLUID_HANDLER
                && CauldronFluidHandler.isCauldron(level.getBlockState(pos))) {
            // Vanilla cauldrons have no block entity; Forge 1.20.1 gives them no fluid handler, so we add one.
            CauldronFluidHandler handler = new CauldronFluidHandler(level, pos);
            cauldron = handler;
            emptySince = Long.MIN_VALUE;
            @SuppressWarnings("unchecked")
            T asT = (T) handler;
            value = asT;
            return asT;
        }
        emptySince = level.getGameTime();
        return null;
    }

    private void onGone(LazyOptional<T> gone) {
        if (gone != optional) {
            return; // an older handler we already replaced
        }
        invalidate();
        if (onInvalidate != null && (isValid == null || isValid.getAsBoolean())) {
            onInvalidate.run();
        }
    }

    /** One-off check (not cached): does the block entity at pos offer this capability on that face? */
    public static boolean has(Level level, BlockPos pos, Direction side, Capability<?> capability) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return capability == ForgeCapabilities.FLUID_HANDLER
                    && CauldronFluidHandler.isCauldron(level.getBlockState(pos));
        }
        return be.getCapability(capability, side).isPresent();
    }
}
