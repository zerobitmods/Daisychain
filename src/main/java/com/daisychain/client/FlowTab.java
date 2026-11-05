package com.daisychain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;

/**
 * Step 44: a tab on the left edge of a side screen (design spec): the selected one is 23 wide in the panel color and
 * joins the panel; the other is 20 wide, darker. The icon is a face (this side), an unfolded cube (all sides), or
 * (step 49, universal pipes, 20 tall) a resource: items, fluids, energy. A resource that's switched off on this side
 * gets a dim icon with a red slash.
 */
final class FlowTab extends AbstractButton {
    private final String[] icon;
    private final boolean selected;
    private final Runnable action;
    @Nullable
    private final BooleanSupplier off;

    FlowTab(int panelLeft, int y, String[] icon, boolean selected, Component name, Runnable action) {
        this(panelLeft, y, 24, icon, selected, name, action, null);
    }

    FlowTab(int panelLeft, int y, int height, String[] icon, boolean selected, Component name, Runnable action,
            @Nullable BooleanSupplier off) {
        super(panelLeft - (selected ? 22 : 20), y, selected ? 23 : 20, height, name);
        this.icon = icon;
        this.selected = selected;
        this.action = action;
        this.off = off;
    }

    @Override
    public void onPress() {
        if (!selected) {
            action.run();
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        // Step 47c: left corners rounded (the right side joins the panel).
        FlowGui.roundLeft(graphics, x, y, x + width, y + height, FlowGui.EDGE, 2);
        int fill = selected ? FlowGui.PANEL : isHovered() ? FlowGui.KEY : FlowGui.TAB_OFF;
        FlowGui.roundLeft(graphics, x + 1, y + 1, x + width, y + height - 1, fill, 1);
        int iw = icon[0].length();
        // Centered in the space between the tab's left edge and the panel's edge line (21 px selected, 19 otherwise);
        // an even-width icon leans right by half a pixel (step 49b: they looked too far left).
        int inside = selected ? 21 : 19;
        int ix = x + 1 + (inside - iw + 1) / 2;
        int iy = y + (height - icon.length + 1) / 2;
        boolean isOff = off != null && off.getAsBoolean();
        FlowGui.icon(graphics, ix, iy, icon, selected && !isOff ? FlowGui.TEXT : FlowGui.FAINT);
        if (isOff) {
            // A red slash from the bottom-left to the top-right corner of the icon.
            for (int i = 0; i < 9; i++) {
                graphics.fill(ix - 1 + i, iy + 7 - i, ix + i, iy + 8 - i, FlowGui.WARN);
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
