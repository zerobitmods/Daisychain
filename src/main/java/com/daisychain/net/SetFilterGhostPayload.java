package com.daisychain.net;

import com.daisychain.Daisychain;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Client -> server: "list this in empty filter slot N of my open side settings screen" (step 28, dragged from JEI).
 * The stack is only a description: the server never gives anything to anyone, it lists a ghost copy using exactly the
 * same rules as clicking the slot (item pipes: items only; fluid pipes: the fluid a fluid ghost or container holds;
 * empty slots only; duplicates refused; big data dropped).
 *
 * @param containerId the open menu (must still be the player's current menu)
 * @param slot        filter slot 0-53
 * @param stack       the dragged item, or a fluid ghost for a dragged fluid
 */
public record SetFilterGhostPayload(int containerId, byte slot, ItemStack stack) implements CustomPacketPayload {
    public static final Type<SetFilterGhostPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "set_filter_ghost"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetFilterGhostPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetFilterGhostPayload::containerId,
            ByteBufCodecs.BYTE, SetFilterGhostPayload::slot,
            ItemStack.OPTIONAL_STREAM_CODEC, SetFilterGhostPayload::stack,
            SetFilterGhostPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
