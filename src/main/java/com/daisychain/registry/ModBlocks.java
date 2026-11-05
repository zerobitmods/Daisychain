package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FluidPipeBlock;
import com.daisychain.block.ItemPipeBlock;
import com.daisychain.block.UniversalPipeBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Daisychain.MOD_ID);

    public static final RegistryObject<ItemPipeBlock> ITEM_PIPE =
            BLOCKS.register("item_pipe", () -> new ItemPipeBlock(pipeProperties()));
    public static final RegistryObject<FluidPipeBlock> FLUID_PIPE =
            BLOCKS.register("fluid_pipe", () -> new FluidPipeBlock(pipeProperties().mapColor(MapColor.WATER)));
    /** Step 84: energy pipe tiers. The basic one keeps its old id, so existing worlds keep their pipes. */
    public static final RegistryObject<EnergyPipeBlock> ENERGY_PIPE = energyPipe(0, "energy_pipe");
    public static final RegistryObject<EnergyPipeBlock> IRON_ENERGY_PIPE = energyPipe(1, "iron_energy_pipe");
    public static final RegistryObject<EnergyPipeBlock> GOLD_ENERGY_PIPE = energyPipe(2, "gold_energy_pipe");
    public static final RegistryObject<EnergyPipeBlock> DIAMOND_ENERGY_PIPE = energyPipe(3, "diamond_energy_pipe");
    public static final RegistryObject<EnergyPipeBlock> NETHERITE_ENERGY_PIPE = energyPipe(4, "netherite_energy_pipe");
    public static final RegistryObject<EnergyPipeBlock> NETHER_STAR_ENERGY_PIPE =
            energyPipe(5, "nether_star_energy_pipe");
    public static final RegistryObject<UniversalPipeBlock> UNIVERSAL_PIPE =
            BLOCKS.register("universal_pipe", () -> new UniversalPipeBlock(pipeProperties().mapColor(MapColor.COLOR_BLACK)));

    private ModBlocks() {
    }

    private static RegistryObject<EnergyPipeBlock> energyPipe(int tier, String id) {
        return BLOCKS.register(id, () -> new EnergyPipeBlock(tier, pipeProperties().mapColor(MapColor.COLOR_GREEN)));
    }

    /** Every energy pipe block, basic first (index = tier). */
    public static EnergyPipeBlock[] energyPipes() {
        return new EnergyPipeBlock[]{ENERGY_PIPE.get(), IRON_ENERGY_PIPE.get(), GOLD_ENERGY_PIPE.get(),
                DIAMOND_ENERGY_PIPE.get(), NETHERITE_ENERGY_PIPE.get(), NETHER_STAR_ENERGY_PIPE.get()};
    }

    /** Every pipe block: item, fluid, all energy tiers, universal. */
    public static Block[] allPipes() {
        return new Block[]{ITEM_PIPE.get(), FLUID_PIPE.get(), ENERGY_PIPE.get(), IRON_ENERGY_PIPE.get(),
                GOLD_ENERGY_PIPE.get(), DIAMOND_ENERGY_PIPE.get(), NETHERITE_ENERGY_PIPE.get(),
                NETHER_STAR_ENERGY_PIPE.get(), UNIVERSAL_PIPE.get()};
    }

    private static BlockBehaviour.Properties pipeProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                // Quick to mine (0.5), but as blast resistant as stone (6) so creepers don't wreck networks.
                .strength(0.5F, 6.0F)
                .sound(SoundType.METAL)
                .noOcclusion()
                // Count as solid so flowing water and lava can't wash pipes away (thin blocks aren't solid by default).
                .forceSolidOn()
                // Pistons can't push or break pipes.
                .pushReaction(PushReaction.BLOCK);
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
