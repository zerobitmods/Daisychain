package com.daisychain.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * What a Settings Copier holds: one side's settings (data component daisychain:copied_settings).
 * insert = insert side settings, otherwise pull side settings. pipe = the block id it was copied from, so settings
 * only paste onto the same pipe type. settings = the values as written by PipeDataBlockEntity.copySide.
 * The tag is never changed after creation (data components must stay unchanged); readers only call getters.
 * The server checks every value again when pasting, because a creative player can put anything on an item.
 */
public record CopiedSettings(boolean insert, String pipe, CompoundTag settings) {
    public static final Codec<CopiedSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("insert").forGetter(CopiedSettings::insert),
            Codec.STRING.fieldOf("pipe").forGetter(CopiedSettings::pipe),
            CompoundTag.CODEC.fieldOf("settings").forGetter(CopiedSettings::settings)
    ).apply(instance, CopiedSettings::new));


    /** Sub-tags per resource (step 31); copies made before that hold one resource's values at the top. */
    private static final String[] RESOURCE_KEYS = {"Items", "Fluids", "Energy"};

    /** Number of filter entries held, all resources together (for the tooltip and messages). */
    public int filterEntries() {
        int count = settings.getList("Filter", Tag.TAG_COMPOUND).size();
        for (String key : RESOURCE_KEYS) {
            count += settings.getCompound(key).getList("Filter", Tag.TAG_COMPOUND).size();
        }
        return count;
    }

    /** Filter mode of the first held filter with entries (for the tooltip): true = Deny. */
    public boolean filterDenies() {
        for (String key : RESOURCE_KEYS) {
            CompoundTag part = settings.getCompound(key);
            if (!part.getList("Filter", Tag.TAG_COMPOUND).isEmpty()) {
                return part.getBoolean("Deny");
            }
        }
        return settings.getBoolean("Deny");
    }

    /** Upgrade level held by pull side settings (0 = none). */
    public int upgradeLevel() {
        return insert ? 0 : settings.getByte("Upgrade");
    }
}
