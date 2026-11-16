package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.CoverShape;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.item.FacadeItem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.pipeline.VertexConsumerWrapper;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Step 129c: the faded covers of the wrench see-through view ({@link WrenchSeeThrough}), drawn from one buffer of our
 * own after the translucent chunk layer, with the depth test on and depth writing off. Faded faces then never hide each
 * other, which is what made the chunk-mesh version (129) flicker. The pipes themselves stay in the chunk meshes.
 * <p>
 * Cost: only while a wrench is held. The buffer is built on the client thread when the wrench comes out and again when
 * a covered pipe appears, changes or goes away (at most every {@link #REBUILD_TICKS} ticks); each frame is one draw
 * call with no allocations. Light changes while the wrench is held aren't picked up until the next rebuild. F3 shows
 * the pipe count and the last build time (for measuring).
 * <p>
 * Step 129d: the faces of blocks touching an opaque-covered pipe (the floor under it, a wall beside it) were either
 * missing (grass and other non-solid-layer blocks: the cover hides them, hidesNeighborFace) or dark (dirt and other
 * solid blocks: lit from the covered spot, where the cover blocks all light). With the cover faded both showed. The
 * overlay now draws those faces first, lit with the brightest light around the pipe and lifted 1/512 of a block toward
 * the pipe so they sit in front of the dark copy in the chunk mesh; the faded covers go on top. Those neighbor blocks
 * are checked every {@link #VERIFY_TICKS} ticks, so breaking or placing one next to a covered pipe rebuilds.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WrenchCoverOverlay {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Shortest gap between two rebuilds. Mass facade apply changes many pipes at once: one rebuild, not one each. */
    private static final int REBUILD_TICKS = 5;
    /** Step 129d: how often the blocks next to opaque-covered pipes are checked for changes. */
    private static final int VERIFY_TICKS = 10;
    /** Step 129d: how far a redrawn neighbor face is moved toward the pipe (in front of the chunk mesh's dark copy). */
    private static final float FACE_LIFT = 1.0F / 512.0F;
    private static final Direction[] DIRECTIONS = Direction.values();

    private static VertexBuffer buffer;
    /** Step F57: 1.20.1's BufferBuilder owns native memory with no way to free it, so one is kept and reused. */
    private static BufferBuilder builder;
    /** Step F66: builder seen through {@link KeepVertexAlpha}; the pipes' faded covers are written through this. */
    private static VertexConsumer coverWriter;
    /** Where the buffer's vertices are measured from (the player's block when it was built), for float precision. */
    private static final BlockPos.MutableBlockPos ORIGIN = new BlockPos.MutableBlockPos();
    private static ClientLevel builtLevel;
    private static boolean dirty;
    private static int seenChanges;
    private static int cooldown;
    private static int lastPipes;
    private static long lastBuildMicros;
    private static boolean warned;
    /** Step 129d: the neighbors of opaque-covered pipes at the last build (position, state), to spot changes. */
    private static long[] neighborPositions = new long[64];
    private static BlockState[] neighborStates = new BlockState[64];
    private static int neighborCount;
    private static int verifyIn;

    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final PoseStack POSE = new PoseStack();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final BlockPos.MutableBlockPos NEXT = new BlockPos.MutableBlockPos();
    private static final float[] SHADE = new float[4];
    private static final int[] LIGHT = new int[4];

    private WrenchCoverOverlay() {
    }

    /** Wrench came out: build on the next tick. */
    static void start() {
        dirty = true;
        cooldown = 0;
    }

    /** Wrench went away, or logging out: free the buffer. */
    static void stop() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
        builtLevel = null;
        dirty = false;
        lastPipes = 0;
        neighborCount = 0;
        java.util.Arrays.fill(neighborStates, null);
    }

    /** Every client tick while a wrench is held (WrenchSeeThrough). */
    static void tick(Minecraft minecraft, ClientLevel level) {
        int changes = PipeDataBlockEntity.clientCoverChanges();
        if (changes != seenChanges || level != builtLevel) {
            seenChanges = changes;
            dirty = true;
        }
        if (--verifyIn <= 0) {
            verifyIn = VERIFY_TICKS;
            if (!dirty && neighborsChanged(level)) {
                dirty = true;
            }
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (dirty && minecraft.player != null) {
            dirty = false;
            cooldown = REBUILD_TICKS;
            rebuild(minecraft, level);
        }
    }

    private static void rebuild(Minecraft minecraft, ClientLevel level) {
        long start = System.nanoTime();
        builtLevel = level;
        ORIGIN.set(minecraft.player.blockPosition());
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        ModelBlockRenderer renderer = dispatcher.getModelRenderer();
        RenderType translucent = RenderType.translucent();
        ChunkPos center = minecraft.player.chunkPosition();
        int radius = minecraft.options.getEffectiveRenderDistance() + 1;
        int pipes = 0;
        neighborCount = 0;
        List<PipeDataBlockEntity> covered = new ArrayList<>();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof PipeDataBlockEntity data && data.hasCover() && !data.isRemoved()) {
                        covered.add(data);
                    }
                }
            }
        }
        if (builder == null) {
            builder = new BufferBuilder(translucent.bufferSize());
            coverWriter = new KeepVertexAlpha(builder);
        }
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        // Step 129d: neighbor faces first, so the faded covers are drawn over them.
        for (int i = 0; i < covered.size(); i++) {
            addNeighborFaces(minecraft, level, dispatcher, builder, covered.get(i).getBlockPos());
        }
        for (int i = 0; i < covered.size(); i++) {
            if (addPipe(level, dispatcher, renderer, covered.get(i), translucent)) {
                pipes++;
            }
        }
        BufferBuilder.RenderedBuffer mesh = builder.endOrDiscardIfEmpty();
        if (mesh == null) {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
        } else {
            if (buffer == null) {
                buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
            buffer.bind();
            buffer.upload(mesh); // releases the mesh
            VertexBuffer.unbind();
        }
        lastPipes = pipes;
        lastBuildMicros = (System.nanoTime() - start) / 1000L;
    }

    /** Adds one covered pipe's faded cover, lit and culled like a chunk mesh would. False if it was skipped. */
    private static boolean addPipe(ClientLevel level, BlockRenderDispatcher dispatcher, ModelBlockRenderer renderer,
                                   PipeDataBlockEntity data, RenderType translucent) {
        BlockPos pos = data.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BasePipeBlock)) {
            return false;
        }
        BakedModel model = dispatcher.getBlockModel(state);
        ModelData modelData = model.getModelData(level, pos, state, data.getModelData());
        if (modelData.get(PipeBakedModel.SEE_THROUGH) == null) {
            return false; // not a covered pipe as far as the model is concerned
        }
        modelData = modelData.derive().with(PipeBakedModel.OVERLAY, Boolean.TRUE).build();
        POSE.pushPose();
        POSE.translate(pos.getX() - ORIGIN.getX(), pos.getY() - ORIGIN.getY(), pos.getZ() - ORIGIN.getZ());
        try {
            if (FacadeItem.isTransparent(data.cover())) {
                // Step F61 (135): the hologram glows evenly: flat light, so its baked full-bright wins on every face.
                // (NeoForge says this through the model's AO hook; Forge 1.20.1's hook gets no model data.) Belt and
                // braces: Forge's AO path already lights quads without AO flat, and glowingQuad turns AO off.
                renderer.tesselateWithoutAO(level, model, state, pos, POSE, coverWriter, true, RANDOM, state.getSeed(pos),
                        OverlayTexture.NO_OVERLAY, modelData, translucent);
            } else {
                renderer.tesselateBlock(level, model, state, pos, POSE, coverWriter, true, RANDOM, state.getSeed(pos),
                        OverlayTexture.NO_OVERLAY, modelData, translucent);
            }
            return true;
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Daisychain: couldn't draw the see-through cover at {}; skipping it", pos, e);
            }
            return false;
        } finally {
            POSE.popPose();
        }
    }

    /**
     * Step 129d: for a pipe with an opaque cover, draws the face of each block touching it (the face toward the pipe),
     * lit as if the cover let light through. Records every neighbor's state so changes trigger a rebuild.
     */
    private static void addNeighborFaces(Minecraft minecraft, ClientLevel level, BlockRenderDispatcher dispatcher,
                                         BufferBuilder builder, BlockPos pos) {
        BlockState pipe = level.getBlockState(pos);
        if (!(pipe.getBlock() instanceof BasePipeBlock) || pipe.getValue(BasePipeBlock.COVER) != CoverShape.SOLID) {
            return;
        }
        int floor = PipeBakedModel.lightFloor(level, pos);
        java.util.Arrays.fill(LIGHT, LightTexture.pack(floor & 15, floor >> 4));
        for (Direction direction : DIRECTIONS) {
            NEXT.setWithOffset(pos, direction);
            if (!level.isLoaded(NEXT)) {
                continue;
            }
            BlockState neighbor = level.getBlockState(NEXT);
            remember(NEXT.asLong(), neighbor);
            if (neighbor.isAir() || neighbor.getBlock() instanceof BasePipeBlock
                    || neighbor.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            Direction face = direction.getOpposite(); // the neighbor's face that looks at the pipe
            try {
                BakedModel model = dispatcher.getBlockModel(neighbor);
                ModelData data = model.getModelData(level, NEXT, neighbor, ModelData.EMPTY);
                long seed = neighbor.getSeed(NEXT);
                RANDOM.setSeed(seed);
                POSE.pushPose();
                POSE.translate(NEXT.getX() - ORIGIN.getX() + face.getStepX() * FACE_LIFT,
                        NEXT.getY() - ORIGIN.getY() + face.getStepY() * FACE_LIFT,
                        NEXT.getZ() - ORIGIN.getZ() + face.getStepZ() * FACE_LIFT);
                for (RenderType layer : model.getRenderTypes(neighbor, RANDOM, data)) {
                    RANDOM.setSeed(seed);
                    List<BakedQuad> quads = model.getQuads(neighbor, face, RANDOM, data, layer);
                    for (int i = 0; i < quads.size(); i++) {
                        BakedQuad quad = quads.get(i);
                        float r = 1.0F;
                        float g = 1.0F;
                        float b = 1.0F;
                        if (quad.isTinted()) {
                            int color = minecraft.getBlockColors().getColor(neighbor, level, NEXT, quad.getTintIndex());
                            r = (color >> 16 & 255) / 255.0F;
                            g = (color >> 8 & 255) / 255.0F;
                            b = (color & 255) / 255.0F;
                        }
                        java.util.Arrays.fill(SHADE, level.getShade(quad.getDirection(), quad.isShade()));
                        builder.putBulkData(POSE.last(), quad, SHADE, r, g, b, 1.0F, LIGHT,
                                OverlayTexture.NO_OVERLAY, true);
                    }
                }
                POSE.popPose();
            } catch (RuntimeException e) {
                POSE.setIdentity();
                if (!warned) {
                    warned = true;
                    LOGGER.warn("Daisychain: couldn't redraw the block face at {}; skipping it", NEXT, e);
                }
            }
        }
    }

    private static void remember(long position, BlockState state) {
        if (neighborCount == neighborPositions.length) {
            neighborPositions = java.util.Arrays.copyOf(neighborPositions, neighborCount * 2);
            neighborStates = java.util.Arrays.copyOf(neighborStates, neighborCount * 2);
        }
        neighborPositions[neighborCount] = position;
        neighborStates[neighborCount] = state;
        neighborCount++;
    }

    /** Step 129d: true if a block next to an opaque-covered pipe changed (or unloaded) since the last build. */
    private static boolean neighborsChanged(ClientLevel level) {
        for (int i = 0; i < neighborCount; i++) {
            NEXT.set(neighborPositions[i]);
            if (!level.isLoaded(NEXT) || level.getBlockState(NEXT) != neighborStates[i]) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || buffer == null
                || !WrenchSeeThrough.isActive()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        MODEL_VIEW.set(event.getPoseStack().last().pose()).translate((float) (ORIGIN.getX() - camera.x),
                (float) (ORIGIN.getY() - camera.y), (float) (ORIGIN.getZ() - camera.z));
        RenderType type = RenderType.translucent();
        type.setupRenderState();
        RenderSystem.depthMask(false); // the point of this class: faded faces never hide each other
        draw(event);
        RenderSystem.depthMask(true);
        type.clearRenderState();
        // Step F61 (134a): a second pass that writes only depth, after all faces are blended. Without it clouds, rain,
        // snow and particles (drawn later) painted over the faded covers wherever sky was behind them. The cutout
        // shader skips pixels under 10% alpha, so the clear middle of a faded glass cover still shows what's behind.
        RenderType depth = RenderType.cutout();
        depth.setupRenderState();
        RenderSystem.colorMask(false, false, false, false);
        draw(event);
        RenderSystem.colorMask(true, true, true, true);
        depth.clearRenderState();
    }

    private static void draw(RenderLevelStageEvent event) {
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            return;
        }
        if (shader.CHUNK_OFFSET != null) {
            shader.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);
        }
        buffer.bind();
        buffer.drawWithShader(MODEL_VIEW, event.getProjectionMatrix(), shader);
        VertexBuffer.unbind();
    }

    /** F3: how many covers the overlay holds and how long the last build took. */
    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (WrenchSeeThrough.isActive() && Minecraft.getInstance().options.renderDebug) {
            event.getRight().add("Daisychain see-through: " + lastPipes + " covers, built in "
                    + String.format("%.2f", lastBuildMicros / 1000.0) + " ms");
        }
    }

    /**
     * Step F66: Embeddium-based renderers (Embeddium, Xenon) replace BufferBuilder's 9-argument putBulkData,
     * the one vanilla's block renderer calls for every quad, with a fast version that keeps each vertex's color but
     * not its alpha. The faded covers keep their fade in the vertex alpha (PipeBakedModel.fadedQuad), so with those
     * mods they came out opaque (and dark). This passes each quad on through Forge's 10-argument putBulkData instead,
     * which those mods leave alone: it multiplies the given alpha (1) by the vertex alpha and merges baked light, exactly
     * what the 9-argument one does without them. Nothing changes without such a mod.
     */
    private static final class KeepVertexAlpha extends VertexConsumerWrapper {
        KeepVertexAlpha(VertexConsumer parent) {
            super(parent);
        }

        @Override
        public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green,
                                float blue, int[] lightmap, int overlay, boolean readExistingColor) {
            parent.putBulkData(pose, quad, brightness, red, green, blue, 1.0F, lightmap, overlay, readExistingColor);
        }
    }
}
