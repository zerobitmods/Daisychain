package com.daisychain.client;

import com.daisychain.compat.Chemicals;
import com.daisychain.filter.ChemicalId;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;

import javax.annotation.Nullable;

/**
 * Step F102 (port of 178): draws a Mekanism chemical as a 16x16 square of its own texture, tinted with its colour (the way JEI and
 * Mekanism's gauges show it; fluids are drawn the same way by FluidRender). A chemical that doesn't exist here (no
 * Mekanism) is drawn as a plain grey square, so the entry is still visible and can be removed.
 */
final class ChemicalRender {
    private static final int UNKNOWN = 0xFF55585F;

    private ChemicalRender() {
    }

    static void drawChemical(GuiGraphics graphics, @Nullable ChemicalId id, int x, int y) {
        if (id == null) {
            return;
        }
        Chemicals.Look look = Chemicals.look(id);
        if (look == null) {
            graphics.fill(x + 2, y + 2, x + 14, y + 14, UNKNOWN);
            return;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(look.texture());
        int tint = look.tint();
        float alpha = ((tint >>> 24) & 0xFF) / 255.0F;
        RenderSystem.enableBlend();
        graphics.blit(x, y, 0, 16, 16, sprite, ((tint >> 16) & 0xFF) / 255.0F, ((tint >> 8) & 0xFF) / 255.0F,
                (tint & 0xFF) / 255.0F, alpha <= 0.0F ? 1.0F : alpha);
        RenderSystem.disableBlend();
    }
}
