package com.daisychain.client;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.SideState;
import com.daisychain.config.DaisychainClientConfig;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.AABB;
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
 */
public final class PipeBakedModel extends BakedModelWrapper<BakedModel> {
    /** One bit per side (Direction.get3DDataValue): a connected side that touches a block, not a pipe. */
    static final ModelProperty<Integer> INSERT_SIDES = new ModelProperty<>();
    /**
     * Step 82 (zerobit): 5 bits per side (side * 5): how far the arm reaches past the block edge to meet a neighbor
     * that doesn't fill its space (a Flux Point, another mod's cable...), in half pixels (0 = not at all, max 16).
     */
    static final ModelProperty<Integer> EXTENSIONS = new ModelProperty<>();
    /** The furthest an arm reaches into the next block: half a block, in half pixels. */
    private static final int MAX_EXTENSION = 16;
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
    /** Quads by (state id << 36 | extensions << 6 | insert sides): 7 lists, one per face direction, then the unculled ones. */
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
        int insert = 0;
        int extensions = 0;
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(FlowPipeBlock.propertyFor(direction)) == SideState.NONE) {
                continue;
            }
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighbor = level.getBlockState(neighborPos);
            if (neighbor.getBlock() instanceof FlowPipeBlock) {
                continue; // pipes meet at the block edge
            }
            int side = direction.get3DDataValue();
            if (state.getValue(FlowPipeBlock.propertyFor(direction)) == SideState.CONNECTED
                    && neighbor.getBlock() != state.getBlock()) {
                insert |= 1 << side;
            }
            extensions |= gapTo(level, neighborPos, neighbor, direction) << (side * 5);
        }
        return ModelData.builder().with(INSERT_SIDES, insert).with(EXTENSIONS, extensions).build();
    }

    /**
     * Step 82: how far (in half pixels) the neighbor's shape stays away from the face the arm comes through, looking
     * only at the arm's 6x6 cross-section. 0 when it touches the face, when nothing is in front of the arm, or when it
     * is further than half a block (then reaching for it would look odd). Only runs when the chunk section is built.
     */
    private static int gapTo(BlockAndTintGetter level, BlockPos neighborPos, BlockState neighbor, Direction direction) {
        VoxelShape shape = neighbor.getShape(level, neighborPos);
        if (shape.isEmpty()) {
            return 0;
        }
        Direction.Axis axis = direction.getAxis();
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double lo = 5.0 / 16.0;
        double hi = 11.0 / 16.0;
        double nearest = Double.MAX_VALUE;
        for (AABB box : shape.toAabbs()) {
            boolean overlaps = true;
            for (Direction.Axis other : Direction.Axis.values()) {
                if (other != axis && (box.max(other) <= lo || box.min(other) >= hi)) {
                    overlaps = false;
                    break;
                }
            }
            if (overlaps) {
                double distance = positive ? box.min(axis) : 1.0 - box.max(axis);
                nearest = Math.min(nearest, distance);
            }
        }
        if (nearest == Double.MAX_VALUE || nearest <= 0.0) {
            return 0;
        }
        int halfPixels = (int) Math.round(nearest * 32.0);
        return halfPixels <= MAX_EXTENSION ? halfPixels : 0;
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
        Integer stored = extraData.get(INSERT_SIDES);
        int insert = stored == null ? 0 : stored & 0b111111;
        Integer storedExtensions = extraData.get(EXTENSIONS);
        int extensions = storedExtensions == null ? 0 : storedExtensions & 0x3FFFFFFF;
        // Step 90: the energy glow setting is part of the key (top bit), so switching it builds the glowing or plain quads.
        boolean glow = DaisychainClientConfig.energyGlow();
        long key = ((long) Block.getId(state) << 36) | ((long) extensions << 6) | insert | (glow ? Long.MIN_VALUE : 0L);
        List<BakedQuad>[] lists = cache.get(key);
        if (lists == null) {
            lists = build(state, insert, extensions, glow, rand, renderType);
            List<BakedQuad>[] earlier = cache.putIfAbsent(key, lists);
            if (earlier != null) {
                lists = earlier;
            }
        }
        return lists[side == null ? 6 : side.get3DDataValue()];
    }

    @SuppressWarnings("unchecked")
    private List<BakedQuad>[] build(BlockState state, int insert, int extensions, boolean glow, RandomSource rand,
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
        int[] kinds = new int[6];
        for (int side = 0; side < 6; side++) {
            int bit = 1 << side;
            if ((connected & bit) != 0) {
                kinds[side] = (pull & bit) != 0 ? PULL : (insert & bit) != 0 ? INSERT : LINK;
                parts[side] = pieces[kinds[side]][side];
            } else {
                kinds[side] = -1;
                parts[side] = cores[side][coreMask(side, connected)];
            }
        }
        List<BakedQuad>[] lists = new List[7];
        for (int i = 0; i < 7; i++) {
            Direction face = i < 6 ? Direction.from3DDataValue(i) : null;
            List<BakedQuad> quads = new ArrayList<>();
            for (int side = 0; side < 6; side++) {
                int halfPixels = kinds[side] >= 0 ? (extensions >> (side * 5)) & 0x1F : 0;
                List<BakedQuad> partQuads = parts[side].getQuads(state, face, rand, ModelData.EMPTY, renderType);
                Direction direction = Direction.from3DDataValue(side);
                for (BakedQuad quad : partQuads) {
                    BakedQuad shaped = halfPixels == 0 ? quad : extended(quad, direction, kinds[side], halfPixels / 2.0F);
                    quads.add(glow && shaped.getTintIndex() == PipeColors.BAND_TINT ? glowing(shaped) : shaped);
                }
            }
            lists[i] = List.copyOf(quads);
        }
        return lists;
    }

    /**
     * Step 82b (zerobit): the arm reaching past the block edge to meet a small neighbor. The whole arm end moves out to
     * the neighbor (pull: cap and tier ring; insert: end and groove) and only the plain tube between the core and that
     * end is stretched; the tube texture is the same all along its length, so stretching doesn't distort it.
     * Distances below are in pixels from the block edge (0) toward the core (5).
     */
    /**
     * Step 86 (zerobit): the energy stripe glows. Full light baked into the quad (so it's lit in the dark, but lights
     * nothing around it), no shading by face direction and no ambient occlusion. Done once when the quads are built.
     */
    private static BakedQuad glowing(BakedQuad quad) {
        BakedQuad lit = QuadTransformers.settingMaxEmissivity().process(quad);
        return new BakedQuad(lit.getVertices(), lit.getTintIndex(), lit.getDirection(), lit.getSprite(), false, false);
    }

    private static BakedQuad extended(BakedQuad quad, Direction direction, int kind, float extension) {
        float endOfFeatures = kind == PULL ? 3.5F : kind == INSERT ? 2.5F : 0.0F;
        float armEnd = 5.0F;
        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        int axis = direction.getAxis().ordinal(); // X = 0, Y = 1, Z = 2: the position float's index in a vertex
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        for (int v = 0; v < 4; v++) {
            int index = v * stride + axis;
            float coord = Float.intBitsToFloat(vertices[index]);
            float d = (positive ? 1.0F - coord : coord) * 16.0F;
            float moved;
            if (d <= endOfFeatures + 1.0E-3F) {
                moved = d - extension; // the cap / groove / end: moved out whole
            } else {
                // the tube from the features to the core: stretched to fill the new length
                moved = (endOfFeatures - extension)
                        + (d - endOfFeatures) * (armEnd - endOfFeatures + extension) / (armEnd - endOfFeatures);
            }
            float result = moved / 16.0F;
            vertices[index] = Float.floatToRawIntBits(positive ? 1.0F - result : result);
        }
        return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade());
    }
}
