package com.daisychain.compat.mekanism;

import com.daisychain.compat.ChemicalOps;
import com.daisychain.compat.Chemicals;
import com.daisychain.filter.ChemicalId;
import com.daisychain.network.CapCache;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.ChemicalTags;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.infuse.IInfusionHandler;
import mekanism.api.chemical.pigment.IPigmentHandler;
import mekanism.api.chemical.slurry.ISlurryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.tags.ITag;
import net.minecraftforge.registries.tags.ITagManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Step F95 (port of step 171), F96 (port of 172): the only class that names Mekanism. Only {@link com.daisychain.compat.Chemicals} calls
 * it, and only when Mekanism is loaded; every call is wrapped there, so a Mekanism change that breaks this class
 * switches gas off instead of crashing.
 * <p>
 * Mekanism 10.4 (1.20.1) has four chemical types, each with its own handler and capability: gas, infusion, pigment,
 * slurry. They are reached the standard Forge way, by the handler interface from Mekanism's published api package
 * (CapabilityManager.get with a CapabilityToken), exactly as Mekanism gets them itself; nothing from Mekanism's
 * internal classes.
 */
public final class MekanismChemicals {
    static final Capability<IGasHandler> GAS = CapabilityManager.get(new CapabilityToken<>() {
    });
    static final Capability<IInfusionHandler> INFUSION = CapabilityManager.get(new CapabilityToken<>() {
    });
    static final Capability<IPigmentHandler> PIGMENT = CapabilityManager.get(new CapabilityToken<>() {
    });
    static final Capability<ISlurryHandler> SLURRY = CapabilityManager.get(new CapabilityToken<>() {
    });

    /** Step F96: the four capabilities in Chemicals' type order (gas, infusion, pigment, slurry). */
    private static final Capability<?>[] BY_TYPE = {GAS, INFUSION, PIGMENT, SLURRY};
    /** Step F96: Mekanism's handlers and stacks behind Daisychain's ChemicalOps (one generic adapter for all four). */
    @SuppressWarnings("rawtypes")
    private static final ChemicalOps OPS = new Ops();

    private MekanismChemicals() {
    }

    /** Step F96: CapCaches for the four types of the block at pos, seen from side (made on first use of each). */
    public static Object newCache(ServerLevel level, BlockPos pos, Direction side, @Nullable BooleanSupplier onValid,
                                  @Nullable Runnable onInvalidate) {
        CapCache<?>[] caches = new CapCache<?>[BY_TYPE.length];
        for (int type = 0; type < BY_TYPE.length; type++) {
            caches[type] = onValid == null || onInvalidate == null ? CapCache.create(BY_TYPE[type], level, pos, side)
                    : CapCache.create(BY_TYPE[type], level, pos, side, onValid, onInvalidate);
        }
        return caches;
    }

    /** Step F96: takes the caches' listeners off the block. */
    public static void releaseCache(Object cache) {
        for (CapCache<?> one : (CapCache<?>[]) cache) {
            one.release();
        }
    }

    /** Step F96: the handler of one type the caches hold now, or null. */
    @Nullable
    public static Object handler(Object cache, int type) {
        return ((CapCache<?>[]) cache)[type].getCapability();
    }

    /** Step F96: the adapter for a handler of any type (10.4's handlers share one generic api). */
    @SuppressWarnings("unchecked")
    public static ChemicalOps<?, ?> ops(int type) {
        return OPS;
    }

    /** True if the block at pos offers any chemical type on that side. Only for connecting pipes. */
    public static boolean hasHandler(Level level, BlockPos pos, Direction side) {
        return CapCache.has(level, pos, side, GAS) || CapCache.has(level, pos, side, INFUSION)
                || CapCache.has(level, pos, side, PIGMENT) || CapCache.has(level, pos, side, SLURRY);
    }

    /**
     * Startup check (after mods registered their capabilities): true if all four capabilities are registered.
     * False means Mekanism stopped registering one under this interface, and gas pipes would sit idle.
     */
    public static boolean capabilitiesRegistered() {
        return GAS.isRegistered() && INFUSION.isRegistered() && PIGMENT.isRegistered() && SLURRY.isRegistered();
    }

    // ---------------------------------------------------------------- step F102 (port of 178): filters

    /** The registries and tag holders of the four types, in Chemicals' type order. */
    @SuppressWarnings("rawtypes")
    private static IForgeRegistry registry(int type) {
        return switch (type) {
            case 0 -> MekanismAPI.gasRegistry();
            case 1 -> MekanismAPI.infuseTypeRegistry();
            case 2 -> MekanismAPI.pigmentRegistry();
            default -> MekanismAPI.slurryRegistry();
        };
    }

    @SuppressWarnings("rawtypes")
    private static ChemicalTags tags(int type) {
        return switch (type) {
            case 0 -> ChemicalTags.GAS;
            case 1 -> ChemicalTags.INFUSE_TYPE;
            case 2 -> ChemicalTags.PIGMENT;
            default -> ChemicalTags.SLURRY;
        };
    }

    @Nullable
    private static Chemical<?> chemical(int type, ResourceLocation id) {
        Object found = registry(type).getValue(id);
        return found instanceof Chemical<?> chemical && !chemical.isEmptyType() ? chemical : null;
    }

    public static boolean exists(int type, ResourceLocation id) {
        return registry(type).containsKey(id) && chemical(type, id) != null;
    }

    @Nullable
    public static Object byId(int type, ResourceLocation id) {
        return exists(type, id) ? chemical(type, id) : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    private static ITagManager manager(int type) {
        return (ITagManager) tags(type).getManager().orElse(null);
    }

    /** Forge's tag object for this tag (kept across reloads; empty until the tag exists), or null without tags. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Nullable
    public static Object tag(int type, ResourceLocation tag) {
        ITagManager manager = manager(type);
        return manager == null ? null : manager.getTag(tags(type).tag(tag));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean tagExists(int type, ResourceLocation tag) {
        ITagManager manager = manager(type);
        return manager != null && manager.isKnownTagName(tags(type).tag(tag));
    }

    public static List<String> tagsOf(int type, ResourceLocation id) {
        List<String> names = new ArrayList<>();
        Chemical<?> chemical = chemical(type, id);
        if (chemical != null) {
            chemical.getTags().forEach(tag -> names.add(tag.location().toString()));
        }
        return names;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<String> allTagNames(int type) {
        List<String> names = new ArrayList<>();
        ITagManager manager = manager(type);
        if (manager != null) {
            // Only bound tags: getTag adds an empty, unbound tag for any name it is asked about (the filter does that
            // for a tag entry), and those must not show up in the picker.
            ((java.util.stream.Stream<ITag>) manager.stream()).filter(ITag::isBound)
                    .forEach(tag -> names.add(((TagKey<?>) tag.getKey()).location().toString()));
        }
        return names;
    }

    /** The first chemical an item holds, trying gas, infusion, pigment and slurry in turn. */
    @SuppressWarnings("rawtypes")
    @Nullable
    public static ChemicalId idInItem(ItemStack stack) {
        for (int type = 0; type < BY_TYPE.length; type++) {
            IChemicalHandler handler = (IChemicalHandler) stack.getCapability(BY_TYPE[type]).resolve().orElse(null);
            if (handler == null) {
                continue;
            }
            for (int i = 0; i < handler.getTanks(); i++) {
                ChemicalStack<?> inTank = handler.getChemicalInTank(i);
                if (!inTank.isEmpty()) {
                    ResourceLocation id = inTank.getType().getRegistryName();
                    if (id != null) {
                        return new ChemicalId(type, id);
                    }
                }
            }
        }
        return null;
    }

    /** JEI lists each type as its own ingredient (GasStack, InfusionStack...); all are api ChemicalStacks. */
    @Nullable
    public static ChemicalId idOfIngredient(Object ingredient) {
        if (ingredient instanceof ChemicalStack<?> stack && !stack.isEmpty()) {
            ResourceLocation id = stack.getType().getRegistryName();
            int type = typeIndex(ChemicalType.getTypeFor(stack));
            return id == null || type < 0 ? null : new ChemicalId(type, id);
        }
        return null;
    }

    private static int typeIndex(ChemicalType type) {
        return switch (type) {
            case GAS -> 0;
            case INFUSION -> 1;
            case PIGMENT -> 2;
            case SLURRY -> 3;
        };
    }

    @Nullable
    public static Component name(int type, ResourceLocation id) {
        Chemical<?> chemical = chemical(type, id);
        return chemical == null ? null : chemical.getTextComponent();
    }

    @Nullable
    public static Chemicals.Look look(int type, ResourceLocation id) {
        Chemical<?> chemical = chemical(type, id);
        return chemical == null ? null : new Chemicals.Look(chemical.getIcon(), chemical.getTint());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Ops implements ChemicalOps<IChemicalHandler, ChemicalStack> {
        @Override
        public int tanks(IChemicalHandler handler) {
            return handler.getTanks();
        }

        @Override
        public ChemicalStack inTank(IChemicalHandler handler, int tank) {
            return handler.getChemicalInTank(tank);
        }

        @Override
        public boolean isEmpty(ChemicalStack stack) {
            return stack.isEmpty();
        }

        @Override
        public long amount(ChemicalStack stack) {
            return stack.getAmount();
        }

        @Override
        public Object chemical(ChemicalStack stack) {
            return stack.getType();
        }

        @Override
        public ChemicalStack withAmount(ChemicalStack stack, long amount) {
            ChemicalStack copy = stack.copy();
            copy.setAmount(amount);
            return copy;
        }

        @Override
        public ChemicalStack extract(IChemicalHandler handler, ChemicalStack want, boolean simulate) {
            return handler.extractChemical(want, simulate ? Action.SIMULATE : Action.EXECUTE);
        }

        @Override
        public long insert(IChemicalHandler handler, ChemicalStack stack, boolean simulate) {
            ChemicalStack left = handler.insertChemical(stack, simulate ? Action.SIMULATE : Action.EXECUTE);
            return stack.getAmount() - left.getAmount();
        }

        @Override
        public boolean hasTag(Object chemical, Object tag) {
            // tag is Forge's tag object from tag(type, name): contains looks in its cached list, no allocation.
            // (Chemical.is(TagKey) would allocate Optionals and lambdas on every call.)
            return ((ITag) tag).contains(chemical);
        }

        @Override
        public String modId(Object chemical) {
            ResourceLocation id = ((Chemical<?>) chemical).getRegistryName();
            return id == null ? "" : id.getNamespace();
        }
    }
}
