package com.daisychain.net;

import net.minecraft.network.FriendlyByteBuf;

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
public record SetFilterGhostPayload(int containerId, byte slot, ItemStack stack) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.containerId);
        buf.writeByte(this.slot);
        buf.writeItem(this.stack);
    }

    public static SetFilterGhostPayload decode(FriendlyByteBuf buf) {
        return new SetFilterGhostPayload(buf.readVarInt(), buf.readByte(), buf.readItem());
    }
}
