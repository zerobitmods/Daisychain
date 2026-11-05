package com.daisychain.client;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.SideState;
import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.item.UpgradeTier;
import com.daisychain.net.OpenPullSidePayload;
import com.daisychain.net.SetSideModePayload;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * All six sides of one pipe (step 48, design spec 5.4, in the step 47c look). Panel 176 x 150 with the "all sides"
 * tab selected. Changes from the spec after a render check: squares 4 px apart (centered at x 22) so the selection ring
 * doesn't touch the neighbors, and mode key widths follow Minecraft's font.
 * <ul>
 *   <li>Header (caps on y 8): what the selected side touches (11 px icon at 7,6), the side's compass name at x 22 and
 *   the block's name in dim. A "3D" key on the right switches the cross for the 3D view in the same space.</li>
 *   <li>Cross: six 30 x 30 squares, 4 px apart, from (22,23), arranged around where the player stands (step 21h):
 *   <pre>
 *          [ Up  ]
 *  [Left ] [Front] [Right] [Back]
 *          [Down ]
 *   </pre>
 *   Each square: 2 px border in its mode color, the compass letter (dim) at (+5,+5), the neighbor's icon at (+15,+14).
 *   Click = select only. The selected square gets a 1 px ring 2 px outside it.</li>
 *   <li>Mode row at y 126, 17 tall: Pull / Insert / Link / Off for the selected side (keys a side can't use are faint),
 *   then a sliders key that opens that side's settings.</li>
 * </ul>
 * Client only. Mode keys send SetSideModePayload; the server applies only modes allowed for the side
 * (FlowPipeBlock.allowedModes, the same rule this screen uses to grey keys out).
 */
public final class PipeSidesScreen extends Screen {
    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_HEIGHT = 150;
    private static final int SQUARE = 30;
    /** 4 px between squares (spec: 2) so the selection ring sits in the middle of the gap (step 48 render check). */
    private static final int GAP = 4;
    /** The cross (4 x 30 + 3 x 4 = 132 wide) centered in the content width x 7-169. */
    private static final int CROSS_X = 22;
    private static final int CROSS_Y = 23;
    private static final int MODE_ROW_Y = 126;
    private static final int MODE_ROW_HEIGHT = 17;
    /** Mode keys fill x 7-146 (spec), 1 px apart; widths follow the text (see init). */
    private static final int MODE_ROW_RIGHT = 147;
    private static final int SETTINGS_KEY_X = 151;

    // 3D view: the same space as the cross (x 7-169, y 21-122).
    private static final int VIEW_LEFT = 7;
    private static final int VIEW_TOP = 21;
    private static final int VIEW_WIDTH = 162;
    private static final int VIEW_HEIGHT = 101;
    private static final float VIEW_Z = 150.0F;
    private static final float MIN_ZOOM = 12.0F;
    private static final float MAX_ZOOM = 48.0F;
    private static final AABB CORE_BOX = new AABB(5 / 16.0, 5 / 16.0, 5 / 16.0, 11 / 16.0, 11 / 16.0, 11 / 16.0);

    /** Display modes of a side. */
    private static final int SHOW_NONE = 0;
    private static final int SHOW_PULL = 1;
    private static final int SHOW_PULL_EMPTY = 2;
    private static final int SHOW_INSERT = 3;
    private static final int SHOW_LINK = 4;
    private static final int SHOW_OFF = 5;
    private static final String[] SHOW_KEYS = {"nothing", "pull", "pull_missing", "insert", "connected", "disconnected"};
    private static final int[] SHOW_COLORS = {FlowGui.MODE_NONE, FlowGui.MODE_PULL, FlowGui.MODE_PULL_EMPTY,
            FlowGui.MODE_INSERT, FlowGui.MODE_LINK, FlowGui.MODE_OFF};

    /** The four mode keys: label key, the mode sent, and the fill / ink when chosen. */
    private static final String[] KEY_NAMES = {"pull", "insert", "link", "off"};
    private static final int[] KEY_MODES = {FlowPipeBlock.MODE_PULL, FlowPipeBlock.MODE_NORMAL, FlowPipeBlock.MODE_NORMAL,
            FlowPipeBlock.MODE_DISCONNECTED};
    private static final int[] KEY_TINTS = {FlowGui.PULL_TINT, FlowGui.INSERT_TINT, FlowGui.ON, FlowGui.WARN_TINT};
    private static final int[] KEY_INKS = {FlowGui.PULL_INK, FlowGui.INSERT_INK, FlowGui.ON_INK, FlowGui.WARN_TEXT};

    /** Square positions in the cross (column, row), in this order: front, left, right, back, up, down. */
    private static final int[][] CELLS = {{1, 1}, {0, 1}, {2, 1}, {3, 1}, {1, 0}, {1, 2}};
    /** The header text ends here (panel x), 4 px left of the 3D key; set in init. */
    private int headerRight = 120;
    private static final String[] RELATIVE_KEYS = {"front", "left", "right", "back", "top", "bottom"};

    private final BlockPos pos;
    /** The real side each slot stands for, worked out from the player's facing each tick. */
    private final Direction[] sides = {Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.NORTH,
            Direction.UP, Direction.DOWN};
    private final int[] shown = new int[6];
    /** Per slot: bits of FlowPipeBlock.MODE_* the side may be set to. */
    private final int[] allowed = new int[6];
    private final boolean[] neighborIsPipe = new boolean[6];
    private final boolean[] hasSettings = new boolean[6];
    /** Step 67: upgrade level per slot (0 = none); on a side that isn't pulling it's parked. */
    private final int[] upgradeTier = new int[6];
    private final ItemStack[] icons = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
            ItemStack.EMPTY, ItemStack.EMPTY};
    private final Component[] blockNames = new Component[6];
    private final SideSquare[] squares = new SideSquare[6];
    private final FlowKey[] modeKeys = new FlowKey[4];
    private FlowKey settingsKey;
    private FlowTab thisSideTab;
    private Direction facing = Direction.NORTH;
    private int panelX;
    private int panelY;

    // View state, kept across re-inits (window resize).
    private boolean view3d;
    private float yawOffset = 30.0F;
    private float pitch = 25.0F;
    private float zoom = 22.0F;
    private int selected; // index into sides[] (0 = front)
    private boolean pressing;
    private boolean dragged;
    private double pressX;
    private double pressY;

    /** The side whose settings screen this was opened from (the face tab goes back there), or null. */
    @Nullable
    private final Direction returnSide;

    PipeSidesScreen(BlockPos pos) {
        this(pos, null);
    }

    PipeSidesScreen(BlockPos pos, @Nullable Direction returnSide) {
        super(Component.translatable("screen.daisychain.pipe_sides"));
        this.pos = pos;
        this.returnSide = returnSide;
        this.view3d = DaisychainClientConfig.sidesView3d();
        java.util.Arrays.fill(blockNames, Component.empty());
    }

    @Override
    protected void init() {
        panelX = Math.max((width - PANEL_WIDTH) / 2, 26); // room for the tabs on the left
        panelY = (height - PANEL_HEIGHT) / 2;
        selectReturnSide();

        // Tabs: this side's settings (the side we came from, else the selected side) and this screen.
        thisSideTab = addRenderableWidget(new FlowTab(panelX, panelY + 8, FlowGui.FACE, false,
                Component.translatable("screen.daisychain.tab.this_side"), this::openTabSide));
        addRenderableWidget(new FlowTab(panelX, panelY + 34, FlowGui.CUBE, true,
                Component.translatable("screen.daisychain.tab.all_sides"), () -> { }));

        // 3D key: right-aligned to x 169 on the header line.
        Component label3d = Component.translatable("screen.daisychain.sides.view_3d");
        int width3d = FlowGui.inkWidth(font, label3d) + 8;
        headerRight = 169 - width3d - 4;
        addRenderableWidget(FlowKey.text(panelX + 169 - width3d, panelY + 5, width3d, 13, () -> label3d,
                () -> view3d, FlowGui.ON, FlowGui.ON_INK, () -> setView(!view3d)))
                .setTooltip(Tooltip.create(Component.translatable("screen.daisychain.sides.view_3d_help")));

        for (int i = 0; i < 6; i++) {
            int x = panelX + CROSS_X + CELLS[i][0] * (SQUARE + GAP);
            int y = panelY + CROSS_Y + CELLS[i][1] * (SQUARE + GAP);
            squares[i] = addRenderableWidget(new SideSquare(x, y, i));
        }

        // Mode row: Pull / Insert / Link / Off, then the settings key. Each key is its text + 8 plus an even share of
        // the room left (the rule the design scripts use for a row of keys), so the row ends at x 146.
        Component[] labels = new Component[4];
        int[] widths = new int[4];
        int used = 3;
        for (int k = 0; k < 4; k++) {
            labels[k] = Component.translatable("screen.daisychain.sides.mode." + KEY_NAMES[k]);
            widths[k] = FlowGui.inkWidth(font, labels[k]) + 8;
            used += widths[k];
        }
        int extra = Math.max(0, MODE_ROW_RIGHT - 7 - used);
        int keyX = panelX + 7;
        for (int k = 0; k < 4; k++) {
            int key = k;
            Component label = labels[k];
            int keyWidth = widths[k] + extra / 4 + (k < extra % 4 ? 1 : 0);
            modeKeys[k] = addRenderableWidget(FlowKey.text(keyX, panelY + MODE_ROW_Y, keyWidth, MODE_ROW_HEIGHT,
                    () -> label, () -> chosenKey() == key, KEY_TINTS[k], KEY_INKS[k], () -> setMode(key)));
            keyX += keyWidth + 1;
        }
        settingsKey = addRenderableWidget(FlowKey.small(panelX + SETTINGS_KEY_X, panelY + MODE_ROW_Y, 18,
                MODE_ROW_HEIGHT, () -> FlowGui.SLIDERS, () -> FlowGui.TEXT, () -> openSettings(sides[selected])));

        refresh();
        applyView();
    }

    /** Opened from a side's settings: start with that side selected. */
    private void selectReturnSide() {
        if (returnSide == null || minecraft == null || minecraft.player == null) {
            return;
        }
        updateSides();
        for (int i = 0; i < 6; i++) {
            if (sides[i] == returnSide) {
                selected = i;
            }
        }
    }

    private void setView(boolean threeD) {
        view3d = threeD;
        pressing = false;
        DaisychainClientConfig.setSidesView3d(threeD);
        applyView();
    }

    /** The squares only exist in the cross view (hidden widgets ignore clicks). */
    private void applyView() {
        for (SideSquare square : squares) {
            square.visible = !view3d;
        }
    }

    @Nullable
    private BlockState pipeState() {
        if (minecraft == null || minecraft.level == null || minecraft.player == null
                || minecraft.player.distanceToSqr(Vec3.atCenterOf(pos)) > 8.0 * 8.0) {
            return null;
        }
        BlockState state = minecraft.level.getBlockState(pos);
        return state.getBlock() instanceof FlowPipeBlock ? state : null;
    }

    /** The side settings screen has a real item slot, so the server opens it (after checking the request). */
    private void openSettings(Direction side) {
        if (pipeState() != null) {
            PacketDistributor.sendToServer(new OpenPullSidePayload(pos, (byte) side.get3DDataValue()));
        }
    }

    /** The face tab: the side this screen was opened from, or else the selected side. */
    private void openTabSide() {
        Direction side = returnSide != null ? returnSide : sides[selected];
        if (hasSettings(side)) {
            openSettings(side);
        }
    }

    private boolean hasSettings(Direction side) {
        BlockState state = pipeState();
        return state != null && minecraft != null && minecraft.level != null
                && (FlowPipeBlock.isPullSide(minecraft.level, pos, state, side)
                || FlowPipeBlock.isInsertSide(minecraft.level, pos, state, side));
    }

    /** A mode key: sets the selected side to that key's mode (the server checks it's allowed). */
    private void setMode(int key) {
        if (pipeState() != null && keyAllowed(key) && chosenKey() != key) {
            PacketDistributor.sendToServer(new SetSideModePayload(pos, (byte) sides[selected].get3DDataValue(),
                    (byte) KEY_MODES[key]));
        }
    }

    /** Insert is for sides facing a block, Link for sides facing a pipe; both mean the "normal" mode. */
    private boolean keyAllowed(int key) {
        if ((allowed[selected] & (1 << KEY_MODES[key])) == 0) {
            return false;
        }
        if (key == 1) {
            return !neighborIsPipe[selected];
        }
        if (key == 2) {
            return neighborIsPipe[selected];
        }
        return true;
    }

    /** Which mode key shows as chosen for the selected side, or -1 (a normal side with nothing to connect to). */
    private int chosenKey() {
        return switch (shown[selected]) {
            case SHOW_PULL, SHOW_PULL_EMPTY -> 0;
            case SHOW_INSERT -> 1;
            case SHOW_LINK -> 2;
            case SHOW_OFF -> 3;
            default -> -1;
        };
    }

    @Override
    public void tick() {
        super.tick();
        if (pipeState() == null) {
            onClose();
            return;
        }
        refresh();
    }

    private void updateSides() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        facing = minecraft.player.getDirection(); // horizontal direction the player looks
        sides[0] = facing.getOpposite();
        sides[1] = facing.getCounterClockWise();
        sides[2] = facing.getClockWise();
        sides[3] = facing;
    }

    /** Works out which real side each slot shows (from the player's facing) and its current mode. Once per tick. */
    private void refresh() {
        BlockState state = pipeState();
        if (state == null || minecraft == null || minecraft.level == null) {
            return;
        }
        updateSides();
        FlowPipeBlock pipe = (FlowPipeBlock) state.getBlock();
        PipeDataBlockEntity data = FlowPipeBlock.getData(minecraft.level, pos, state);
        for (int i = 0; i < 6; i++) {
            Direction side = sides[i];
            BlockState neighbor = minecraft.level.getBlockState(pos.relative(side));
            neighborIsPipe[i] = pipe.linksWith(neighbor); // step 84: energy pipes of every tier link
            SideState sideState = state.getValue(FlowPipeBlock.propertyFor(side));
            if (data != null && data.isDisconnected(side)) {
                shown[i] = SHOW_OFF;
            } else if (sideState == SideState.EXTRACT) {
                shown[i] = SHOW_PULL;
            } else if (FlowPipeBlock.isPullSide(minecraft.level, pos, state, side)) {
                shown[i] = SHOW_PULL_EMPTY; // pull mark kept, nothing to pull from
            } else if (sideState == SideState.CONNECTED) {
                shown[i] = neighborIsPipe[i] ? SHOW_LINK : SHOW_INSERT;
            } else {
                shown[i] = SHOW_NONE;
            }
            allowed[i] = pipe.allowedModes(minecraft.level, pos, side);
            upgradeTier[i] = data == null ? 0 : data.upgradeLevel(side);
            hasSettings[i] = FlowPipeBlock.isPullSide(minecraft.level, pos, state, side)
                    || FlowPipeBlock.isInsertSide(minecraft.level, pos, state, side);
            ItemStack icon = neighbor.isAir() ? ItemStack.EMPTY : new ItemStack(neighbor.getBlock().asItem());
            if (!ItemStack.isSameItem(icon, icons[i])) {
                icons[i] = icon;
            }
            blockNames[i] = neighbor.isAir() ? Component.translatable("screen.daisychain.nothing_attached")
                    : neighbor.getBlock().getName();
            squares[i].setTooltip(Tooltip.create(sideTooltip(i)));
        }
        for (int k = 0; k < 4; k++) {
            modeKeys[k].active = keyAllowed(k);
            MutableComponent tip = Component.translatable("screen.daisychain.sides.mode_help." + KEY_NAMES[k]);
            if (!modeKeys[k].active) {
                tip.append("\n").append(Component.translatable("screen.daisychain.sides.mode_unavailable")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            modeKeys[k].setTooltip(Tooltip.create(tip));
        }
        settingsKey.active = hasSettings[selected];
        settingsKey.setTooltip(Tooltip.create(Component.translatable(hasSettings[selected]
                ? "screen.daisychain.sides.settings" : "screen.daisychain.sides.no_settings")));
        Direction tabSide = returnSide != null ? returnSide : sides[selected];
        boolean tabActive = hasSettings(tabSide);
        thisSideTab.active = tabActive;
        thisSideTab.setTooltip(Tooltip.create(Component.translatable(tabActive
                ? "screen.daisychain.tab.this_side_help" : "screen.daisychain.tab.this_side_none",
                Component.translatable("message.daisychain.direction." + tabSide.getSerializedName()))));
    }

    /** Square tooltip: where it is, what it touches, its mode (and how to use it, with hints on). */
    private Component sideTooltip(int i) {
        MutableComponent text = sideName(i).copy();
        text.append("\n").append(blockNames[i].copy().withStyle(ChatFormatting.GRAY));
        text.append("\n").append(Component.translatable("screen.daisychain.side_mode_long." + SHOW_KEYS[shown[i]])
                .withStyle(ChatFormatting.GRAY));
        if (upgradeTier[i] > 0) {
            Component tier = Component.translatable("upgrade.daisychain.tier." + UpgradeTier.byLevel(upgradeTier[i]).id());
            // Step 84: energy pipes don't use upgrades, so one left in an energy side is always parked.
            BlockState pipeNow = pipeState();
            boolean energyPipe = pipeNow != null && pipeNow.getBlock() instanceof com.daisychain.block.EnergyPipeBlock;
            text.append("\n").append(Component.translatable(energyPipe ? "screen.daisychain.sides.upgrade_unused"
                    : shown[i] == SHOW_PULL || shown[i] == SHOW_PULL_EMPTY
                    ? "screen.daisychain.sides.upgrade" : "screen.daisychain.sides.upgrade_parked", tier)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (DaisychainClientConfig.showHints()) {
            text.append("\n").append(Component.translatable("screen.daisychain.sides.select_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return text;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        FlowGui.panel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        // Header (caps on y 8): what the selected side touches, its compass name, the block's name.
        ItemStack icon = icons[selected];
        if (!icon.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(panelX + 7, panelY + 6, 0);
            graphics.pose().scale(11 / 16.0F, 11 / 16.0F, 1);
            graphics.renderFakeItem(icon, 0, 0);
            graphics.pose().popPose();
        }
        Component name = sideName(selected);
        FlowGui.text(graphics, font, name, panelX + 22, panelY + 8, FlowGui.TEXT);
        int blockX = panelX + 22 + FlowGui.inkWidth(font, name) + 4;
        FlowGui.text(graphics, font, fit(blockNames[selected], panelX + headerRight - blockX), blockX, panelY + 8,
                FlowGui.DIM);
    }

    /** "Front (North)" for the four flat sides, just "Top" or "Bottom" for the other two. */
    private Component sideName(int i) {
        Component relative = Component.translatable("screen.daisychain.side." + RELATIVE_KEYS[i]);
        if (sides[i].getAxis().isVertical()) {
            return relative;
        }
        return Component.translatable("screen.daisychain.sides.where", relative,
                Component.translatable("message.daisychain.direction." + sides[i].getSerializedName()));
    }

    /** Cuts a name down to fit the given width, ending it with "..." when it was too long. */
    private Component fit(Component text, int maxWidth) {
        if (FlowGui.inkWidth(font, text) <= maxWidth) {
            return text;
        }
        String dots = "...";
        String cut = font.plainSubstrByWidth(text.getString(), Math.max(0, maxWidth - font.width(dots)));
        return Component.literal(cut.stripTrailing() + dots);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (view3d) {
            int hovered = pressing ? -1 : pickSide(mouseX, mouseY);
            render3d(graphics, hovered);
            if (hovered >= 0) {
                graphics.renderTooltip(font, font.split(sideTooltip(hovered), 170), mouseX, mouseY);
            }
        }
    }

    /** The pipe and its six neighbors as real block models, plus an outline per side in its mode color. */
    private void render3d(GuiGraphics graphics, int hovered) {
        BlockState state = pipeState();
        if (state == null || minecraft == null || minecraft.level == null) {
            return;
        }
        int left = panelX + VIEW_LEFT;
        int top = panelY + VIEW_TOP;
        FlowGui.round(graphics, left, top, left + VIEW_WIDTH, top + VIEW_HEIGHT, FlowGui.SLOT_EDGE, 1);
        graphics.fill(left + 1, top + 1, left + VIEW_WIDTH - 1, top + VIEW_HEIGHT - 1, FlowGui.SLOT);
        if (DaisychainClientConfig.showHints()) {
            graphics.drawString(font, Component.translatable("screen.daisychain.view_3d_hint"), left + 4, top + 4,
                    FlowGui.FAINT, false);
        }
        graphics.flush();
        graphics.enableScissor(left + 1, top + 1, left + VIEW_WIDTH - 1, top + VIEW_HEIGHT - 1);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + VIEW_WIDTH / 2.0F, top + VIEW_HEIGHT / 2.0F, VIEW_Z);
        pose.scale(zoom, -zoom, zoom);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yawDegrees()));
        pose.translate(-0.5F, -0.5F, -0.5F);

        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        BlockRenderDispatcher blocks = minecraft.getBlockRenderer();
        Lighting.setupFor3DItems();
        blocks.renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        for (int i = 0; i < 6; i++) {
            BlockState neighbor = minecraft.level.getBlockState(pos.relative(sides[i]));
            if (neighbor.isAir() || neighbor.getRenderShape() == RenderShape.INVISIBLE) {
                continue;
            }
            pose.pushPose();
            pose.translate(sides[i].getStepX(), sides[i].getStepY(), sides[i].getStepZ());
            blocks.renderSingleBlock(neighbor, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        buffers.endBatch();

        // Step 102: Minecraft's line shader shrinks every line toward the view's origin by 1/256 (made for the world,
        // where that origin is the camera). In a screen the origin is the top-left corner, so the boxes came out a few
        // pixels up and to the left of the blocks. Drawing them 256/255 bigger around that corner cancels it exactly.
        pose.popPose();
        pose.pushPose();
        pose.scale(256.0F / 255.0F, 256.0F / 255.0F, 1.0F);
        pose.translate(left + VIEW_WIDTH / 2.0F, top + VIEW_HEIGHT / 2.0F, VIEW_Z);
        pose.scale(zoom, -zoom, zoom);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yawDegrees()));
        pose.translate(-0.5F, -0.5F, -0.5F);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (int i = 0; i < 6; i++) {
            int color = i == selected ? FlowGui.WHITE : i == hovered ? FlowGui.DIM : SHOW_COLORS[shown[i]];
            double grow = i == selected ? 0.04 : 0.015;
            LevelRenderer.renderLineBox(pose, lines, neighborBox(sides[i]).inflate(grow),
                    ((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
        }
        buffers.endBatch(RenderType.lines());
        pose.popPose();
        graphics.disableScissor();
    }

    /** Turns the view so the side facing the player is toward the screen, then adds the player's drag. */
    private float yawDegrees() {
        return facing.toYRot() - 180.0F + yawOffset;
    }

    private static AABB neighborBox(Direction side) {
        return new AABB(side.getStepX(), side.getStepY(), side.getStepZ(),
                side.getStepX() + 1, side.getStepY() + 1, side.getStepZ() + 1);
    }

    private boolean inView(double mouseX, double mouseY) {
        int left = panelX + VIEW_LEFT;
        int top = panelY + VIEW_TOP;
        return mouseX >= left && mouseX < left + VIEW_WIDTH && mouseY >= top && mouseY < top + VIEW_HEIGHT;
    }

    /**
     * Which side slot (0-5) is under the mouse in the 3D view, or -1. Undoes the view's rotation to turn the mouse
     * position into a line through the model, then finds the nearest box it hits: the pipe core (blocks the view),
     * each side's arm, and each neighbor block.
     */
    private int pickSide(double mouseX, double mouseY) {
        if (!view3d || minecraft == null || minecraft.level == null || !inView(mouseX, mouseY)) {
            return -1;
        }
        Matrix4f inverse = new Matrix4f()
                .translate(panelX + VIEW_LEFT + VIEW_WIDTH / 2.0F, panelY + VIEW_TOP + VIEW_HEIGHT / 2.0F, VIEW_Z)
                .scale(zoom, -zoom, zoom)
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yawDegrees()))
                .translate(-0.5F, -0.5F, -0.5F)
                .invert();
        Vector3f near = inverse.transformPosition(new Vector3f((float) mouseX, (float) mouseY, VIEW_Z + 1000.0F));
        Vector3f far = inverse.transformPosition(new Vector3f((float) mouseX, (float) mouseY, VIEW_Z - 1000.0F));
        Vec3 from = new Vec3(near.x, near.y, near.z);
        Vec3 to = new Vec3(far.x, far.y, far.z);

        double best = Double.MAX_VALUE;
        int bestSide = -1;
        Optional<Vec3> coreHit = CORE_BOX.clip(from, to);
        if (coreHit.isPresent()) {
            best = coreHit.get().distanceToSqr(from);
        }
        for (int i = 0; i < 6; i++) {
            Direction side = sides[i];
            Optional<Vec3> armHit = FlowPipeBlock.armBounds(side.get3DDataValue()).clip(from, to);
            if (armHit.isPresent() && armHit.get().distanceToSqr(from) < best) {
                best = armHit.get().distanceToSqr(from);
                bestSide = i;
            }
            // The whole neighbor cube is clickable, also when it's empty air.
            Optional<Vec3> blockHit = neighborBox(side).clip(from, to);
            if (blockHit.isPresent() && blockHit.get().distanceToSqr(from) < best) {
                best = blockHit.get().distanceToSqr(from);
                bestSide = i;
            }
        }
        return bestSide;
    }

    private void select(int slot) {
        if (slot >= 0 && slot < 6) {
            selected = slot;
            refresh();
        }
    }

    // ---------------------------------------------------------------- 3D mouse: drag to turn, click to pick

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (view3d && button == 0 && inView(mouseX, mouseY)) {
            pressing = true;
            dragged = false;
            pressX = mouseX;
            pressY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pressing && button == 0) {
            if (Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) > 3.0) {
                dragged = true;
            }
            if (dragged) {
                yawOffset += (float) dragX * 1.5F;
                pitch = Math.max(-89.0F, Math.min(89.0F, pitch + (float) dragY * 1.5F));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressing && button == 0) {
            pressing = false;
            if (!dragged) {
                select(pickSide(mouseX, mouseY));
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (view3d && inView(mouseX, mouseY)) {
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + (float) scrollY * 3.0F));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** The inventory key (E by default) closes the menu, like other Minecraft screens. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * One square of the cross: a 2 px border in the side's mode color (corners rounded 1 px), the compass letter in dim
     * at (+5,+5) and what the side touches at (+15,+14). Click = select. Selected: a 1 px ring 2 px outside.
     */
    private final class SideSquare extends AbstractButton {
        private final int slot;

        SideSquare(int x, int y, int slot) {
            super(x, y, SQUARE, SQUARE, Component.empty());
            this.slot = slot;
        }

        @Override
        public void onPress() {
            select(slot);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            if (slot == selected) {
                int r0 = x - 2;
                int r1 = x + SQUARE + 2;
                int s0 = y - 2;
                int s1 = y + SQUARE + 2;
                FlowGui.round(graphics, r0, s0, r1, s1, FlowGui.WHITE, 1);
                FlowGui.round(graphics, r0 + 1, s0 + 1, r1 - 1, s1 - 1, FlowGui.PANEL, 1);
            }
            FlowGui.round(graphics, x, y, x + SQUARE, y + SQUARE, SHOW_COLORS[shown[slot]], 1);
            graphics.fill(x + 2, y + 2, x + SQUARE - 2, y + SQUARE - 2, isHovered() ? FlowGui.KEY : FlowGui.SLOT);
            Component letter = Component.translatable("screen.daisychain.side_letter." + RELATIVE_KEYS[slot]);
            FlowGui.text(graphics, font, letter, x + 5, y + 5, FlowGui.DIM);
            if (upgradeTier[slot] > 0) {
                // Step 67: the side holds an upgrade (pulling, or parked): a tier-colored mark top right.
                FlowGui.round(graphics, x + SQUARE - 10, y + 4, x + SQUARE - 4, y + 10, FlowGui.PANEL, 1);
                graphics.fill(x + SQUARE - 9, y + 5, x + SQUARE - 5, y + 9, 0xFF000000 | PipeColors.tierColor(upgradeTier[slot]));
            }
            ItemStack icon = icons[slot];
            if (!icon.isEmpty()) {
                graphics.pose().pushPose();
                graphics.pose().translate(x + 15, y + 14, 0);
                graphics.pose().scale(11 / 16.0F, 11 / 16.0F, 1);
                graphics.renderFakeItem(icon, 0, 0);
                graphics.pose().popPose();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
