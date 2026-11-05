package com.daisychain.filter;

import com.daisychain.Compat;
import com.daisychain.block.PipeKind;
import com.daisychain.registry.ModDataComponents;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;


import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The filter of one pull side: up to 54 "ghost" entries (copies of an item, never real items), each with a match mode:
 * the item, the item with exactly its data, a tag, or a whole mod.
 * Entries are compiled into lookup structures whenever they change, so checking an item never allocates:
 * ITEM entries are one hash lookup; DATA entries only compare data for items that have DATA entries;
 * TAG entries check the item's tags; MOD entries are one hash lookup on the item's mod id.
 * Fluid pipes use the same class with fluid entries (FluidGhost, step 27): the same four modes apply to the fluid
 * (fluid, fluid + data, fluid tag, mod), compiled into their own lookups and checked with testFluid.
 */
public final class ItemFilter {
    public static final int SIZE = 54;
    /** Item data bigger than this (saved size in bytes) isn't stored, so huge items can't bloat world saves. */
    public static final int MAX_DATA_BYTES = 1024;
    /** Per-entry numbers: which one. */
    public static final int BATCH = 0;
    public static final int KEEP = 1;
    public static final int FILL = 2;
    /** A per-entry number that isn't set: the side's value is used. */
    public static final int UNSET = -1;

    private final ItemStack[] entries = new ItemStack[SIZE];
    private final MatchMode[] modes = new MatchMode[SIZE];
    /** TAG mode only: the item tag (item entries) or the fluid tag (fluid entries). */
    private final TagKey<Item>[] tags;
    private final TagKey<Fluid>[] fluidTags;
    /** True for entries that are fluids (FluidGhost). Set by rebuild. */
    private final boolean[] fluidEntry = new boolean[SIZE];
    /** Per entry: its own Batch / Keep in source / Fill destination to, or UNSET. Indexed [BATCH|KEEP|FILL][slot]. */
    private final int[][] numbers = new int[3][SIZE];
    /** What filter slots show: a copy of the entry, plus FilterMatch (unless ITEM mode) and FilterNumbers (if set). */
    private final ItemStack[] display = new ItemStack[SIZE];
    /** MOD mode only: the mod id, so lookups don't touch the registry. */
    private final String[] modValues = new String[SIZE];

    // Compiled form, rebuilt on every edit.
    private int entryCount;
    private final ReferenceOpenHashSet<Item> items = new ReferenceOpenHashSet<>();
    private final Reference2ObjectOpenHashMap<Item, ObjectArrayList<ItemStack>> dataByItem = new Reference2ObjectOpenHashMap<>();
    private final ObjectArrayList<TagKey<Item>> tagList = new ObjectArrayList<>();
    private final ObjectOpenHashSet<String> mods = new ObjectOpenHashSet<>();
    private final ReferenceOpenHashSet<Fluid> fluids = new ReferenceOpenHashSet<>();
    private final Reference2ObjectOpenHashMap<Fluid, ObjectArrayList<FluidStack>> fluidDataByFluid =
            new Reference2ObjectOpenHashMap<>();
    private final ObjectArrayList<TagKey<Fluid>> fluidTagList = new ObjectArrayList<>();
    private final ObjectOpenHashSet<String> fluidMods = new ObjectOpenHashSet<>();
    private boolean hasOverrides;
    private boolean hasKeepOverride;
    private boolean hasFillOverride;

    @SuppressWarnings("unchecked")
    public ItemFilter() {
        tags = new TagKey[SIZE];
        fluidTags = new TagKey[SIZE];
        clear();
    }

    /** True if at least one entry is set. An empty filter lets everything through. */
    public boolean isActive() {
        return entryCount > 0;
    }

    /**
     * Allow mode (deny = false): only matching items pass. Deny mode: everything except matching items passes.
     * Only call this on an active filter.
     */
    public boolean test(ItemStack stack, boolean deny) {
        return matches(stack) != deny;
    }

    private boolean matches(ItemStack stack) {
        Item item = stack.getItem();
        if (items.contains(item)) {
            return true;
        }
        if (!dataByItem.isEmpty()) {
            ObjectArrayList<ItemStack> withData = dataByItem.get(item);
            if (withData != null) {
                for (int i = 0; i < withData.size(); i++) {
                    if (ItemStack.isSameItemSameTags(withData.get(i), stack)) {
                        return true;
                    }
                }
            }
        }
        for (int i = 0; i < tagList.size(); i++) {
            if (stack.is(tagList.get(i))) {
                return true;
            }
        }
        return !mods.isEmpty() && mods.contains(modId(item));
    }

    /**
     * Fluid pipes: Allow mode passes only matching fluids, Deny mode everything else. Only call on an active filter.
     * No allocation.
     */
    public boolean testFluid(FluidStack stack, boolean deny) {
        return matchesFluid(stack) != deny;
    }

    private boolean matchesFluid(FluidStack stack) {
        Fluid fluid = stack.getFluid();
        if (fluids.contains(fluid)) {
            return true;
        }
        if (!fluidDataByFluid.isEmpty()) {
            ObjectArrayList<FluidStack> withData = fluidDataByFluid.get(fluid);
            if (withData != null) {
                for (int i = 0; i < withData.size(); i++) {
                    if (Compat.sameFluidSameData(withData.get(i), stack)) {
                        return true;
                    }
                }
            }
        }
        for (int i = 0; i < fluidTagList.size(); i++) {
            if (stack.getFluid().is(fluidTagList.get(i))) {
                return true;
            }
        }
        return !fluidMods.isEmpty() && fluidMods.contains(FluidGhost.modId(fluid));
    }

    /** True if any entry has its own Batch, Keep or Fill. Only then is entryFor worth calling. */
    public boolean hasOverrides() {
        return hasOverrides;
    }

    public boolean hasKeepOverride() {
        return hasKeepOverride;
    }

    public boolean hasFillOverride() {
        return hasFillOverride;
    }

    /**
     * The entry whose numbers apply to this item: the most specific matching entry (item + data, then item, then
     * tag, then mod; the lowest slot wins a tie), or -1. That entry's UNSET numbers fall back to the side's.
     * No allocation; only called when hasOverrides() is true.
     */
    public int entryFor(ItemStack stack) {
        Item item = stack.getItem();
        String mod = null;
        int best = -1;
        int bestRank = -1;
        for (int i = 0; i < SIZE; i++) {
            ItemStack entry = entries[i];
            if (entry.isEmpty() || fluidEntry[i]) {
                continue;
            }
            MatchMode mode = modes[i];
            int rank = switch (mode) {
                case DATA -> 3;
                case ITEM -> 2;
                case TAG -> 1;
                case MOD -> 0;
            };
            if (rank <= bestRank) {
                continue;
            }
            boolean match;
            switch (mode) {
                case DATA -> match = ItemStack.isSameItemSameTags(entry, stack);
                case ITEM -> match = entry.is(item);
                case TAG -> match = stack.is(tags[i]);
                default -> {
                    if (mod == null) {
                        mod = modId(item);
                    }
                    match = mod.equals(modValues[i]);
                }
            }
            if (match) {
                best = i;
                bestRank = rank;
                if (rank == 3) {
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Fluid pipes: the entry whose amounts apply to this fluid, by the same rule as entryFor (fluid + data, then fluid,
     * then fluid tag, then mod; lowest slot on a tie), or -1. Only fluid entries count. No allocation.
     */
    public int entryForFluid(FluidStack stack) {
        Fluid fluid = stack.getFluid();
        String mod = null;
        int best = -1;
        int bestRank = -1;
        for (int i = 0; i < SIZE; i++) {
            if (!fluidEntry[i] || entries[i].isEmpty()) {
                continue;
            }
            MatchMode mode = modes[i];
            int rank = switch (mode) {
                case DATA -> 3;
                case ITEM -> 2;
                case TAG -> 1;
                case MOD -> 0;
            };
            if (rank <= bestRank) {
                continue;
            }
            SimpleFluidContent content = ModDataComponents.GHOST_FLUID.get(entries[i]);
            boolean match;
            switch (mode) {
                case DATA -> match = content != null && content.isSameFluidSameComponents(stack);
                case ITEM -> match = content != null && content.getFluid() == fluid;
                case TAG -> match = fluidTags[i] != null && stack.getFluid().is(fluidTags[i]);
                default -> {
                    if (mod == null) {
                        mod = FluidGhost.modId(fluid);
                    }
                    match = mod.equals(modValues[i]);
                }
            }
            if (match) {
                best = i;
                bestRank = rank;
                if (rank == 3) {
                    break;
                }
            }
        }
        return best;
    }

    /** An entry's own number (BATCH, KEEP or FILL), or UNSET. */
    public int number(int slot, int which) {
        return numbers[which][slot];
    }

    // ---------------------------------------------------------------- reading

    /** The display copy for a filter slot (empty if unused). Never hand this to a player as a real item. */
    public ItemStack display(int slot) {
        return slot >= 0 && slot < SIZE ? display[slot] : ItemStack.EMPTY;
    }

    /** True if an ITEM-mode entry already lists this item (or, for a fluid ghost, this fluid). */
    public boolean listsItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        SimpleFluidContent content = FluidGhost.contentOf(stack);
        return content != null ? fluids.contains(content.getFluid()) : items.contains(stack.getItem());
    }

    /** The first empty entry, or -1 if all 54 are used. */
    public int firstEmpty() {
        for (int i = 0; i < SIZE; i++) {
            if (entries[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- editing (server)

    /**
     * Lists a ghost copy of the stack in ITEM mode (empty clears the entry).
     * Returns false and changes nothing if another entry already lists this item in ITEM mode.
     */
    public boolean set(int slot, ItemStack stack, HolderLookup.Provider registries) {
        if (slot < 0 || slot >= SIZE) {
            return false;
        }
        ItemStack ghost = ghostOf(stack, registries);
        if (!ghost.isEmpty() && isDuplicate(slot, ghost, MatchMode.ITEM, null)) {
            return false;
        }
        entries[slot] = ghost;
        modes[slot] = MatchMode.ITEM;
        tags[slot] = null;
        fluidTags[slot] = null;
        for (int[] values : numbers) {
            values[slot] = UNSET;
        }
        rebuild();
        return true;
    }

    /**
     * Sets an entry's own Batch / Keep / Fill (UNSET = use the side's). Returns false and changes nothing if the entry
     * is empty or the value is out of range: batch 0 (Max) .. MAX_BATCH, keep and fill 0 (Off) .. MAX_STOCK.
     */
    public boolean setNumber(int slot, int which, int value) {
        if (slot < 0 || slot >= SIZE || which < BATCH || which > FILL || entries[slot].isEmpty()) {
            return false;
        }
        if (!validNumber(which, value, FluidGhost.isGhost(entries[slot])) || numbers[which][slot] == value) {
            return false;
        }
        numbers[which][slot] = value;
        rebuild();
        return true;
    }

    /** Item entries count items, fluid entries mB (bigger limits). */
    private static boolean validNumber(int which, int value, boolean fluid) {
        PipeKind kind = fluid ? PipeKind.FLUID : PipeKind.ITEM;
        int max = which == BATCH ? kind.maxBatch() : kind.maxStock();
        return value == UNSET || (value >= 0 && value <= max);
    }

    /**
     * Changes how an existing entry matches. Returns false and changes nothing if the entry is empty, the mode
     * isn't possible for it (DATA without stored data, TAG without a tag), or an identical entry already exists.
     */
    public boolean setMatch(int slot, MatchMode mode, @Nullable ResourceLocation tagId) {
        if (slot < 0 || slot >= SIZE || entries[slot].isEmpty()) {
            return false;
        }
        if (mode == MatchMode.DATA && !hasData(entries[slot])) {
            return false;
        }
        if (mode == MatchMode.TAG && tagId == null) {
            return false;
        }
        ResourceLocation newTag = mode == MatchMode.TAG ? tagId : null;
        if (isDuplicate(slot, entries[slot], mode, newTag)) {
            return false;
        }
        boolean fluid = FluidGhost.isGhost(entries[slot]);
        modes[slot] = mode;
        tags[slot] = newTag != null && !fluid ? TagKey.create(Registries.ITEM, newTag) : null;
        fluidTags[slot] = newTag != null && fluid ? TagKey.create(Registries.FLUID, newTag) : null;
        rebuild();
        return true;
    }

    /** The tag an entry matches (item or fluid tag), or null. */
    @Nullable
    private ResourceLocation tagId(int slot) {
        if (tags[slot] != null) {
            return tags[slot].location();
        }
        return fluidTags[slot] != null ? fluidTags[slot].location() : null;
    }

    /** The mod id of an entry's item, or of its fluid for a fluid ghost. */
    public static String modOf(ItemStack entry) {
        SimpleFluidContent content = FluidGhost.contentOf(entry);
        return content != null ? FluidGhost.modId(content.getFluid()) : modId(entry.getItem());
    }

    public void clear() {
        Arrays.fill(entries, ItemStack.EMPTY);
        Arrays.fill(modes, MatchMode.ITEM);
        Arrays.fill(tags, null);
        Arrays.fill(fluidTags, null);
        for (int[] values : numbers) {
            Arrays.fill(values, UNSET);
        }
        rebuild();
    }

    /** True if another entry (not at exceptSlot) would match exactly the same things. */
    private boolean isDuplicate(int exceptSlot, ItemStack ghost, MatchMode mode, @Nullable ResourceLocation tag) {
        boolean fluid = FluidGhost.isGhost(ghost);
        for (int i = 0; i < SIZE; i++) {
            if (i == exceptSlot || entries[i].isEmpty() || modes[i] != mode || FluidGhost.isGhost(entries[i]) != fluid) {
                continue;
            }
            boolean same = switch (mode) {
                case ITEM -> fluid ? sameFluid(entries[i], ghost) : entries[i].is(ghost.getItem());
                case DATA -> ItemStack.isSameItemSameTags(entries[i], ghost);
                case TAG -> tag != null && tag.equals(tagId(i));
                case MOD -> modOf(entries[i]).equals(modOf(ghost));
            };
            if (same) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ghost form of a stack: count 1, keeping its data unless the data is too big to store
     * (then only the item is kept, and "item + data" matching isn't available for it).
     */
    public static ItemStack ghostOf(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (FluidGhost.isGhost(stack)) {
            // Rebuilt from its fluid: drops display markers and anything else, re-checks the data size.
            return FluidGhost.of(FluidGhost.fluidOf(stack), registries);
        }
        ItemStack ghost = stack.copyWithCount(1);
        ModDataComponents.FILTER_MATCH.remove(ghost);
        ModDataComponents.FILTER_NUMBERS.remove(ghost);
        if (hasData(ghost) && Compat.save(ghost).sizeInBytes() > MAX_DATA_BYTES) {
            return new ItemStack(stack.getItem());
        }
        return ghost;
    }

    /** True if the stack carries data beyond its item's defaults (needed for "item + data" matching). */
    public static boolean hasData(ItemStack stack) {
        if (FluidGhost.isGhost(stack)) {
            return FluidGhost.hasData(stack);
        }
        return stack.hasTag();
    }

    private static boolean sameFluid(ItemStack a, ItemStack b) {
        SimpleFluidContent first = FluidGhost.contentOf(a);
        SimpleFluidContent second = FluidGhost.contentOf(b);
        return first != null && second != null && first.getFluid() == second.getFluid();
    }

    public static String modId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getNamespace();
    }

    private void rebuild() {
        entryCount = 0;
        items.clear();
        dataByItem.clear();
        tagList.clear();
        mods.clear();
        fluids.clear();
        fluidDataByFluid.clear();
        fluidTagList.clear();
        fluidMods.clear();
        hasOverrides = false;
        hasKeepOverride = false;
        hasFillOverride = false;
        Arrays.fill(modValues, null);
        for (int i = 0; i < SIZE; i++) {
            ItemStack entry = entries[i];
            SimpleFluidContent content = FluidGhost.contentOf(entry);
            fluidEntry[i] = content != null;
            if (entry.isEmpty() || (fluidEntry[i] && content.isEmpty())) {
                display[i] = ItemStack.EMPTY;
                continue;
            }
            entryCount++;
            String value = "";
            if (fluidEntry[i]) {
                Fluid fluid = content.getFluid();
                switch (modes[i]) {
                    case ITEM -> fluids.add(fluid);
                    case DATA -> fluidDataByFluid.computeIfAbsent(fluid, k -> new ObjectArrayList<>()).add(content.copy());
                    case TAG -> {
                        fluidTagList.add(fluidTags[i]);
                        value = fluidTags[i].location().toString();
                    }
                    case MOD -> {
                        value = FluidGhost.modId(fluid);
                        fluidMods.add(value);
                        modValues[i] = value;
                    }
                }
            } else switch (modes[i]) {
                case ITEM -> items.add(entry.getItem());
                case DATA -> dataByItem.computeIfAbsent(entry.getItem(), k -> new ObjectArrayList<>()).add(entry);
                case TAG -> {
                    tagList.add(tags[i]);
                    value = tags[i].location().toString();
                }
                case MOD -> {
                    value = modId(entry.getItem());
                    mods.add(value);
                    modValues[i] = value;
                }
            }
            ItemStack shown = entry.copy();
            if (modes[i] != MatchMode.ITEM) {
                ModDataComponents.FILTER_MATCH.set(shown, new FilterMatch((byte) modes[i].id(), value));
            }
            int batch = numbers[BATCH][i];
            int keep = numbers[KEEP][i];
            int fill = numbers[FILL][i];
            if (batch != UNSET || keep != UNSET || fill != UNSET) {
                hasOverrides = true;
                hasKeepOverride |= keep != UNSET;
                hasFillOverride |= fill != UNSET;
                ModDataComponents.FILTER_NUMBERS.set(shown, new FilterNumbers(batch, keep, fill));
            }
            display[i] = shown;
        }
    }

    // ---------------------------------------------------------------- save / load

    /**
     * Writes the entries as a list of {Slot, Item, Mode, Tag, Batch, Keep, Fill} (numbers only when set).
     * Items from removed mods are skipped on load.
     */
    public ListTag save(HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int i = 0; i < SIZE; i++) {
            if (entries[i].isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) i);
            entry.put("Item", Compat.save(entries[i]));
            entry.putByte("Mode", (byte) modes[i].id());
            ResourceLocation tag = tagId(i);
            if (tag != null) {
                entry.putString("Tag", tag.toString());
            }
            putNumber(entry, "Batch", numbers[BATCH][i]);
            putNumber(entry, "Keep", numbers[KEEP][i]);
            putNumber(entry, "Fill", numbers[FILL][i]);
            list.add(entry);
        }
        return list;
    }

    private static void putNumber(CompoundTag entry, String key, int value) {
        if (value != UNSET) {
            entry.putInt(key, value);
        }
    }

    /** A saved number, or UNSET if it's missing or out of range (e.g. edited by hand). */
    private static int readNumber(CompoundTag entry, String key, int which, boolean fluid) {
        if (!entry.contains(key, Tag.TAG_INT)) {
            return UNSET;
        }
        int value = entry.getInt(key);
        return validNumber(which, value, fluid) ? value : UNSET;
    }

    public void load(ListTag list, HolderLookup.Provider registries) {
        Arrays.fill(entries, ItemStack.EMPTY);
        Arrays.fill(modes, MatchMode.ITEM);
        Arrays.fill(tags, null);
        Arrays.fill(fluidTags, null);
        for (int[] values : numbers) {
            Arrays.fill(values, UNSET);
        }
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getByte("Slot");
            if (slot < 0 || slot >= SIZE || !entry.contains("Item", Tag.TAG_COMPOUND)) {
                continue;
            }
            ItemStack ghost = ghostOf(Compat.parse(entry.getCompound("Item")), registries);
            if (ghost.isEmpty()) {
                continue;
            }
            entries[slot] = ghost;
            MatchMode mode = MatchMode.byId(entry.getByte("Mode"));
            ResourceLocation tag = null;
            if (mode == MatchMode.TAG) {
                tag = ResourceLocation.tryParse(entry.getString("Tag"));
            }
            if (mode == null || (mode == MatchMode.TAG && tag == null) || (mode == MatchMode.DATA && !hasData(ghost))) {
                mode = MatchMode.ITEM; // anything unreadable falls back to plain item matching
                tag = null;
            }
            boolean fluid = FluidGhost.isGhost(ghost);
            modes[slot] = mode;
            tags[slot] = tag != null && !fluid ? TagKey.create(Registries.ITEM, tag) : null;
            fluidTags[slot] = tag != null && fluid ? TagKey.create(Registries.FLUID, tag) : null;
            numbers[BATCH][slot] = readNumber(entry, "Batch", BATCH, fluid);
            numbers[KEEP][slot] = readNumber(entry, "Keep", KEEP, fluid);
            numbers[FILL][slot] = readNumber(entry, "Fill", FILL, fluid);
        }
        rebuild();
    }
}
