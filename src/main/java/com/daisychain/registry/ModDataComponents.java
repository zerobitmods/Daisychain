package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.filter.FilterMatch;
import com.daisychain.filter.FilterNumbers;
import com.daisychain.filter.SimpleFluidContent;
import com.daisychain.item.CopiedSettings;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Daisychain's extra item data. Minecraft 1.20.1 has no data components, so each entry is stored in the item's NBT
 * under "daisychain:&lt;name&gt;", encoded with the same codec the 1.21.1 version uses.
 */
public final class ModDataComponents {
    /** How a filter entry matches; only on the display copies in filter slots. */
    public static final Entry<FilterMatch> FILTER_MATCH = new Entry<>("filter_match", FilterMatch.CODEC);
    /** A filter entry's own Batch / Keep / Fill; only on the display copies in filter slots, and only when set. */
    public static final Entry<FilterNumbers> FILTER_NUMBERS = new Entry<>("filter_numbers", FilterNumbers.CODEC);
    /** One side's settings held by a Settings Copier. */
    public static final Entry<CopiedSettings> COPIED_SETTINGS = new Entry<>("copied_settings", CopiedSettings.CODEC);
    /** The fluid a fluid filter entry stands for; only on daisychain:fluid ghosts in filters. */
    public static final Entry<SimpleFluidContent> GHOST_FLUID = new Entry<>("ghost_fluid", SimpleFluidContent.CODEC);

    private ModDataComponents() {
    }

    /** One kind of extra item data, stored in the stack's NBT. */
    public static final class Entry<T> {
        private final String key;
        private final Codec<T> codec;

        Entry(String name, Codec<T> codec) {
            this.key = Daisychain.MOD_ID + ":" + name;
            this.codec = codec;
        }

        public boolean has(ItemStack stack) {
            CompoundTag tag = stack.getTag();
            return tag != null && tag.contains(key);
        }

        /** The stored value, or null if the stack has none (or it can't be read). */
        @Nullable
        public T get(ItemStack stack) {
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return null;
            }
            Tag raw = tag.get(key);
            if (raw == null) {
                return null;
            }
            return codec.parse(NbtOps.INSTANCE, raw).result().orElse(null);
        }

        public void set(ItemStack stack, @Nullable T value) {
            if (value == null) {
                remove(stack);
                return;
            }
            codec.encodeStart(NbtOps.INSTANCE, value).result()
                    .ifPresent(encoded -> stack.getOrCreateTag().put(key, encoded));
        }

        public void remove(ItemStack stack) {
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return;
            }
            tag.remove(key);
            if (tag.isEmpty()) {
                stack.setTag(null);
            }
        }
    }
}
