package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.FacadeMassApply;
import com.daisychain.block.FacadeOutline;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.item.FacadeItem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Step 131: while the player sneaks with facades in the main hand and looks at a pipe, the pipes a sneak + right-click
 * would cover (step 130, FacadeMassApply) get one merged outline (Brandon's option B: outer edges of each group only),
 * plus a line under the crosshair: "Cover 5 pipes · 5 facades", or how far the stack reaches when it runs short.
 * <p>
 * Uses the same walk as the server (FacadeMassApply.selection) on the client's copy of the world, nearest first, and
 * outlines only as many as the stack covers (creative: up to the server limit). Claims are only known to the server, so
 * a claimed pipe can still show here; the click skips it.
 * <p>
 * Cost: nothing unless sneaking with facades. Then the walk and the outline are worked out again only when the looked-at
 * pipe, the facade, the stack size or a cover in the world changes, and otherwise every {@link #REFRESH_TICKS} ticks
 * (pipes added or unlinked). Each frame only replays the stored edges: no allocations.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class FacadeMassPreview {
    private static final int REFRESH_TICKS = 10;
    private static final int DEFAULT_MAX = 64;
    private static final int COLOR = 0x7FC8FF;
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID,
            "facade_mass_preview");

    /** Outline edges (FacadeOutline format) of the current preview; empty = nothing shown. */
    private static int[] edges = new int[0];
    @Nullable
    private static Component line;

    // What the current preview was worked out for.
    @Nullable
    private static ClientLevel shownLevel;
    private static long shownPos = Long.MIN_VALUE;
    @Nullable
    private static BlockState shownCover;
    private static int shownCount = -1;
    private static boolean shownCreative;
    private static int shownChanges;
    private static int refreshIn;

    private FacadeMassPreview() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.screen != null || player.isSpectator()
                || !player.isSecondaryUseActive()) {
            clear();
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof FacadeItem)
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            clear();
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (!(level.getBlockState(pos).getBlock() instanceof FlowPipeBlock)) {
            clear();
            return;
        }
        BlockState cover = FacadeItem.coverOf(stack);
        boolean creative = player.getAbilities().instabuild;
        int changes = PipeDataBlockEntity.clientCoverChanges();
        boolean same = level == shownLevel && pos.asLong() == shownPos && cover == shownCover
                && stack.getCount() == shownCount && creative == shownCreative && changes == shownChanges;
        if (same && --refreshIn > 0) {
            return;
        }
        refreshIn = REFRESH_TICKS;
        shownLevel = level;
        shownPos = pos.asLong();
        shownCover = cover;
        shownCount = stack.getCount();
        shownCreative = creative;
        shownChanges = changes;
        rebuild(level, pos, cover, stack.getCount(), creative);
    }

    private static void rebuild(ClientLevel level, BlockPos pos, @Nullable BlockState cover, int count,
                                boolean creative) {
        if (!FacadeItem.canCover(cover)) {
            edges = new int[0];
            line = Component.translatable("message.daisychain.facade_invalid");
            return;
        }
        BlockState current = currentCover(level, pos);
        if (current == cover) {
            edges = new int[0];
            line = Component.translatable("message.daisychain.facade_same");
            return;
        }
        List<BlockPos> selection = FacadeMassApply.selection(level, pos, cover, maxPerClick());
        int linked = selection.size();
        int shown = creative ? linked : Math.min(linked, count);
        LongOpenHashSet set = new LongOpenHashSet(shown);
        for (int i = 0; i < shown; i++) {
            set.add(selection.get(i).asLong());
        }
        edges = FacadeOutline.edges(set);
        String kind = current == null ? "cover" : "swap";
        if (shown == 0) {
            line = null;
        } else if (creative) {
            line = Component.translatable("hud.daisychain.facade_mass." + kind + "_creative" + (shown == 1 ? "_one" : ""),
                    shown);
        } else if (shown < linked) {
            line = Component.translatable("hud.daisychain.facade_mass." + kind + "_short", shown, linked);
        } else {
            // One facade per pipe: both numbers are the same.
            line = Component.translatable("hud.daisychain.facade_mass." + kind + (shown == 1 ? "_one" : ""), shown,
                    shown);
        }
    }

    @Nullable
    private static BlockState currentCover(ClientLevel level, BlockPos pos) {
        PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, level.getBlockState(pos));
        return data == null ? null : data.cover();
    }

    /** The server's limit (server configs are sent to the client on join); 64 if not there yet. */
    private static int maxPerClick() {
        try {
            return DaisychainConfig.MAX_FACADE_MASS_APPLY.get();
        } catch (IllegalStateException e) {
            return DEFAULT_MAX;
        }
    }

    private static void clear() {
        if (shownLevel != null || edges.length > 0 || line != null) {
            edges = new int[0];
            line = null;
            shownLevel = null;
            shownPos = Long.MIN_VALUE;
            shownCover = null;
            shownCount = -1;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || edges.length == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        // Faint copy through walls (pipes inside walls are where facades go), like the Settings Copier's outlines.
        BufferBuilder through = Tesselator.getInstance().begin(VertexFormat.Mode.LINES,
                DefaultVertexFormat.POSITION_COLOR_NORMAL);
        draw(poseStack, through, camera, 0.35F);
        MeshData mesh = through.build();
        if (mesh != null) {
            PasteHighlights.drawThroughWalls(minecraft, () -> BufferUploader.drawWithShader(mesh));
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        draw(poseStack, buffers.getBuffer(RenderType.lines()), camera, 1.0F);
        buffers.endBatch(RenderType.lines());
    }

    private static void draw(PoseStack poseStack, VertexConsumer lines, Vec3 camera, float alpha) {
        PoseStack.Pose pose = poseStack.last();
        float r = ((COLOR >> 16) & 0xFF) / 255.0F;
        float g = ((COLOR >> 8) & 0xFF) / 255.0F;
        float b = (COLOR & 0xFF) / 255.0F;
        int[] e = edges;
        for (int i = 0; i + FacadeOutline.INTS_PER_EDGE <= e.length; i += FacadeOutline.INTS_PER_EDGE) {
            float x = (float) (e[i] - camera.x);
            float y = (float) (e[i + 1] - camera.y);
            float z = (float) (e[i + 2] - camera.z);
            int axis = e[i + 3];
            float nx = axis == 0 ? 1.0F : 0.0F;
            float ny = axis == 1 ? 1.0F : 0.0F;
            float nz = axis == 2 ? 1.0F : 0.0F;
            lines.addVertex(pose, x, y, z).setColor(r, g, b, alpha).setNormal(pose, nx, ny, nz);
            lines.addVertex(pose, x + nx, y + ny, z + nz).setColor(r, g, b, alpha).setNormal(pose, nx, ny, nz);
        }
    }

    /** The count line, just under the crosshair. */
    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, FacadeMassPreview::renderLine);
    }

    private static void renderLine(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Component text = line;
        Minecraft minecraft = Minecraft.getInstance();
        if (text == null || minecraft.options.hideGui) {
            return;
        }
        Font font = minecraft.font;
        int x = (graphics.guiWidth() - font.width(text)) / 2;
        int y = graphics.guiHeight() / 2 + 12;
        graphics.drawString(font, text, x, y, 0xFFFFFF, true);
    }
}
