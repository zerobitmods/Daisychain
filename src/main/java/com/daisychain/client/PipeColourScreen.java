package com.daisychain.client;

import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.SideState;
import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.registry.ModBlocks;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import javax.annotation.Nullable;

/**
 * Step 147: pick the body and line colour of item, fluid and universal pipes, with a live preview.
 * Only on this player's screen (client config). Opened from the mod list's Config button (DaisychainSettingsScreen).
 * Colours are kept here until Done; Cancel / Esc leaves the settings as they were.
 */
public final class PipeColourScreen extends Screen {
    private static final int W = 284;
    private static final int H = 196;
    private static final int VIEW_X = 8;
    private static final int VIEW_Y = 40;
    private static final int VIEW_SIZE = 120;
    private static final int COL_X = 136;
    private static final int SWATCH = 14;
    private static final int SWATCH_STEP = 16;
    private static final int PER_ROW = 8;
    private static final int BODY_Y = 40;
    private static final int LINE_Y = 106;
    /** Body presets (surface colours); -1 = this pipe's default. */
    private static final int[] BODY_PRESETS = {-1, 0x303236, 0x3C4148, 0x464E5A, 0x465268, 0x4E6076, 0x283454,
            0x543E58, 0x602C2C, 0x844E30, 0x7A603A, 0x54583A, 0x2C4834, 0x3E5C5C, 0x5C564E, 0x969AA0};
    /** Line presets; -1 = this pipe's default. */
    private static final int[] LINE_PRESETS = {-1, 0xE28448, 0x80D696, 0xECE0BE, 0xD65C54, 0xB0D65C, 0xF0D250,
            0xE6E8EC, 0x96BEF0, 0x5AD2E6, 0xF08CB4, 0xAA78E6};
    private static final String[] TAB_KEYS = {"item", "fluid", "universal"};

    @Nullable
    private final Screen parent;
    /** Picked colours per slot, -1 = default. */
    private final int[] body = new int[3];
    private final int[] line = new int[3];
    private int slot;
    private boolean capped;
    private int left;
    private int top;
    private EditBox bodyHex;
    private EditBox lineHex;
    private boolean syncing;
    private float yaw = -35.0F;
    private float pitch = 28.0F;
    private boolean dragging;

    public PipeColourScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.daisychain.colours.title"));
        this.parent = parent;
        for (int i = 0; i < 3; i++) {
            body[i] = DaisychainClientConfig.pipeColour(i, false);
            line[i] = DaisychainClientConfig.pipeColour(i, true);
        }
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int[] tabX = {8, 50, 92};
        int[] tabW = {40, 40, 58};
        for (int i = 0; i < 3; i++) {
            int index = i;
            addRenderableWidget(DaisyKey.text(left + tabX[i], top + 22, tabW[i], 14,
                    () -> Component.translatable("screen.daisychain.colours.tab." + TAB_KEYS[index]), () -> slot == index,
                    DaisyGui.ON, DaisyGui.ON_INK, () -> select(index)));
        }
        bodyHex = hexBox(top + BODY_Y + 46, false);
        lineHex = hexBox(top + LINE_Y + 46, true);
        addRenderableWidget(DaisyKey.text(left + COL_X + 68, top + BODY_Y + 44, 60, 14,
                () -> Component.translatable("screen.daisychain.colours.default"), () -> body[slot] < 0, DaisyGui.ON,
                DaisyGui.ON_INK, () -> pick(false, -1)));
        addRenderableWidget(DaisyKey.text(left + COL_X + 68, top + LINE_Y + 44, 60, 14,
                () -> Component.translatable("screen.daisychain.colours.default"), () -> line[slot] < 0, DaisyGui.ON,
                DaisyGui.ON_INK, () -> pick(true, -1)));
        addRenderableWidget(DaisyKey.text(left + W - 8 - 116, top + H - 20, 56, 14,
                () -> Component.translatable("gui.cancel"), () -> false, DaisyGui.ON, DaisyGui.ON_INK, this::onClose));
        addRenderableWidget(DaisyKey.text(left + W - 8 - 56, top + H - 20, 56, 14,
                () -> Component.translatable("gui.done"), () -> false, DaisyGui.ON, DaisyGui.ON_INK, this::save));
        syncBoxes();
    }

    private EditBox hexBox(int y, boolean isLine) {
        EditBox box = addRenderableWidget(new EditBox(font, left + COL_X + 4, y, 56, 9, Component.empty()));
        box.setBordered(false);
        box.setMaxLength(7);
        box.setResponder(text -> {
            if (syncing) {
                return;
            }
            int rgb = DaisychainClientConfig.parseColour(text);
            if (rgb >= 0) {
                set(isLine, rgb);
            }
        });
        return box;
    }

    private void select(int index) {
        slot = index;
        capped = false;
        syncBoxes();
    }

    /** A swatch or Default: sets the colour and shows it in the hex box. */
    private void pick(boolean isLine, int rgb) {
        set(isLine, rgb);
        syncBoxes();
    }

    private void set(boolean isLine, int rgb) {
        int shown = rgb < 0 ? -1 : PipeTints.clamp(slot, isLine, rgb);
        capped = rgb >= 0 && shown != rgb;
        (isLine ? line : body)[slot] = shown;
    }

    private void syncBoxes() {
        syncing = true;
        bodyHex.setValue(hex(shownColour(false)));
        lineHex.setValue(hex(shownColour(true)));
        syncing = false;
    }

    private int shownColour(boolean isLine) {
        int rgb = (isLine ? line : body)[slot];
        return rgb >= 0 ? rgb : isLine ? PipeTints.DEFAULT_LINE[slot] : PipeTints.DEFAULT_SURFACE[slot];
    }

    private static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    private void save() {
        for (int i = 0; i < 3; i++) {
            if (body[i] != DaisychainClientConfig.pipeColour(i, false)) {
                DaisychainClientConfig.setPipeColour(i, false, body[i]);
            }
            if (line[i] != DaisychainClientConfig.pipeColour(i, true)) {
                DaisychainClientConfig.setPipeColour(i, true, line[i]);
            }
        }
        if (PipeTints.refresh() && minecraft != null && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged(); // the world's pipes in the new colours
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        DaisyGui.panel(graphics, left, top, W, H);
        DaisyGui.text(graphics, font, DaisyGui.styled(title), left + 8, top + 8, DaisyGui.TEXT);
        DaisyGui.round(graphics, left + VIEW_X, top + VIEW_Y, left + VIEW_X + VIEW_SIZE, top + VIEW_Y + VIEW_SIZE,
                DaisyGui.SLOT_EDGE, 1);
        graphics.fill(left + VIEW_X + 1, top + VIEW_Y + 1, left + VIEW_X + VIEW_SIZE - 1, top + VIEW_Y + VIEW_SIZE - 1,
                DaisyGui.SLOT);
        swatches(graphics, false, BODY_Y, BODY_PRESETS, "screen.daisychain.colours.body");
        swatches(graphics, true, LINE_Y, LINE_PRESETS, "screen.daisychain.colours.line");
        field(graphics, bodyHex);
        field(graphics, lineHex);
        DaisyGui.text(graphics, font, DaisyGui.styled(Component.translatable("screen.daisychain.colours.only_you")),
                left + 8, top + H - 28, DaisyGui.DIM);
        if (capped) {
            DaisyGui.text(graphics, font, DaisyGui.styled(Component.translatable("screen.daisychain.colours.capped")),
                    left + 8, top + H - 16, DaisyGui.WARN_TEXT);
        }
    }

    private void swatches(GuiGraphics graphics, boolean isLine, int y, int[] presets, String label) {
        DaisyGui.text(graphics, font, DaisyGui.styled(Component.translatable(label)), left + COL_X, top + y + 1,
                DaisyGui.DIM);
        int current = (isLine ? line : body)[slot];
        for (int i = 0; i < presets.length; i++) {
            int x = left + COL_X + i % PER_ROW * SWATCH_STEP;
            int sy = top + y + 12 + i / PER_ROW * SWATCH_STEP;
            int rgb = swatchColour(isLine, presets[i]);
            boolean on = presets[i] < 0 ? current < 0 : current == rgb;
            graphics.fill(x - 1, sy - 1, x + SWATCH + 1, sy + SWATCH + 1, on ? DaisyGui.TEXT : DaisyGui.EDGE);
            graphics.fill(x, sy, x + SWATCH, sy + SWATCH, 0xFF000000 | rgb);
        }
    }

    /** What a preset gives on this pipe (default -> its default colour; too bright -> as bright as it can go). */
    private int swatchColour(boolean isLine, int preset) {
        if (preset < 0) {
            return isLine ? PipeTints.DEFAULT_LINE[slot] : PipeTints.DEFAULT_SURFACE[slot];
        }
        return PipeTints.clamp(slot, isLine, preset);
    }

    private void field(GuiGraphics graphics, EditBox box) {
        int x = box.getX() - 4;
        int y = box.getY() - 3;
        DaisyGui.round(graphics, x, y, x + 64, y + 14, box.isFocused() ? DaisyGui.FIELD_HOVER : DaisyGui.FIELD_BORDER, 1);
        graphics.fill(x + 1, y + 1, x + 63, y + 13, DaisyGui.SLOT);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderPreview(graphics);
    }

    private void renderPreview(GuiGraphics graphics) {
        if (minecraft == null) {
            return;
        }
        Block block = (slot == 0 ? ModBlocks.ITEM_PIPE : slot == 1 ? ModBlocks.FLUID_PIPE : ModBlocks.UNIVERSAL_PIPE).get();
        BlockState center = pipe(block, BasePipeBlock.NORTH, BasePipeBlock.SOUTH, BasePipeBlock.EAST);
        int bodyTint = PipeTints.tintFor(slot, false, body[slot]);
        int lineTint = PipeTints.tintFor(slot, true, line[slot]);
        int x0 = left + VIEW_X;
        int y0 = top + VIEW_Y;
        graphics.flush();
        graphics.enableScissor(x0 + 1, y0 + 1, x0 + VIEW_SIZE - 1, y0 + VIEW_SIZE - 1);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x0 + VIEW_SIZE / 2.0F, y0 + VIEW_SIZE / 2.0F, 150.0F);
        pose.scale(30.0F, -30.0F, 30.0F);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-0.5F, -0.5F, -0.5F);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Lighting.setupFor3DItems();
        // A small T: the middle pipe, a dead end north and south, one block east.
        draw(pose, buffers, center, 0, 0, 0, bodyTint, lineTint);
        draw(pose, buffers, pipe(block, BasePipeBlock.SOUTH), 0, 0, -1, bodyTint, lineTint);
        draw(pose, buffers, pipe(block, BasePipeBlock.NORTH), 0, 0, 1, bodyTint, lineTint);
        draw(pose, buffers, pipe(block, BasePipeBlock.WEST), 1, 0, 0, bodyTint, lineTint);
        buffers.endBatch();
        pose.popPose();
        graphics.disableScissor();
        if (DaisychainClientConfig.showHints()) {
            DaisyGui.text(graphics, font, DaisyGui.styled(Component.translatable("screen.daisychain.colours.drag")),
                    x0 + 4, y0 + VIEW_SIZE - 11, DaisyGui.FAINT);
        }
    }

    @SafeVarargs
    private static BlockState pipe(Block block, EnumProperty<SideState>... connected) {
        BlockState state = block.defaultBlockState();
        for (EnumProperty<SideState> side : connected) {
            state = state.setValue(side, SideState.CONNECTED);
        }
        return state;
    }

    private static void draw(PoseStack pose, MultiBufferSource buffers, BlockState state, int dx, int dy, int dz,
                             int bodyTint, int lineTint) {
        pose.pushPose();
        pose.translate(dx, dy, dz);
        PipePreview.render(state, pose, buffers, tint -> tint == PipeColors.BODY_TINT ? bodyTint
                : tint == PipeColors.LINE_TINT ? lineTint : PipeColors.color(state, null, null, tint));
        pose.popPose();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            if (clickSwatch(mouseX, mouseY, false, BODY_Y, BODY_PRESETS)
                    || clickSwatch(mouseX, mouseY, true, LINE_Y, LINE_PRESETS)) {
                return true;
            }
            int x0 = left + VIEW_X;
            int y0 = top + VIEW_Y;
            if (mouseX >= x0 && mouseX < x0 + VIEW_SIZE && mouseY >= y0 && mouseY < y0 + VIEW_SIZE) {
                dragging = true;
                return true;
            }
        }
        return false;
    }

    private boolean clickSwatch(double mouseX, double mouseY, boolean isLine, int y, int[] presets) {
        for (int i = 0; i < presets.length; i++) {
            int x = left + COL_X + i % PER_ROW * SWATCH_STEP;
            int sy = top + y + 12 + i / PER_ROW * SWATCH_STEP;
            if (mouseX >= x && mouseX < x + SWATCH && mouseY >= sy && mouseY < sy + SWATCH) {
                pick(isLine, presets[i]);
                setFocused(null);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            yaw += (float) dragX * 1.5F;
            pitch = Math.max(-89.0F, Math.min(89.0F, pitch + (float) dragY * 1.5F));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
