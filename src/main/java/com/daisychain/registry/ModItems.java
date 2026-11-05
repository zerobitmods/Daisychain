package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.item.BloomWrenchItem;
import com.daisychain.item.EnergyPipeItem;
import com.daisychain.item.FluidGhostItem;
import com.daisychain.item.SettingsCopierItem;
import com.daisychain.item.UpgradeItem;
import com.daisychain.item.UpgradeTier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Daisychain.MOD_ID);

    public static final RegistryObject<BlockItem> ITEM_PIPE = ITEMS.register("item_pipe", () -> new BlockItem(ModBlocks.ITEM_PIPE.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> FLUID_PIPE = ITEMS.register("fluid_pipe", () -> new BlockItem(ModBlocks.FLUID_PIPE.get(), new Item.Properties()));
    public static final RegistryObject<EnergyPipeItem> ENERGY_PIPE = energyPipe("energy_pipe", ModBlocks.ENERGY_PIPE);
    public static final RegistryObject<EnergyPipeItem> IRON_ENERGY_PIPE =
            energyPipe("iron_energy_pipe", ModBlocks.IRON_ENERGY_PIPE);
    public static final RegistryObject<EnergyPipeItem> GOLD_ENERGY_PIPE =
            energyPipe("gold_energy_pipe", ModBlocks.GOLD_ENERGY_PIPE);
    public static final RegistryObject<EnergyPipeItem> DIAMOND_ENERGY_PIPE =
            energyPipe("diamond_energy_pipe", ModBlocks.DIAMOND_ENERGY_PIPE);
    public static final RegistryObject<EnergyPipeItem> NETHERITE_ENERGY_PIPE =
            energyPipe("netherite_energy_pipe", ModBlocks.NETHERITE_ENERGY_PIPE);
    public static final RegistryObject<EnergyPipeItem> NETHER_STAR_ENERGY_PIPE =
            energyPipe("nether_star_energy_pipe", ModBlocks.NETHER_STAR_ENERGY_PIPE);
    public static final RegistryObject<BlockItem> UNIVERSAL_PIPE =
            ITEMS.register("universal_pipe", () -> new BlockItem(ModBlocks.UNIVERSAL_PIPE.get(), new Item.Properties()));
    /**
     * The Bloom Wrench (step 62b; was the Probe, id daisychain:probe, and before that the Pipe Wrench, id daisychain:wrench).
     * Its behavior comes from the forge:tools/wrench tag.
     */
    public static final RegistryObject<BloomWrenchItem> BLOOM_WRENCH =
            ITEMS.register("bloom_wrench", () -> new BloomWrenchItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<SettingsCopierItem> SETTINGS_COPIER = ITEMS.register("settings_copier",
            () -> new SettingsCopierItem(new Item.Properties().stacksTo(1)));

    /** A fluid listed in a filter (never a real item in survival; not in the creative tab). */
    public static final RegistryObject<FluidGhostItem> FLUID_GHOST = ITEMS.register("fluid",
            () -> new FluidGhostItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<UpgradeItem> IRON_UPGRADE = upgrade(UpgradeTier.IRON);
    public static final RegistryObject<UpgradeItem> GOLD_UPGRADE = upgrade(UpgradeTier.GOLD);
    public static final RegistryObject<UpgradeItem> DIAMOND_UPGRADE = upgrade(UpgradeTier.DIAMOND);
    public static final RegistryObject<UpgradeItem> NETHERITE_UPGRADE = upgrade(UpgradeTier.NETHERITE);
    public static final RegistryObject<UpgradeItem> NETHER_STAR_UPGRADE = upgrade(UpgradeTier.NETHER_STAR);

    private ModItems() {
    }

    private static RegistryObject<EnergyPipeItem> energyPipe(String id, RegistryObject<EnergyPipeBlock> block) {
        return ITEMS.register(id, () -> new EnergyPipeItem(block.get(), new Item.Properties()));
    }

    /** Every energy pipe item, basic first (index = tier). */
    public static Item[] energyPipes() {
        return new Item[]{ENERGY_PIPE.get(), IRON_ENERGY_PIPE.get(), GOLD_ENERGY_PIPE.get(), DIAMOND_ENERGY_PIPE.get(),
                NETHERITE_ENERGY_PIPE.get(), NETHER_STAR_ENERGY_PIPE.get()};
    }

    private static RegistryObject<UpgradeItem> upgrade(UpgradeTier tier) {
        return ITEMS.register(tier.id() + "_upgrade", () -> new UpgradeItem(tier, new Item.Properties()));
    }

    /** The upgrade item for a saved upgrade level, or null for 0 / unknown levels. */
    public static UpgradeItem upgradeFor(int level) {
        return switch (level) {
            case 1 -> IRON_UPGRADE.get();
            case 2 -> GOLD_UPGRADE.get();
            case 3 -> DIAMOND_UPGRADE.get();
            case 4 -> NETHERITE_UPGRADE.get();
            case 5 -> NETHER_STAR_UPGRADE.get();
            default -> null;
        };
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
