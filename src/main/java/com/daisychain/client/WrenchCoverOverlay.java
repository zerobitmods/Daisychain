package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
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
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class WrenchCoverOverlay {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Shortest gap between two rebuilds. Mass facade apply changes many pipes at once: one rebuild, not one each. */
    private static final int REBUILD_TICKS = 5;

    private static VertexBuffer buffer;
    /** Where the buffer's vertices are measured from (the player's block when it was built), for float precision. */
    private static final BlockPos.MutableBlockPos ORIGIN = new BlockPos.MutableBlockPos();
    private static ClientLevel builtLevel;
    private static boolean dirty;
    private static int seenChanges;
    private static int cooldown;
    private static int lastPipes;
    private static long lastBuildMicros;
    private static boolean warned;

    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final PoseStack POSE = new PoseStack();
    private static final RandomSource RANDOM = RandomSource.create();

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
    }

    /** Every client tick while a wrench is held (WrenchSeeThrough). */
    static void tick(Minecraft minecraft, ClientLevel level) {
        int changes = PipeDataBlockEntity.clientCoverChanges();
        if (changes != seenChanges || level != builtLevel) {
            seenChanges = changes;
            dirty = true;
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
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(translucent.bufferSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            for (int x = center.x - radius; x <= center.x + radius; x++) {
                for (int z = center.z - radius; z <= center.z + radius; z++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (blockEntity instanceof PipeDataBlockEntity data && data.hasCover() && !data.isRemoved()
                                && addPipe(level, dispatcher, renderer, builder, data, translucent)) {
                            pipes++;
                        }
                    }
                }
            }
            MeshData mesh = builder.build();
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
                buffer.upload(mesh); // closes the mesh
                VertexBuffer.unbind();
            }
        }
        lastPipes = pipes;
        lastBuildMicros = (System.nanoTime() - start) / 1000L;
    }

    /** Adds one covered pipe's faded cover, lit and culled like a chunk mesh would. False if it was skipped. */
    private static boolean addPipe(ClientLevel level, BlockRenderDispatcher dispatcher, ModelBlockRenderer renderer,
                                   BufferBuilder builder, PipeDataBlockEntity data, RenderType translucent) {
        BlockPos pos = data.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
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
            renderer.tesselateBlock(level, model, state, pos, POSE, builder, true, RANDOM, state.getSeed(pos),
                    OverlayTexture.NO_OVERLAY, modelData, translucent);
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

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || buffer == null
                || !WrenchSeeThrough.isActive()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        MODEL_VIEW.set(event.getModelViewMatrix()).translate((float) (ORIGIN.getX() - camera.x),
                (float) (ORIGIN.getY() - camera.y), (float) (ORIGIN.getZ() - camera.z));
        RenderType type = RenderType.translucent();
        type.setupRenderState();
        RenderSystem.depthMask(false); // the point of this class: faded faces never hide each other
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            if (shader.CHUNK_OFFSET != null) {
                shader.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);
            }
            buffer.bind();
            buffer.drawWithShader(MODEL_VIEW, event.getProjectionMatrix(), shader);
            VertexBuffer.unbind();
        }
        RenderSystem.depthMask(true);
        type.clearRenderState();
    }

    /** F3: how many covers the overlay holds and how long the last build took. */
    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (WrenchSeeThrough.isActive() && Minecraft.getInstance().getDebugOverlay().showDebugScreen()) {
            event.getRight().add("Daisychain see-through: " + lastPipes + " covers, built in "
                    + String.format("%.2f", lastBuildMicros / 1000.0) + " ms");
        }
    }
}
