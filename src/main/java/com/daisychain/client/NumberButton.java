package com.daisychain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A number setting shown as a button (zerobit's GUI convention for numbers that aren't sliders):
 * left-click = up a small step, right-click = down; Shift = big step; Alt/Option = jump to max (left) or min (right).
 * The mouse wheel also works (Shift: 10 per notch). Ctrl+click (Cmd+click on Mac) asks to type a number instead.
 * <p>
 * Step 46: {@link #flat} turns it into the design spec's number field (13 tall, 1 px border, slot fill, the value
 * right-aligned 5 px from the right edge; warning = red border, red value and a 2x7 bar 4 px to its left). Without it,
 * it keeps the vanilla button look (still used by the filter entry page).
 */
final class NumberButton extends AbstractButton {
    /** direction: +1 or -1. big: Shift held. jump: Alt/Option held. */
    interface Change {
        void apply(int direction, boolean big, boolean jump);
    }

    private final Change change;
    private final Runnable onEdit;
    /** Step 46: the flat field look (null = vanilla button). */
    private Supplier<Component> fieldValue;
    private IntSupplier fieldColor;
    private BooleanSupplier fieldWarn;
    /** While the type-a-number box sits on top: draw only the frame. */
    private boolean editing;

    NumberButton(int x, int y, int width, int height, Change change, Runnable onEdit) {
        super(x, y, width, height, Component.empty());
        this.change = change;
        this.onEdit = onEdit;
    }

    /** Draw as a flat number field: value text (already in the GUI font), its color, and whether to warn. */
    NumberButton flat(Supplier<Component> value, IntSupplier color, BooleanSupplier warn) {
        this.fieldValue = value;
        this.fieldColor = color;
        this.fieldWarn = warn;
        return this;
    }

    void setEditing(boolean editing) {
        this.editing = editing;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (fieldValue == null) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            return;
        }
        int x = getX();
        int y = getY();
        boolean warn = fieldWarn.getAsBoolean();
        int border = warn ? FlowGui.WARN : active && !editing && isHovered() ? FlowGui.FIELD_HOVER : FlowGui.FIELD_BORDER;
        FlowGui.round(graphics, x, y, x + width, y + height, border, 1);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, FlowGui.SLOT);
        if (warn) {
            graphics.fill(x - 6, y + 3, x - 4, y + 10, FlowGui.WARN);
        }
        if (!editing) {
            Font font = Minecraft.getInstance().font;
            Component value = fieldValue.get();
            FlowGui.text(graphics, font, value, x + width - 5 - FlowGui.inkWidth(font, value), y + 3,
                    warn ? FlowGui.WARN_TEXT : fieldColor.getAsInt());
        }
    }

    @Override
    public void onPress() {
        change.apply(1, Screen.hasShiftDown(), Screen.hasAltDown());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && Screen.hasControlDown() && active && visible && isMouseOver(mouseX, mouseY)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onEdit.run();
            return true;
        }
        if (button == 1 && active && visible && isMouseOver(mouseX, mouseY)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            change.apply(-1, Screen.hasShiftDown(), Screen.hasAltDown());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    /**
     * Mouse wheel: 1 per notch, 10 per notch with Shift (zerobit, step 23c). On macOS, Shift turns a vertical scroll
     * into a horizontal one, so a horizontal scroll counts too.
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        double scrollX = 0; // 1.20.1 only reports vertical scrolling
        double amount = scrollY != 0 ? scrollY : scrollX;
        if (active && visible && amount != 0 && isMouseOver(mouseX, mouseY)) {
            int direction = amount > 0 ? 1 : -1;
            change.apply(Screen.hasShiftDown() ? direction * 10 : direction, false, false);
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
