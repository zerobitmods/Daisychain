package com.daisychain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.RenderTypeHelper;
import net.minecraftforge.client.model.data.ModelData;

import java.util.function.IntUnaryOperator;

/**
 * Draws a block model in a screen with each quad in its own tint color. Minecraft's renderSingleBlock gives every
 * tinted quad the color of tint index 0, so a pipe's body, line and rings would all come out one color. Client only;
 * screens only (allocates a little per call).
 */
final class PipePreview {
    private static final Direction[] SIDES_AND_NONE = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null};

    private PipePreview() {
    }

    /** tint: tint index -> 0xRRGGBB. Full-bright, no overlay. */
    static void render(BlockState state, PoseStack pose, MultiBufferSource buffers, IntUnaryOperator tint) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        RandomSource random = RandomSource.create(42);
        for (RenderType layer : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            VertexConsumer consumer = buffers.getBuffer(RenderTypeHelper.getEntityRenderType(layer, false));
            for (Direction side : SIDES_AND_NONE) {
                random.setSeed(42);
                for (BakedQuad quad : model.getQuads(state, side, random, ModelData.EMPTY, layer)) {
                    int c = quad.isTinted() ? tint.applyAsInt(quad.getTintIndex()) : 0xFFFFFF;
                    consumer.putBulkData(pose.last(), quad, (c >> 16 & 0xFF) / 255F, (c >> 8 & 0xFF) / 255F,
                            (c & 0xFF) / 255F, 1F, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false);
                }
            }
        }
    }
}
