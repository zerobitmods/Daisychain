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
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.common.util.TriState;

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
 * occlusion, break particles), with that model's own data so connected-texture models still work. The cover comes from
 * the block entity's model data ({@link PipeDataBlockEntity#COVER_MODEL}); the cover model's quads are returned as they
 * are (no copy). If a modded cover model throws, the pipe is drawn plain and the error is logged once.
 * <p>
 * Step 128: a see-through cover (block state {@code cover=clear}: glass, ice, leaves) draws the pipe inside it too. The
 * model's render layers are the pipe's and the cover's together; each layer gets the pipe's quads (if the pipe draws in
 * it) followed by the cover's (if the cover draws in it). Tinted cover quads get {@link #COVER_TINT_OFFSET} added to
 * their tint index so {@link PipeColors} can tell them from the pipe's rings. Pipe faces lying on the block's outer
 * surface (pull cap ends) are moved 1/8 pixel inward so they don't flicker against the cover's face in the same plane.
 * Opaque covers ({@code cover=solid}) still draw only the cover.
 */
public final class PipeBakedModel extends BakedModelWrapper<BakedModel> {
    /** One bit per side (Direction.get3DDataValue): a connected side that touches a block, not a pipe. */
    static final ModelProperty<Integer> INSERT_SIDES = new ModelProperty<>();
    /** Step 126: the cover model's own data (only when it has some). */
    static final ModelProperty<ModelData> COVER_DATA = new ModelProperty<>();
    /** Step 126: a broken cover model was logged already. */
    private static volatile boolean warnedAboutCover;
    /**
     * Step 128: added to the tint index of a see-through cover's tinted quads (leaves, grass), so they don't collide with
     * the pipe's own tint indexes (0-14). PipeColors subtracts it again and asks the cover's block colors.
     */
    public static final int COVER_TINT_OFFSET = 1000;
    /** Step 128: how far pipe faces on the block's surface are moved inward under a see-through cover (1/8 pixel). */
    private static final float EDGE_INSET = 1.0F / 128.0F;
    /**
     * Step 128: a fixed-seed random for asking a model its render layers inside getQuads. Asking with the renderer's
     * own random would use it up (weighted models pick a variant with it) and change which variant the quads come from.
     */
    private static final ThreadLocal<RandomSource> LAYER_RANDOM = ThreadLocal.withInitial(RandomSource::create);
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
    /** Quads by (state id << 6 | insert sides, top bit = glow): 7 lists, one per face direction, then the unculled ones. */
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
        // removes the block entity (HAS_DATA false) and NeoForge never clears the model data it stored for this spot,
        // so without this check the old cover stayed drawn until the chunk reloaded.
        BlockState cover = state.getValue(FlowPipeBlock.HAS_DATA) ? modelData.get(PipeDataBlockEntity.COVER_MODEL) : null;
        ModelData.Builder builder = ModelData.builder();
        if (cover != null) {
            builder.with(PipeDataBlockEntity.COVER_MODEL, cover);
            try {
                ModelData coverData = coverModel(cover).getModelData(level, pos, cover, ModelData.EMPTY);
                if (coverData != null && coverData != ModelData.EMPTY) {
                    builder.with(COVER_DATA, coverData);
                }
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
            if (!showsPipe(state)) {
                return builder.build(); // opaque cover: only the cover is drawn
            }
        }
        int insert = 0;
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(FlowPipeBlock.propertyFor(direction)) == SideState.NONE) {
                continue;
            }
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.getBlock() instanceof FlowPipeBlock) {
                continue; // pipes meet at the block edge
            }
            if (state.getValue(FlowPipeBlock.propertyFor(direction)) == SideState.CONNECTED
                    && neighbor.getBlock() != state.getBlock()) {
                insert |= 1 << direction.get3DDataValue();
            }
        }
        return builder.with(INSERT_SIDES, insert).build();
    }

    /** Step 128: a see-through cover shows the pipe inside it (read from the block state). */
    private static boolean showsPipe(BlockState state) {
        return state.getValue(FlowPipeBlock.COVER) == CoverShape.CLEAR;
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
            if (showsPipe(state)) {
                return pipeAndCover(state, cover, side, rand, extraData, renderType);
            }
            try {
                return coverModel(cover).getQuads(cover, side, rand, coverData(extraData), renderType);
            } catch (RuntimeException e) {
                warnCover(cover, e); // and draw the plain pipe below
            }
        }
        return pipeQuads(state, side, rand, extraData, renderType);
    }

    /** The pipe's own quads (as if uncovered), built once per (block state, insert sides, glow) and kept. */
    private List<BakedQuad> pipeQuads(BlockState state, @Nullable Direction side, RandomSource rand, ModelData extraData,
                                      @Nullable RenderType renderType) {
        Integer stored = extraData.get(INSERT_SIDES);
        int insert = stored == null ? 0 : stored & 0b111111;
        // Step 90: the energy glow setting is part of the key (top bit), so switching it builds the glowing or plain quads.
        boolean glow = DaisychainClientConfig.energyGlow();
        long key = ((long) Block.getId(state) << 6) | insert | (glow ? Long.MIN_VALUE : 0L);
        List<BakedQuad>[] lists = cache.get(key);
        if (lists == null) {
            lists = build(state, insert, glow, rand, renderType);
            List<BakedQuad>[] earlier = cache.putIfAbsent(key, lists);
            if (earlier != null) {
                lists = earlier;
            }
        }
        return lists[side == null ? 6 : side.get3DDataValue()];
    }

    /**
     * Step 128: a see-through cover. For this render layer: the pipe's quads if the pipe draws in it, then the cover's
     * if the cover draws in it (a null layer, e.g. the breaking overlay, gets both). Only called while a chunk section
     * is built, never per frame; still, a list is only copied when both parts have quads or the cover's need tinting.
     */
    private List<BakedQuad> pipeAndCover(BlockState state, BlockState cover, @Nullable Direction side, RandomSource rand,
                                         ModelData extraData, @Nullable RenderType renderType) {
        List<BakedQuad> pipe = renderType == null || pipeLayers(state).contains(renderType)
                ? pipeQuads(state, side, rand, extraData, renderType) : List.of();
        List<BakedQuad> coverQuads;
        try {
            BakedModel model = coverModel(cover);
            ModelData data = coverData(extraData);
            coverQuads = renderType == null || coverLayers(model, cover, data).contains(renderType)
                    ? model.getQuads(cover, side, rand, data, renderType) : List.of();
        } catch (RuntimeException e) {
            warnCover(cover, e);
            coverQuads = List.of(); // the pipe alone
        }
        boolean tinted = false;
        for (int i = 0; i < coverQuads.size(); i++) {
            if (coverQuads.get(i).isTinted()) {
                tinted = true;
                break;
            }
        }
        if (coverQuads.isEmpty()) {
            return pipe;
        }
        if (pipe.isEmpty() && !tinted) {
            return coverQuads;
        }
        List<BakedQuad> quads = new ArrayList<>(pipe.size() + coverQuads.size());
        quads.addAll(pipe);
        for (int i = 0; i < coverQuads.size(); i++) {
            BakedQuad quad = coverQuads.get(i);
            quads.add(quad.isTinted() ? new BakedQuad(quad.getVertices(), quad.getTintIndex() + COVER_TINT_OFFSET,
                    quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion()) : quad);
        }
        return quads;
    }

    /** The pipe's own render layers (cutout). */
    private ChunkRenderTypeSet pipeLayers(BlockState state) {
        RandomSource random = LAYER_RANDOM.get();
        random.setSeed(42L);
        return node.getRenderTypes(state, random, ModelData.EMPTY);
    }

    /** A cover model's render layers, asked with the fixed-seed random (see LAYER_RANDOM). */
    private static ChunkRenderTypeSet coverLayers(BakedModel model, BlockState cover, ModelData data) {
        RandomSource random = LAYER_RANDOM.get();
        random.setSeed(42L);
        return model.getRenderTypes(cover, random, data);
    }

    /** Step 126: a covered pipe is drawn in its cover's render layers (glass: cutout, ice: translucent). */
    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState cover = data.get(PipeDataBlockEntity.COVER_MODEL);
        if (cover != null) {
            try {
                ChunkRenderTypeSet coverLayers = coverModel(cover).getRenderTypes(cover, rand, coverData(data));
                // Step 128: a see-through cover draws the pipe too, so both parts' layers.
                return showsPipe(state)
                        ? ChunkRenderTypeSet.union(super.getRenderTypes(state, rand, data), coverLayers) : coverLayers;
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
        }
        return super.getRenderTypes(state, rand, data);
    }

    @Override
    public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
        BlockState cover = data.get(PipeDataBlockEntity.COVER_MODEL);
        // Step 128: under a see-through cover the pipe's layer (cutout) keeps the pipe's own setting.
        if (cover != null && !(showsPipe(state) && renderType != null && pipeLayers(state).contains(renderType))) {
            try {
                return coverModel(cover).useAmbientOcclusion(cover, coverData(data), renderType);
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
        }
        return super.useAmbientOcclusion(state, data, renderType);
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
    private List<BakedQuad>[] build(BlockState state, int insert, boolean glow, RandomSource rand,
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
        boolean inset = showsPipe(state); // step 128: under a see-through cover
        List<BakedQuad>[] lists = new List[7];
        for (int i = 0; i < 7; i++) {
            Direction face = i < 6 ? Direction.from3DDataValue(i) : null;
            List<BakedQuad> quads = new ArrayList<>();
            for (int side = 0; side < 6; side++) {
                for (BakedQuad quad : parts[side].getQuads(state, face, rand, ModelData.EMPTY, renderType)) {
                    BakedQuad shown = glow && quad.getTintIndex() == PipeColors.BAND_TINT ? glowing(quad) : quad;
                    quads.add(inset ? insetFromSurface(shown) : shown);
                }
            }
            lists[i] = List.copyOf(quads);
        }
        return lists;
    }

    /**
     * Step 128: a pipe face lying on the block's outer surface (a pull cap's end) is moved 1/8 pixel inward, so it doesn't
     * flicker against the see-through cover's face in the same plane. Other quads are returned as they are. Done once
     * when the quads are built.
     */
    private static BakedQuad insetFromSurface(BakedQuad quad) {
        Direction direction = quad.getDirection();
        int axis = direction.getAxis().ordinal(); // x, y, z: the first three ints of each vertex
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        float surface = positive ? 1.0F : 0.0F;
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        for (int v = 0; v < 4; v++) {
            if (Math.abs(Float.intBitsToFloat(vertices[v * stride + axis]) - surface) > 1.0E-4F) {
                return quad;
            }
        }
        int[] moved = vertices.clone();
        int inward = Float.floatToRawIntBits(positive ? 1.0F - EDGE_INSET : EDGE_INSET);
        for (int v = 0; v < 4; v++) {
            moved[v * stride + axis] = inward;
        }
        return new BakedQuad(moved, quad.getTintIndex(), direction, quad.getSprite(), quad.isShade(),
                quad.hasAmbientOcclusion());
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
