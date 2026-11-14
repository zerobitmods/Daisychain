package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.filter.FilterMatch;
import com.daisychain.filter.FilterNumbers;
import com.daisychain.item.CopiedSettings;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Daisychain.MOD_ID);

    /** How a filter entry matches; only on the display copies in filter slots. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FilterMatch>> FILTER_MATCH =
            COMPONENTS.registerComponentType("filter_match", builder -> builder
                    .persistent(FilterMatch.CODEC)
                    .networkSynchronized(FilterMatch.STREAM_CODEC));

    /** A filter entry's own Batch / Keep / Fill; only on the display copies in filter slots, and only when set. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FilterNumbers>> FILTER_NUMBERS =
            COMPONENTS.registerComponentType("filter_numbers", builder -> builder
                    .persistent(FilterNumbers.CODEC)
                    .networkSynchronized(FilterNumbers.STREAM_CODEC));

    /** One side's settings held by a Settings Copier (step 24). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CopiedSettings>> COPIED_SETTINGS =
            COMPONENTS.registerComponentType("copied_settings", builder -> builder
                    .persistent(CopiedSettings.CODEC)
                    .networkSynchronized(CopiedSettings.STREAM_CODEC));

    /** The fluid a fluid filter entry stands for; only on daisychain:fluid ghosts in filters (step 27). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SimpleFluidContent>> GHOST_FLUID =
            COMPONENTS.registerComponentType("ghost_fluid", builder -> builder
                    .persistent(SimpleFluidContent.CODEC)
                    .networkSynchronized(SimpleFluidContent.STREAM_CODEC));

    /** Step 125: the block a facade covers a pipe with (sent to clients as its block-state id). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockState>> FACADE =
            COMPONENTS.registerComponentType("facade", builder -> builder
                    .persistent(BlockState.CODEC)
                    .networkSynchronized(ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY)));

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
