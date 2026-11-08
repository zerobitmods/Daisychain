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
        List<BakedQuad>[] lists = new List[7];
        for (int i = 0; i < 7; i++) {
            Direction face = i < 6 ? Direction.from3DDataValue(i) : null;
            List<BakedQuad> quads = new ArrayList<>();
            for (int side = 0; side < 6; side++) {
                for (BakedQuad quad : parts[side].getQuads(state, face, rand, ModelData.EMPTY, renderType)) {
                    quads.add(glow && quad.getTintIndex() == PipeColors.BAND_TINT ? glowing(quad) : quad);
                }
            }
            lists[i] = List.copyOf(quads);
        }
        return lists;
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
