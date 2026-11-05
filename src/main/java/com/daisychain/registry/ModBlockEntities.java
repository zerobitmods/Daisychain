package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.block.PipeDataBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Daisychain.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeDataBlockEntity>> PIPE_DATA =
            BLOCK_ENTITY_TYPES.register("pipe_data",
                    () -> BlockEntityType.Builder.of(PipeDataBlockEntity::new, ModBlocks.allPipes()).build(null));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
