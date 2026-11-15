package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Facades step 4 (step 129): while the player holds a wrench in the main hand, covered pipes are drawn see-through
 * (the pipe, plus its cover faded; see {@link PipeBakedModel}). Step 129c: the faded covers are drawn by
 * {@link WrenchCoverOverlay}; the chunk meshes hold only the pipes. The look is baked into chunk meshes, so nothing is done
 * per frame: when the wrench goes into or out of the hand, the chunk sections holding covered pipes are re-meshed.
 * <ul>
 *   <li>Wrench in: every loaded chunk within render distance is checked for covered pipes (block entities only, once
 *   per switch) and their sections re-meshed.</li>
 *   <li>Wrench out: the sections that were meshed see-through (recorded by the model while meshing) are re-meshed.</li>
 * </ul>
 * Covered pipes that load or re-mesh for other reasons pick up the current look by themselves. Client only.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class WrenchSeeThrough {
    /** True while the local player holds a wrench. Read by chunk meshing threads. */
    private static volatile boolean active;
    /** Bumped each time the wrench goes away, so PipeBakedModel drops its see-through quads. */
    private static volatile int generation;
    /** Sections (SectionPos.asLong) meshed with see-through covers since the wrench came out. */
    private static final Set<Long> SEE_THROUGH_SECTIONS = ConcurrentHashMap.newKeySet();

    private WrenchSeeThrough() {
    }

    /**
     * Meshing threads, for a covered pipe: whether to draw it see-through. The section is recorded before the flag is
     * read a second time, so a mesh that races the wrench going away is always re-meshed afterwards.
     */
    static boolean drawSeeThrough(BlockPos pos) {
        if (!active) {
            return false;
        }
        SEE_THROUGH_SECTIONS.add(SectionPos.asLong(pos));
        return active;
    }

    /** Step 129c: true while the local player holds a wrench (client thread). */
    static boolean isActive() {
        return active;
    }

    static int generation() {
        return generation;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        boolean holding = level != null && minecraft.player != null && FlowPipeBlock.isHoldingWrench(minecraft.player);
        if (holding == active) {
            if (active) {
                WrenchCoverOverlay.tick(minecraft, level); // step 129c
            }
            return;
        }
        active = holding;
        if (level == null) {
            SEE_THROUGH_SECTIONS.clear();
            generation++;
            WrenchCoverOverlay.stop();
            return;
        }
        if (holding) {
            remeshCoveredPipes(minecraft, level);
            WrenchCoverOverlay.start();
            WrenchCoverOverlay.tick(minecraft, level);
        } else {
            WrenchCoverOverlay.stop();
            generation++;
            remeshRecorded(minecraft.levelRenderer);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
        SEE_THROUGH_SECTIONS.clear();
        generation++;
        WrenchCoverOverlay.stop();
    }

    /** Wrench in: re-mesh every section within render distance that has a covered pipe. */
    private static void remeshCoveredPipes(Minecraft minecraft, ClientLevel level) {
        LevelRenderer renderer = minecraft.levelRenderer;
        ChunkPos center = minecraft.player.chunkPosition();
        int radius = minecraft.options.getEffectiveRenderDistance() + 1;
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof PipeDataBlockEntity data && data.hasCover()) {
                        BlockPos pos = blockEntity.getBlockPos();
                        renderer.setSectionDirty(SectionPos.blockToSectionCoord(pos.getX()),
                                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
                    }
                }
            }
        }
    }

    /** Wrench out: re-mesh the sections that were drawn see-through. */
    private static void remeshRecorded(LevelRenderer renderer) {
        Iterator<Long> sections = SEE_THROUGH_SECTIONS.iterator();
        while (sections.hasNext()) {
            long section = sections.next();
            sections.remove();
            renderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
    }
}
