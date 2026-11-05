package com.daisychain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Step 44: a flat key from the design spec. An 18x18 icon key on the slot grid (left 1 px column in the panel color, so
 * keys side by side read as one bar), or a text key. "On" keys are filled (white, or the side color for page keys).
 * Left-click runs onLeft, right-click onRight (if any). An inactive key draws its icon or text in the faint color.
 * Step 47b: only slot-grid keys ("segmented": icon keys and page keys) leave their left column as a gap; text keys use
 * their whole width and are placed with real 1 px gaps, so their left edges line up with labels at x 7. Text is centered
 * in the visible part of the key. Step 47c: corners rounded 1 px; "on" keys use the soft colors. Client only.
 */
final class FlowKey extends AbstractButton {
    private final Supplier<String[]> icon;
    private final IntSupplier iconColor;
    private final Supplier<Component> label;
    private final BooleanSupplier on;
    /** Fill color when on (WHITE for toggles, the side color for page keys). */
    private final int onColor;
    private final int onInk;
    private BooleanSupplier dot;
    private int dotColor;
    private final Runnable onLeft;
    private final Runnable onRight;
    /** Slot-grid key: the left column is drawn in the panel color (spec 4, icon button). */
    private boolean segmented;

    private FlowKey(int x, int y, int w, int h, Supplier<String[]> icon, IntSupplier iconColor, Supplier<Component> label,
                    BooleanSupplier on, int onColor, int onInk, BooleanSupplier dot, int dotColor, Runnable onLeft,
                    Runnable onRight) {
        super(x, y, w, h, Component.empty());
        this.icon = icon;
        this.iconColor = iconColor;
        this.label = label;
        this.on = on;
        this.onColor = onColor;
        this.onInk = onInk;
        this.dot = dot;
        this.dotColor = dotColor;
        this.onLeft = onLeft;
        this.onRight = onRight;
    }

    /** 18x18 icon key. */
    static FlowKey icon(int x, int y, Supplier<String[]> icon, IntSupplier color, BooleanSupplier on,
                        BooleanSupplier dot, int dotColor, Runnable onLeft, Runnable onRight) {
        return new FlowKey(x, y, 18, 18, icon, color, null, on, FlowGui.ON, FlowGui.ON_INK, dot, dotColor, onLeft,
                onRight).segmented();
    }

    /** Step 47g: a 2x2 dot in the top-right corner while dot is true (e.g. a text key pointing at settings in use). */
    FlowKey withDot(BooleanSupplier dot, int dotColor) {
        this.dot = dot;
        this.dotColor = dotColor;
        return this;
    }

    /** Draws the panel-colored left column, like the icon keys on the slot grid (used for the page keys). */
    FlowKey segmented() {
        this.segmented = true;
        return this;
    }

    /**
     * Step 47: a small icon key of any size (13x13 back, page arrows and reset keys in the filter entry drawer). Never
     * "on"; an inactive one draws its icon faint.
     */
    static FlowKey small(int x, int y, int w, int h, Supplier<String[]> icon, IntSupplier color, Runnable onLeft) {
        return new FlowKey(x, y, w, h, icon, color, null, () -> false, FlowGui.ON, FlowGui.ON_INK, () -> false, 0,
                onLeft, null);
    }

    /** Text key (e.g. the page numbers), filled with onColor when on. */
    static FlowKey text(int x, int y, int w, int h, Supplier<Component> label, BooleanSupplier on, int onColor,
                        int onInk, Runnable onLeft) {
        return new FlowKey(x, y, w, h, null, () -> FlowGui.TEXT, label, on, onColor, onInk, () -> false, 0, onLeft,
                null);
    }

    @Override
    public void onPress() {
        onLeft.run();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && onRight != null && active && visible && isMouseOver(mouseX, mouseY)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onRight.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        boolean isOn = active && on.getAsBoolean();
        int fill = isOn ? onColor : active && isHovered() ? FlowGui.KEY_HOVER : FlowGui.KEY;
        int left = segmented ? 1 : 0; // slot-grid keys leave their first column as a gap (segmented-bar look)
        FlowGui.round(graphics, x + left, y, x + width, y + height, fill, 1);
        if (icon != null) {
            String[] rows = icon.get();
            int iw = rows.length == 0 ? 0 : rows[0].length();
            graphics.pose().pushPose();
            FlowGui.icon(graphics, x + (width - iw + 1) / 2, y + (height - rows.length + 1) / 2, rows,
                    !active ? FlowGui.FAINT : isOn ? onInk : iconColor.getAsInt());
            graphics.pose().popPose();
        } else if (label != null) {
            Minecraft minecraft = Minecraft.getInstance();
            Component text = label.get();
            // Spec: page numbers sit in 18 px keys at cap-top + 5, moved down half a pixel to center exactly.
            graphics.pose().pushPose();
            graphics.pose().translate(0, height % 2 == 0 ? 0.5F : 0.0F, 0);
            FlowGui.centered(graphics, minecraft.font, text, x + left, width - left, y + (height - 7) / 2,
                    !active ? FlowGui.FAINT : isOn ? onInk : FlowGui.TEXT);
            graphics.pose().popPose();
        }
        if (dot.getAsBoolean()) {
            graphics.fill(x + width - 4, y + 2, x + width - 2, y + 4, dotColor);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
