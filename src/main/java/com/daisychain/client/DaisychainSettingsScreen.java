package com.daisychain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

import javax.annotation.Nullable;

/**
 * Step 147: what the mod list's Config button opens. Pipe colours (our own screen, with a preview) or all the
 * other settings (NeoForge's ConfigurationScreen, which is final, so it can't get a button of its own).
 */
public final class DaisychainSettingsScreen extends Screen {
    private static final int W = 160;
    private static final int H = 86;
    private final ModContainer container;
    @Nullable
    private final Screen parent;
    private int left;
    private int top;

    public DaisychainSettingsScreen(ModContainer container, @Nullable Screen parent) {
        super(Component.translatable("daisychain.configuration.title"));
        this.container = container;
        this.parent = parent;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        addRenderableWidget(DaisyKey.text(left + 8, top + 22, W - 16, 16,
                () -> Component.translatable("screen.daisychain.settings.colours"), () -> false, DaisyGui.ON,
                DaisyGui.ON_INK, () -> minecraft.setScreen(new PipeColourScreen(this))));
        addRenderableWidget(DaisyKey.text(left + 8, top + 42, W - 16, 16,
                () -> Component.translatable("screen.daisychain.settings.other"), () -> false, DaisyGui.ON,
                DaisyGui.ON_INK, () -> minecraft.setScreen(new ConfigurationScreen(container, this))));
        addRenderableWidget(DaisyKey.text(left + W - 8 - 56, top + H - 22, 56, 14,
                () -> Component.translatable("gui.done"), () -> false, DaisyGui.ON, DaisyGui.ON_INK, this::onClose));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        DaisyGui.panel(graphics, left, top, W, H);
        DaisyGui.text(graphics, font, DaisyGui.styled(title), left + 8, top + 8, DaisyGui.TEXT);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
