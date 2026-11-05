package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.net.PasteHighlightPayload;
import com.daisychain.registry.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.daisychain.net.UndoPastePayload;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import com.daisychain.net.DaisychainNetwork;
import net.minecraftforge.client.event.RenderLevelStageEvent;


/**
 * Settings Copier outlines (steps 48e/48f). The server tells this player which pipe sides to outline; each side gets a
 * box around its arm plus a square on the face of the block it touches, so you can see which machine it is. Drawn twice:
 * faint through walls (so a whole network shows) and bright where it's in plain view. Only shown while the player holds
 * the copier. Three groups:
 * <ul>
 *   <li>preview (gold): what the confirming click will change; disappears when the 3 s confirm window ends;</li>
 *   <li>source (white): the side the copier's settings came from, until the next copy or clear;</li>
 *   <li>result (green = pasted, blue = put back by an undo): the latest paste or undo, until the next one replaces it.
 *       So it always shows what Undo would put back.</li>
 * </ul>
 * Client only.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PasteHighlights {

    private static final int MAX = 8192;
    private static final long PREVIEW_MS = 3000;
    private static final long FADE_MS = 400;

    private static final int GOLD = 0xFFC94A;
    private static final int GREEN = 0x6CE08A;
    private static final int BLUE = 0x5CC4F2;
    private static final int WHITE = 0xFFFFFF;


    private static final AABB[] ARM_BOXES = new AABB[6];
    private static final AABB[] FACE_BOXES = new AABB[6];

    static {
        for (Direction side : Direction.values()) {
            int i = side.get3DDataValue();
            ARM_BOXES[i] = FlowPipeBlock.armBounds(i).inflate(0.03);
            // A flat square just outside the pipe's block, on the face of the neighbor it touches.
            double inset = 0.06;
            double x0 = inset;
            double y0 = inset;
            double z0 = inset;
            double x1 = 1 - inset;
            double y1 = 1 - inset;
            double z1 = 1 - inset;
            double edge = 0.01;
            switch (side) {
                case DOWN -> { y0 = -edge; y1 = edge; }
                case UP -> { y0 = 1 - edge; y1 = 1 + edge; }
                case NORTH -> { z0 = -edge; z1 = edge; }
                case SOUTH -> { z0 = 1 - edge; z1 = 1 + edge; }
                case WEST -> { x0 = -edge; x1 = edge; }
                case EAST -> { x0 = 1 - edge; x1 = 1 + edge; }
            }
            FACE_BOXES[i] = new AABB(x0, y0, z0, x1, y1, z1);
        }
    }

    /** One group of outlined sides in one color. */
    private static final class Group {
        final long[] positions;
        final byte[] sides;
        int count;
        int color;

        Group(int capacity) {
            positions = new long[capacity];
            sides = new byte[capacity];
        }

        void set(long[] newPositions, byte[] newSides, int newColor) {
            count = 0;
            add(newPositions, newSides, newColor);
        }

        void add(long[] newPositions, byte[] newSides, int newColor) {
            color = newColor;
            int n = Math.min(newPositions.length, newSides.length);
            for (int i = 0; i < n && count < positions.length; i++) {
                if (newSides[i] >= 0 && newSides[i] <= 5) {
                    positions[count] = newPositions[i];
                    sides[count] = newSides[i];
                    count++;
                }
            }
        }
    }

    private static final Group preview = new Group(MAX);
    private static final Group source = new Group(1);
    private static final Group result = new Group(MAX);
    private static long previewEnd;

    private PasteHighlights() {
    }

    /** Outlines from the server. See PasteHighlightPayload for the kinds. */
    static void show(byte kind, long[] positions, byte[] sides) {
        switch (kind) {
            case PasteHighlightPayload.PREVIEW -> {
                preview.set(positions, sides, GOLD);
                previewEnd = Util.getMillis() + PREVIEW_MS;
            }
            case PasteHighlightPayload.PASTED -> {
                preview.count = 0;
                result.set(positions, sides, GREEN);
            }
            case PasteHighlightPayload.PASTED_STREAK -> {
                preview.count = 0;
                if (result.color != GREEN) {
                    result.count = 0; // a streak adds to its own green set, never to an undo's blue one
                }
                result.add(positions, sides, GREEN);
            }
            case PasteHighlightPayload.UNDONE -> {
                preview.count = 0;
                result.set(positions, sides, BLUE);
            }
            case PasteHighlightPayload.COPIED -> {
                preview.count = 0;
                source.set(positions, sides, WHITE);
            }
            case PasteHighlightPayload.CLEAR -> {
                preview.count = 0;
                source.count = 0;
                result.count = 0;
            }
            default -> {
            }
        }
    }

    /** The undo and redo keys, only while holding the copier and with no screen open. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        while (CopierKeys.UNDO_PASTE.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null && holdingCopier(minecraft.player)) {
                DaisychainNetwork.sendToServer(UndoPastePayload.UNDO);
            }
        }
        while (CopierKeys.REDO_PASTE.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null && holdingCopier(minecraft.player)) {
                DaisychainNetwork.sendToServer(UndoPastePayload.REDO);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        preview.count = 0;
        source.count = 0;
        result.count = 0;
    }

    private static boolean holdingCopier(Player player) {
        return player.getMainHandItem().is(ModItems.SETTINGS_COPIER.get())
                || player.getOffhandItem().is(ModItems.SETTINGS_COPIER.get());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        long now = Util.getMillis();
        if (preview.count > 0 && now >= previewEnd) {
            preview.count = 0;
        }
        if (preview.count + source.count + result.count == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !holdingCopier(minecraft.player)) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        // The gold preview fades out over its last moments, so the end of the confirm window is visible.
        float previewAlpha = Math.min(1.0F, (previewEnd - now) / (float) FADE_MS);

        // A side in more than one group is drawn once, in the color that says the most: the gold preview first,
        // then the white copied-from side, then green/blue (so the copied side doesn't show green and white at once).
        // Faint copy drawn through walls. Drawn directly with the depth test off (not through a render type), so
        // rendering mods that batch or replace render types (Sodium, Iris, ImmediatelyFast...) can't put walls back
        // in front of it.
        BufferBuilder through = Tesselator.getInstance().getBuilder();
        through.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        draw(poseStack, through, camera, result, 0.35F, preview, source);
        draw(poseStack, through, camera, source, 0.35F, preview, null);
        draw(poseStack, through, camera, preview, 0.35F * previewAlpha, null, null);
        BufferBuilder.RenderedBuffer mesh = through.end();
        if (mesh.isEmpty()) {
            mesh.release();
        } else {
            drawThroughWalls(minecraft, () -> BufferUploader.drawWithShader(mesh));
        }

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        draw(poseStack, lines, camera, result, 1.0F, preview, source);
        draw(poseStack, lines, camera, source, 1.0F, preview, null);
        draw(poseStack, lines, camera, preview, previewAlpha, null, null);
        buffers.endBatch(RenderType.lines());
    }

    /** Runs the draw with blending on and the depth test, depth writes and culling off; puts everything back after. */
    private static void drawThroughWalls(Minecraft minecraft, Runnable drawCall) {
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        // Same line width vanilla uses for its line render types.
        RenderSystem.lineWidth(Math.max(2.5F, minecraft.getWindow().getWidth() / 1920.0F * 2.5F));
        drawCall.run();
        RenderSystem.lineWidth(1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** True if the group has this side (only checked for the small preview and source groups). */
    private static boolean has(Group group, long position, byte side) {
        if (group == null) {
            return false;
        }
        for (int i = 0; i < group.count; i++) {
            if (group.positions[i] == position && group.sides[i] == side) {
                return true;
            }
        }
        return false;
    }

    private static void draw(PoseStack poseStack, VertexConsumer lines, Vec3 camera, Group group, float alpha,
                             Group skipA, Group skipB) {
        if (group.count == 0 || alpha <= 0) {
            return;
        }
        float r = ((group.color >> 16) & 0xFF) / 255.0F;
        float g = ((group.color >> 8) & 0xFF) / 255.0F;
        float b = (group.color & 0xFF) / 255.0F;
        // Checking a big preview for every result side would be slow; only skip when the preview is small.
        Group skipPreview = skipA != null && skipA.count <= 64 ? skipA : null;
        for (int i = 0; i < group.count; i++) {
            long packed = group.positions[i];
            if (has(skipPreview, packed, group.sides[i]) || has(skipB, packed, group.sides[i])) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(BlockPos.getX(packed) - camera.x, BlockPos.getY(packed) - camera.y,
                    BlockPos.getZ(packed) - camera.z);
            LevelRenderer.renderLineBox(poseStack, lines, ARM_BOXES[group.sides[i]], r, g, b, alpha);
            LevelRenderer.renderLineBox(poseStack, lines, FACE_BOXES[group.sides[i]], r, g, b, alpha);
            poseStack.popPose();
        }
    }
}
