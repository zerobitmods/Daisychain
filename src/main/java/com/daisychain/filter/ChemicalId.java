package com.daisychain.filter;

import com.daisychain.compat.Chemicals;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Step F102 (port of 178): which chemical a chemical filter entry stands for. Mekanism 10.4 (1.20.1) keeps its four
 * chemical types in four registries, so a name alone isn't enough: type is Chemicals' type order (0 gas, 1 infusion,
 * 2 pigment, 3 slurry) and id the name in that type's registry. Plain data, no Mekanism type, so it saves and loads
 * without Mekanism. (On 1.21.1 the types are merged and the entry holds only the name.)
 */
public record ChemicalId(int type, ResourceLocation id) {
    public static final Codec<ChemicalId> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, Chemicals.TYPES - 1).fieldOf("type").forGetter(ChemicalId::type),
            ResourceLocation.CODEC.fieldOf("id").forGetter(ChemicalId::id)
    ).apply(instance, ChemicalId::new));

    /** True if type is one of the four. */
    public boolean validType() {
        return type >= 0 && type < Chemicals.TYPES;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
