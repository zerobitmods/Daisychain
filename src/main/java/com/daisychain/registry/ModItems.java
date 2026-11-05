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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Daisychain.MOD_ID);

    public static final DeferredItem<BlockItem> ITEM_PIPE = ITEMS.registerSimpleBlockItem("item_pipe", ModBlocks.ITEM_PIPE);
    public static final DeferredItem<BlockItem> FLUID_PIPE = ITEMS.registerSimpleBlockItem("fluid_pipe", ModBlocks.FLUID_PIPE);
    public static final DeferredItem<EnergyPipeItem> ENERGY_PIPE = energyPipe("energy_pipe", ModBlocks.ENERGY_PIPE);
    public static final DeferredItem<EnergyPipeItem> IRON_ENERGY_PIPE =
            energyPipe("iron_energy_pipe", ModBlocks.IRON_ENERGY_PIPE);
    public static final DeferredItem<EnergyPipeItem> GOLD_ENERGY_PIPE =
            energyPipe("gold_energy_pipe", ModBlocks.GOLD_ENERGY_PIPE);
    public static final DeferredItem<EnergyPipeItem> DIAMOND_ENERGY_PIPE =
            energyPipe("diamond_energy_pipe", ModBlocks.DIAMOND_ENERGY_PIPE);
    public static final DeferredItem<EnergyPipeItem> NETHERITE_ENERGY_PIPE =
            energyPipe("netherite_energy_pipe", ModBlocks.NETHERITE_ENERGY_PIPE);
    public static final DeferredItem<EnergyPipeItem> NETHER_STAR_ENERGY_PIPE =
            energyPipe("nether_star_energy_pipe", ModBlocks.NETHER_STAR_ENERGY_PIPE);
    public static final DeferredItem<BlockItem> UNIVERSAL_PIPE =
            ITEMS.registerSimpleBlockItem("universal_pipe", ModBlocks.UNIVERSAL_PIPE);
    /**
     * The Bloom Wrench (step 62b; was the Probe, id daisychain:probe, and before that the Pipe Wrench, id daisychain:wrench).
     * Its behavior comes from the c:tools/wrench tag.
     */
    public static final DeferredItem<BloomWrenchItem> BLOOM_WRENCH =
            ITEMS.registerItem("bloom_wrench", BloomWrenchItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<SettingsCopierItem> SETTINGS_COPIER = ITEMS.register("settings_copier",
            () -> new SettingsCopierItem(new Item.Properties().stacksTo(1)));

    /** A fluid listed in a filter (never a real item in survival; not in the creative tab). */
    public static final DeferredItem<FluidGhostItem> FLUID_GHOST = ITEMS.register("fluid",
            () -> new FluidGhostItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<UpgradeItem> IRON_UPGRADE = upgrade(UpgradeTier.IRON);
    public static final DeferredItem<UpgradeItem> GOLD_UPGRADE = upgrade(UpgradeTier.GOLD);
    public static final DeferredItem<UpgradeItem> DIAMOND_UPGRADE = upgrade(UpgradeTier.DIAMOND);
    public static final DeferredItem<UpgradeItem> NETHERITE_UPGRADE = upgrade(UpgradeTier.NETHERITE);
    public static final DeferredItem<UpgradeItem> NETHER_STAR_UPGRADE = upgrade(UpgradeTier.NETHER_STAR);

    private ModItems() {
    }

    private static DeferredItem<EnergyPipeItem> energyPipe(String id, DeferredBlock<EnergyPipeBlock> block) {
        return ITEMS.register(id, () -> new EnergyPipeItem(block.get(), new Item.Properties()));
    }

    /** Every energy pipe item, basic first (index = tier). */
    public static Item[] energyPipes() {
        return new Item[]{ENERGY_PIPE.get(), IRON_ENERGY_PIPE.get(), GOLD_ENERGY_PIPE.get(), DIAMOND_ENERGY_PIPE.get(),
                NETHERITE_ENERGY_PIPE.get(), NETHER_STAR_ENERGY_PIPE.get()};
    }

    private static DeferredItem<UpgradeItem> upgrade(UpgradeTier tier) {
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
