package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.item.SettingsCopierItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While the player holds a wrench, sneaks (step 66) and looks at a pipe, shows the ghost arm ("hologram") of the one side being looked at
 * (step 34b, zerobit): light blue for an open side, red for a side set to Off. Every armless side can still be clicked
 * (the click shape includes all ghost arms for wrench holders, see FlowPipeBlock.getShape); only the looked-at one is
 * drawn, as a soft see-through box with a faint edge (step 60). The black selection outline is redrawn as the pipe itself, without any ghost arms (step 58: the hologram
 * already shows the ghost, and a black copy inside it looked like stray lines).
 * Only the one pipe under the crosshair. Client only; nothing is sent to the server.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class WrenchGhostRenderer {
    private static final float RED = 0.35F;
    private static final float GREEN = 0.85F;
    private static final float BLUE = 1.0F;
    /** Set to Off: red. */
    private static final float OFF_RED = 1.0F;
    private static final float OFF_GREEN = 0.35F;
    private static final float OFF_BLUE = 0.3F;
    private static final float FILL_ALPHA = 0.18F;
    private static final float LINE_ALPHA = 0.6F;
    /**
     * Step 61: the hologram is the arm's exact size, a hair smaller (0.03 px a side) so its faces never flicker
     * against the block next to it. (It used to be 0.16 px bigger a side, which made it look larger than the pipe.)
     */
    private static final double GROW = -0.002;
    private static final AABB[] GHOST_BOXES = new AABB[6];
    /** Step 55: the outline shown when the crosshair is on the middle of a pipe while holding the Settings Copier. */
    private static final VoxelShape CORE_SHAPE = Shapes.create(FlowPipeBlock.coreBounds());

    static {
        for (int i = 0; i < 6; i++) {
            GHOST_BOXES[i] = FlowPipeBlock.armBounds(i).inflate(GROW);
        }
    }

    private WrenchGhostRenderer() {
    }

    /** The armless side the player is looking at on this pipe (as a bit), or 0. */
    private static int lookedAtGhost(Minecraft minecraft, BlockHitResult hit, BlockState state) {
        BlockPos pos = hit.getBlockPos();
        int ghosts = FlowPipeBlock.ghostArmMask(minecraft.level, pos, state);
        int bit = 1 << FlowPipeBlock.targetedSide(state, pos, hit).get3DDataValue();
        return ghosts & bit;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.hitResult == null
                || minecraft.hitResult.getType() != HitResult.Type.BLOCK
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || !FlowPipeBlock.isHoldingWrench(minecraft.player)
                || !minecraft.player.isSecondaryUseActive() // step 66: only while sneaking
                || FlowPipeBlock.clientGhostsHidden()) { // step 68: hidden while the click-through key is held
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        int ghost = lookedAtGhost(minecraft, hit, state);
        if (ghost == 0) {
            return;
        }
        int side = Integer.numberOfTrailingZeros(ghost);
        boolean off = (FlowPipeBlock.offMask(minecraft.level, pos, state) & ghost) != 0;

        // Step 60 (zerobit chose "glass + faint outline"): a soft see-through fill with a fainter edge.
        float r = off ? OFF_RED : RED;
        float g = off ? OFF_GREEN : GREEN;
        float b = off ? OFF_BLUE : BLUE;
        AABB box = GHOST_BOXES[side];
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
        LevelRenderer.addChainedFilledBoxVertices(poseStack, fill, box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ, r, g, b, FILL_ALPHA);
        buffers.endBatch(RenderType.debugFilledBox());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, lines, box, r, g, b, LINE_ALPHA);
        buffers.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    /**
     * The black selection outline for a pipe while holding a wrench or the Settings Copier.
     * <ul>
     *   <li>Wrench (step 58): the pipe as it really is, never any ghost arms. Vanilla would outline every ghost arm,
     *   because they're all in the click shape; the looked-at one is shown by the hologram instead. The wrench has no
     *   "middle": a click there changes the side you're facing, which the hologram or the arm already shows.</li>
     *   <li>Settings Copier with the crosshair on the middle of the pipe (step 55, zerobit): only the middle cube is
     *   outlined, so it's clear a sneak-click there pastes to the whole network. Anywhere else: vanilla's outline.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        boolean wrench = FlowPipeBlock.isHoldingWrench(minecraft.player);
        if (!wrench && !holdingCopier(minecraft.player)) {
            return;
        }
        BlockHitResult hit = event.getTarget();
        BlockPos pos = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return;
        }
        VoxelShape shape;
        if (wrench) {
            shape = FlowPipeBlock.shapeFor(state, 0);
        } else if (FlowPipeBlock.isCoreHit(state, pos, hit.getLocation())) {
            shape = CORE_SHAPE;
        } else {
            return;
        }
        event.setCanceled(true);
        Vec3 camera = event.getCamera().getPosition();
        double ox = pos.getX() - camera.x;
        double oy = pos.getY() - camera.y;
        double oz = pos.getZ() - camera.z;
        PoseStack.Pose pose = event.getPoseStack().last();
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        // Same look as vanilla's outline: black at 40% along every edge of the shape.
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float dx = (float) (x2 - x1);
            float dy = (float) (y2 - y1);
            float dz = (float) (z2 - z1);
            float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= length;
            dy /= length;
            dz /= length;
            lines.addVertex(pose, (float) (x1 + ox), (float) (y1 + oy), (float) (z1 + oz))
                    .setColor(0.0F, 0.0F, 0.0F, 0.4F).setNormal(pose, dx, dy, dz);
            lines.addVertex(pose, (float) (x2 + ox), (float) (y2 + oy), (float) (z2 + oz))
                    .setColor(0.0F, 0.0F, 0.0F, 0.4F).setNormal(pose, dx, dy, dz);
        });
    }

    /** Reads the hologram setting once per tick for FlowPipeBlock.getShape (toggled with the Bloom Wrench). */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // Step 72: the client setting turns them off for good.
        FlowPipeBlock.setClientGhostsHidden(!DaisychainClientConfig.wrenchHolograms());
    }

    private static boolean holdingCopier(Player player) {
        return player.getMainHandItem().getItem() instanceof SettingsCopierItem
                || player.getOffhandItem().getItem() instanceof SettingsCopierItem;
    }
}
