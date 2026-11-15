package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.CoverShape;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.SideState;
import com.daisychain.config.DaisychainClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.QuadTransformers;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-world pipe look (step 42, reworked in step 55), put together from part models (client/PipeModels):
 * <ul>
 *   <li>The tube is 6 px wide everywhere. Every pipe has a 6x6x6 core in the middle, and each connected side adds an
 *   arm from the core to the block edge: link (to another pipe), insert (a groove near the block) or pull (cap + tier
 *   ring). Nothing changes size when a neighbor is added, and bends don't cut into anything.</li>
 *   <li>The core only draws its faces toward sides without an arm. Each such face has its own texture whose stripe runs
 *   to the edges where arms touch it ({@link #coreMask}), so bends and junctions read as one tube.</li>
 * </ul>
 * Whether a connected side is an insert side (a block) or a link (a pipe) isn't in the block state, so it is read
 * from the neighbor when the chunk section is built (getModelData). Nothing here runs per frame, and the server never
 * sees any of it. Quads are put together once per (block state, insert sides) and kept.
 * <p>
 * Facades step 2 (step 126): a covered pipe draws its cover block's own model instead (quads, render layers, ambient
 * occlusion is the pipe's on Forge 1.20.1: its AO hook gets no model data; break particles), with that model's own data so connected-texture models still work. The cover comes from
 * the block entity's model data ({@link PipeDataBlockEntity#COVER_MODEL}); the cover model's quads are returned as they
 * are (no copy). If a modded cover model throws, the pipe is drawn plain and the error is logged once.
 * <p>
 * Step 128 (option A): a link arm whose neighbor pipe is covered gets an end cap on the block edge, so the tube doesn't
 * look hollow through a glass cover. The cap is the core's own face on that side (mask 0), moved out to the edge and
 * 1/8 pixel back in (no flicker against the glass face in the same plane). It sits in that side's culled list, so an
 * opaque cover next to it hides it (hidesNeighborFace) and only see-through covers show it.
 */
public final class PipeBakedModel extends BakedModelWrapper<BakedModel> {
    /**
     * Bits 0-5, one per side (Direction.get3DDataValue): a connected side that touches a block, not a pipe.
     * Step 128: bits 6-11: a link arm whose neighbor pipe is covered, so its end gets a cap.
     */
    static final ModelProperty<Integer> INSERT_SIDES = new ModelProperty<>();
    /** Step 128: how far an end cap sits inside the block edge (1/8 pixel), so it doesn't flicker against a glass face. */
    private static final float EDGE_INSET = 1.0F / 128.0F;
    /** Step 126: the cover model's own data (only when it has some). */
    static final ModelProperty<ModelData> COVER_DATA = new ModelProperty<>();
    /** Step 126: a broken cover model was logged already. */
    private static volatile boolean warnedAboutCover;
    static final int LINK = 0;
    static final int INSERT = 1;
    static final int PULL = 2;
    /** Core face variants per side: one bit per perpendicular side that has an arm. */
    static final int CORE_MASKS = 16;
    private static final Direction[] DIRECTIONS = Direction.values();
    /**
     * For each side, the four perpendicular sides in ascending get3DDataValue order. Bit b of a core face's mask means
     * PERPENDICULAR[side][b] has an arm; scripts/pipe_models.py uses the same order when it names core_<face>_<mask>.
     */
    private static final int[][] PERPENDICULAR = new int[6][4];

    static {
        for (int side = 0; side < 6; side++) {
            int n = 0;
            for (int other = 0; other < 6; other++) {
                if (other / 2 != side / 2) {
                    PERPENDICULAR[side][n++] = other;
                }
            }
        }
    }

    private final BakedModel node;
    /** Core faces [side][mask]. */
    private final BakedModel[][] cores;
    /** Arms [LINK / INSERT / PULL][side]. */
    private final BakedModel[][] pieces;
    /** Quads by (state id << 12 | capped sides << 6 | insert sides, top bit = glow): 7 lists, one per face direction, then the unculled ones. */
    private final ConcurrentHashMap<Long, List<BakedQuad>[]> cache = new ConcurrentHashMap<>();

    PipeBakedModel(BakedModel node, BakedModel[][] cores, BakedModel[][] pieces) {
        super(node);
        this.node = node;
        this.cores = cores;
        this.pieces = pieces;
    }

    /** Which of the four sides around this side have arms (see PERPENDICULAR). */
    static int coreMask(int side, int connected) {
        int mask = 0;
        for (int b = 0; b < 4; b++) {
            if ((connected & (1 << PERPENDICULAR[side][b])) != 0) {
                mask |= 1 << b;
            }
        }
        return mask;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return modelData;
        }
        // Step 126b: only while the pipe has its data block. When the last setting (the cover) comes off, the server
        // removes the block entity (HAS_DATA false) and Forge never clears the model data it stored for this spot,
        // so without this check the old cover stayed drawn until the chunk reloaded.
        BlockState cover = state.getValue(FlowPipeBlock.HAS_DATA) ? modelData.get(PipeDataBlockEntity.COVER_MODEL) : null;
        if (cover != null) {
            ModelData.Builder builder = ModelData.builder().with(PipeDataBlockEntity.COVER_MODEL, cover);
            try {
                ModelData coverData = coverModel(cover).getModelData(level, pos, cover, ModelData.EMPTY);
                if (coverData != null && coverData != ModelData.EMPTY) {
                    builder.with(COVER_DATA, coverData);
                }
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
            return builder.build();
        }
        int insert = 0;
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(FlowPipeBlock.propertyFor(direction)) == SideState.NONE) {
                continue;
            }
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.getBlock() instanceof FlowPipeBlock) {
                // Pipes meet at the block edge. Step 128: a covered neighbor draws only its cover, so this arm's end
                // would be open; cap it (not pull arms, they have their own cap).
                if (neighbor.getValue(FlowPipeBlock.COVER) != CoverShape.NONE
                        && state.getValue(FlowPipeBlock.propertyFor(direction)) != SideState.EXTRACT) {
                    insert |= 1 << (6 + direction.get3DDataValue());
                }
                continue;
            }
            if (state.getValue(FlowPipeBlock.propertyFor(direction)) == SideState.CONNECTED
                    && neighbor.getBlock() != state.getBlock()) {
                insert |= 1 << direction.get3DDataValue();
            }
        }
        return ModelData.builder().with(INSERT_SIDES, insert).build();
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData extraData, @Nullable RenderType renderType) {
        if (state == null || !(state.getBlock() instanceof FlowPipeBlock)) {
            return node.getQuads(state, side, rand, ModelData.EMPTY, renderType);
        }
        BlockState cover = extraData.get(PipeDataBlockEntity.COVER_MODEL);
        if (cover != null) {
            try {
                return coverModel(cover).getQuads(cover, side, rand, coverData(extraData), renderType);
            } catch (RuntimeException e) {
                warnCover(cover, e); // and draw the plain pipe below
            }
        }
        Integer stored = extraData.get(INSERT_SIDES);
        int sides = stored == null ? 0 : stored & 0xFFF; // insert sides + capped sides (step 128)
        // Step 90: the energy glow setting is part of the key (top bit), so switching it builds the glowing or plain quads.
        boolean glow = DaisychainClientConfig.energyGlow();
        long key = ((long) Block.getId(state) << 12) | sides | (glow ? Long.MIN_VALUE : 0L);
        List<BakedQuad>[] lists = cache.get(key);
        if (lists == null) {
            lists = build(state, sides & 0b111111, sides >>> 6, glow, rand, renderType);
            List<BakedQuad>[] earlier = cache.putIfAbsent(key, lists);
            if (earlier != null) {
                lists = earlier;
            }
        }
        return lists[side == null ? 6 : side.get3DDataValue()];
    }

    /** Step 126: a covered pipe is drawn in its cover's render layers (glass: cutout, ice: translucent). */
    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState cover = data.get(PipeDataBlockEntity.COVER_MODEL);
        if (cover != null) {
            try {
                return coverModel(cover).getRenderTypes(cover, rand, coverData(data));
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
        }
        return super.getRenderTypes(state, rand, data);
    }

    /** Step 126: breaking a covered pipe shows the cover's particles. */
    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        BlockState cover = data.get(PipeDataBlockEntity.COVER_MODEL);
        if (cover != null) {
            try {
                return coverModel(cover).getParticleIcon(coverData(data));
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
        }
        return super.getParticleIcon(data);
    }

    private static BakedModel coverModel(BlockState cover) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(cover);
    }

    private static ModelData coverData(ModelData data) {
        ModelData coverData = data.get(COVER_DATA);
        return coverData == null ? ModelData.EMPTY : coverData;
    }

    private static void warnCover(BlockState cover, RuntimeException e) {
        if (!warnedAboutCover) {
            warnedAboutCover = true;
            Daisychain.LOGGER.warn("Daisychain: the model of facade cover {} failed; covered pipes using it are drawn plain", cover, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<BakedQuad>[] build(BlockState state, int insert, int capped, boolean glow, RandomSource rand,
                                    @Nullable RenderType renderType) {
        int connected = 0;
        int pull = 0;
        for (Direction direction : DIRECTIONS) {
            SideState sideState = state.getValue(FlowPipeBlock.propertyFor(direction));
            int bit = 1 << direction.get3DDataValue();
            if (sideState != SideState.NONE) {
                connected |= bit;
            }
            if (sideState == SideState.EXTRACT) {
                pull |= bit;
            }
        }
        BakedModel[] parts = new BakedModel[6];
        for (int side = 0; side < 6; side++) {
            int bit = 1 << side;
            if ((connected & bit) != 0) {
                int kind = (pull & bit) != 0 ? PULL : (insert & bit) != 0 ? INSERT : LINK;
                parts[side] = pieces[kind][side];
            } else {
                parts[side] = cores[side][coreMask(side, connected)];
            }
        }
        List<BakedQuad>[] lists = new List[7];
        for (int i = 0; i < 7; i++) {
            Direction face = i < 6 ? Direction.from3DDataValue(i) : null;
            List<BakedQuad> quads = new ArrayList<>();
            for (int side = 0; side < 6; side++) {
                for (BakedQuad quad : parts[side].getQuads(state, face, rand, ModelData.EMPTY, renderType)) {
                    quads.add(glow && quad.getTintIndex() == PipeColors.BAND_TINT ? glowing(quad) : quad);
                }
            }
            int bit = 1 << i;
            if (face != null && (capped & connected & ~pull & bit) != 0) {
                // Step 128: end cap toward a covered pipe, in this side's culled list (an opaque cover hides it).
                addEndCap(quads, state, face, glow, rand, renderType);
            }
            lists[i] = List.copyOf(quads);
        }
        return lists;
    }

    /**
     * Step 128: the core's plain face on this side (mask 0: no stripe running off it), moved out to the block edge and
     * EDGE_INSET back in. Built once per cached quad set.
     */
    private void addEndCap(List<BakedQuad> quads, BlockState state, Direction face, boolean glow, RandomSource rand,
                           @Nullable RenderType renderType) {
        BakedModel core = cores[face.get3DDataValue()][0];
        for (int i = 0; i < 7; i++) {
            Direction listFace = i < 6 ? Direction.from3DDataValue(i) : null;
            for (BakedQuad quad : core.getQuads(state, listFace, rand, ModelData.EMPTY, renderType)) {
                if (quad.getDirection() == face) {
                    BakedQuad shown = glow && quad.getTintIndex() == PipeColors.BAND_TINT ? glowing(quad) : quad;
                    quads.add(movedToEdge(shown, face));
                }
            }
        }
    }

    /** A copy of the quad with its coordinate along the face's axis set to the block edge minus EDGE_INSET. */
    private static BakedQuad movedToEdge(BakedQuad quad, Direction face) {
        int axis = face.getAxis().ordinal(); // x, y, z: the first three ints of each vertex
        int edge = Float.floatToRawIntBits(face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? 1.0F - EDGE_INSET : EDGE_INSET);
        int[] moved = quad.getVertices().clone();
        int stride = moved.length / 4;
        for (int v = 0; v < 4; v++) {
            moved[v * stride + axis] = edge;
        }
        return new BakedQuad(moved, quad.getTintIndex(), face, quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    /**
     * Step 86 (zerobit): the energy stripe glows. Full light baked into the quad (so it's lit in the dark, but lights
     * nothing around it), no shading by face direction and no ambient occlusion. Done once when the quads are built.
     */
    private static BakedQuad glowing(BakedQuad quad) {
        BakedQuad lit = QuadTransformers.settingMaxEmissivity().process(quad);
        return new BakedQuad(lit.getVertices(), lit.getTintIndex(), lit.getDirection(), lit.getSprite(), false, false);
    }
}
