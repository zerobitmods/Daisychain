package com.daisychain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import com.daisychain.filter.SimpleFluidContent;

import javax.annotation.Nullable;

/** Draws a fluid as a 16x16 square of its own still texture (for fluid filter entries, step 27). */
final class FluidRender {
    private FluidRender() {
    }

    static void drawFluid(GuiGraphics graphics, @Nullable SimpleFluidContent fluid, int x, int y) {
        if (fluid == null || fluid.isEmpty()) {
            return;
        }
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid.getFluid());
        ResourceLocation texture = extensions.getStillTexture();
        if (texture == null) {
            return;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
        int tint = extensions.getTintColor();
        float alpha = ((tint >>> 24) & 0xFF) / 255.0F;
        RenderSystem.enableBlend();
        graphics.blit(x, y, 0, 16, 16, sprite, ((tint >> 16) & 0xFF) / 255.0F, ((tint >> 8) & 0xFF) / 255.0F,
                (tint & 0xFF) / 255.0F, alpha <= 0.0F ? 1.0F : alpha);
        RenderSystem.disableBlend();
    }
}
