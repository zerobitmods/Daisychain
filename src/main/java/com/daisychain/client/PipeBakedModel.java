package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.block.CoverShape;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.SideState;
import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.item.FacadeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
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
 * <p>
 * Step 129 (facades step 4): while the player holds a wrench ({@link WrenchSeeThrough}), a covered pipe is drawn
 * see-through: the pipe's own quads (cutout layer) plus the cover's quads faded to {@link #FADE_ALPHA}.
 * Step 129c: the faded cover is no longer in the chunk mesh (translucent chunk faces write depth and are sorted per
 * section, so overlapping faded faces hid each other and flickered). {@link WrenchCoverOverlay} draws it from its own
 * buffer with depth writing off; it asks this model for the faded quads with {@link #OVERLAY} set. Pipe faces on the block surface sit 1/8 pixel inside so they don't flicker against the faded cover.
 * Under an opaque cover the light inside the block is 0, so the pipe's quads get the brightest light of the six
 * neighbors baked in as a floor (NeoForge keeps the brighter of baked and world light). These quads are cached
 * separately and dropped when the wrench goes away. Tinted cover quads always carry {@link #COVER_TINT_OFFSET}, so
 * {@link PipeColors} can tell a cover's tints (leaves, grass) from the pipe's rings when both are drawn.
 * <p>
 * Step F61 (port of 134, transparent facade): a pipe wearing the transparent cover draws nothing at all (no pipe, no
 * cover) until a wrench is held; then it is drawn like any see-through cover, except the cover (the hologram cube) isn't
 * faded: its texture is see-through already. Its block state says GHOST (F69; not a full block, but any cover counts), so linked
 * plain pipes cap their arm's end as next to a glass cover (step 128).
 * <p>
 * Step F61 (port of 135): the hologram is drawn full-bright (it glows in the dark; since F67 WrenchCoverOverlay draws
 * every cover flat-lit, vertex by vertex, keeping the brighter of baked and given light), and a cover skips its face toward a neighbor
 * pipe wearing the same cover ({@link #SAME_COVER_SIDES}), like vanilla glass next to glass: a row of covered pipes
 * reads as one block, with and without a wrench.
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
    /**
     * Step 129: present while a covered pipe is drawn see-through (wrench in hand). The value is the light floor baked
     * into the pipe's quads: block light | sky light << 4 (0 under see-through covers, which let light in).
     */
    static final ModelProperty<Integer> SEE_THROUGH = new ModelProperty<>();
    /** Step 129: the position's model seed, so the faded cover picks the same variant as when it's drawn solid. */
    static final ModelProperty<Long> COVER_SEED = new ModelProperty<>();
    /** Step F61 (135): bit per side (get3DDataValue) whose neighbor is a pipe with the same cover: that face isn't drawn. */
    static final ModelProperty<Integer> SAME_COVER_SIDES = new ModelProperty<>();
    /** Step F61 (135): packed light for the hologram (block 15, sky 15). */
    private static final int FULL_BRIGHT = 0x00F000F0;
    /** Step 129c: set only by WrenchCoverOverlay: getQuads returns the faded cover alone (any render type). */
    static final ModelProperty<Boolean> OVERLAY = new ModelProperty<>();
    /**
     * Step 129: added to the tint index of tinted cover quads (leaves, grass), so they don't collide with the pipe's own
     * tint indexes (0-14). PipeColors subtracts it again and asks the cover's block colors.
     */
    public static final int COVER_TINT_OFFSET = 1000;
    /** Step 129: how opaque a cover is while a wrench is held (0-255; 102 = 40%). */
    static final int FADE_ALPHA = 102;
    /** Step 146: the transparent facade hologram's opacity: its texture's alpha times this / 255. */
    static final int HOLOGRAM_ALPHA = 128;
    /**
     * Step 129: a random for asking the cover model its layers and quads inside getQuads, reseeded with COVER_SEED each
     * time. The renderer's own random is used up by weighted models after the first call.
     */
    private static final ThreadLocal<RandomSource> LAYER_RANDOM = ThreadLocal.withInitial(RandomSource::create);
    /** Step 129: the faded cover's layer. */
    static final int LINK = 0;
    static final int INSERT = 1;
    static final int PULL = 2;
    /** Step 146: a pipe with exactly one connection, to another pipe, uses its own arm (a longer end block). */
    static final int END = 3;
    /** Step 146: a pull side on a pipe that would be a straight dead end without it (one other connection,
     * opposite): the pull cap plus the dead end's raised end block in front of it. */
    static final int PULL_END = 4;
    /** Step 146: as PULL_END for an insert side. */
    static final int INSERT_END = 5;
    /** Step 146: the block side of a pipe with no other connection (draws the whole pipe). */
    static final int PULL_LONE = 6;
    static final int INSERT_LONE = 7;
    /**
     * Step 146: the arm toward a covered pipe (step 128's end cap): it ends like a dead end, the tip (with the
     * middle hole) at the block face and a full block back to the core.
     */
    static final int CAP = 8;
    /**
     * Core face variants per side: one bit per perpendicular side that has an arm, plus (step 146) bit 4 = the
     * opposite side has an arm, so a face's line can wrap round an outer corner to meet the next face's line.
     */
    static final int CORE_MASKS = 32;
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
    /** Step 129: see-through pipe quads by (state id << 20 | light floor << 12 | insert sides, top bit = glow). */
    private final ConcurrentHashMap<Long, List<BakedQuad>[]> seeThroughCache = new ConcurrentHashMap<>();
    /** Step 129: WrenchSeeThrough.generation() when seeThroughCache was last cleared. */
    private volatile int seeThroughGeneration;

    /** Step 146: [a][b], a < b at right angles: closes the grooves where arms a and b meet at the core. */
    private final BakedModel[][] plugs;
    /** Step 146: drawn only when all six sides are connected (no core face is drawn then). */
    private final BakedModel coreAll;
    /** Step 151: break and hit particles show the pipe's body texture (the node's particle is an old pre-thin image). */
    private final net.minecraft.resources.ResourceLocation particleTexture;

    PipeBakedModel(BakedModel node, BakedModel[][] cores, BakedModel[][] pieces, BakedModel[][] plugs,
                   BakedModel coreAll, net.minecraft.resources.ResourceLocation particleTexture) {
        super(node);
        this.node = node;
        this.cores = cores;
        this.pieces = pieces;
        this.plugs = plugs;
        this.coreAll = coreAll;
        this.particleTexture = particleTexture;
    }

    /** Which of the four sides around this side have arms (see PERPENDICULAR). */
    static int coreMask(int side, int connected) {
        int mask = 0;
        for (int b = 0; b < 4; b++) {
            if ((connected & (1 << PERPENDICULAR[side][b])) != 0) {
                mask |= 1 << b;
            }
        }
        if ((connected & (1 << (side ^ 1))) != 0) {
            mask |= 1 << 4; // Step 146: the opposite side has an arm
        }
        return mask;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        if (!(state.getBlock() instanceof BasePipeBlock)) {
            return modelData;
        }
        // Step 126b: only while the pipe has its data block. When the last setting (the cover) comes off, the server
        // removes the block entity (HAS_DATA false) and Forge never clears the model data it stored for this spot,
        // so without this check the old cover stayed drawn until the chunk reloaded.
        BlockState cover = state.getValue(BasePipeBlock.HAS_DATA) ? modelData.get(PipeDataBlockEntity.COVER_MODEL) : null;
        if (cover != null) {
            ModelData.Builder builder = ModelData.builder().with(PipeDataBlockEntity.COVER_MODEL, cover);
            int same = sameCoverSides(level, pos, cover);
            if (same != 0) {
                builder.with(SAME_COVER_SIDES, same);
            }
            try {
                ModelData coverData = coverModel(cover).getModelData(level, pos, cover, ModelData.EMPTY);
                if (coverData != null && coverData != ModelData.EMPTY) {
                    builder.with(COVER_DATA, coverData);
                }
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
            if (WrenchSeeThrough.drawSeeThrough(pos)) {
                // Step 129: wrench in hand, so the pipe is drawn too (no end caps: covered neighbors show their pipe).
                int light = state.getValue(BasePipeBlock.COVER) == CoverShape.SOLID ? lightFloor(level, pos) : 0;
                builder.with(INSERT_SIDES, sideBits(level, pos, state, false))
                        .with(SEE_THROUGH, light)
                        .with(COVER_SEED, state.getSeed(pos));
            }
            return builder.build();
        }
        int bits = sideBits(level, pos, state, true);
        if (bits >>> 6 != 0 && WrenchSeeThrough.drawSeeThrough(pos)) {
            // Step 146: wrench in hand, so the covered neighbor shows its pipe; a cap would be a wall across
            // the joint (a dark square in the line). drawSeeThrough records the section, so it's redrawn
            // with its caps when the wrench goes away.
            bits &= 0b111111;
        }
        return ModelData.builder().with(INSERT_SIDES, bits).build();
    }

    /**
     * Step 129: the brightest block and sky light of the six neighbors (block | sky << 4). An opaque cover blocks light,
     * so its own position is dark; this is baked into the see-through pipe's quads as a floor.
     */
    static int lightFloor(BlockAndTintGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        int block = 0;
        int sky = 0;
        for (Direction direction : DIRECTIONS) {
            int packed = LevelRenderer.getLightColor(level, next.setWithOffset(pos, direction));
            block = Math.max(block, LightTexture.block(packed));
            sky = Math.max(sky, LightTexture.sky(packed));
        }
        return block | sky << 4;
    }

    /**
     * Step F61 (135): the sides whose neighbor is a pipe wearing this same cover. Reads the neighbors' block entities: on
     * the main thread for the overlay, from the chunk copy while meshing (vanilla does the same for block entity
     * renderers). A neighbor's cover changing re-meshes this section too (its block update marks the sections around it).
     */
    private static int sameCoverSides(BlockAndTintGetter level, BlockPos pos, BlockState cover) {
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        int sides = 0;
        for (Direction direction : DIRECTIONS) {
            next.setWithOffset(pos, direction);
            BlockState neighbor = level.getBlockState(next);
            if (neighbor.getBlock() instanceof BasePipeBlock && neighbor.getValue(BasePipeBlock.COVER) != CoverShape.NONE
                    && level.getBlockEntity(next) instanceof PipeDataBlockEntity data && data.cover() == cover) {
                sides |= 1 << direction.get3DDataValue();
            }
        }
        return sides;
    }

    /** Step F61 (135): true if this face of the cover touches a neighbor with the same cover. */
    private static boolean sharedFace(ModelData data, @Nullable Direction side) {
        if (side == null) {
            return false;
        }
        Integer same = data.get(SAME_COVER_SIDES);
        return same != null && (same & (1 << side.get3DDataValue())) != 0;
    }

    /** Insert sides (bits 0-5) and, if asked, end-capped link sides (bits 6-11, step 128). */
    private static int sideBits(BlockAndTintGetter level, BlockPos pos, BlockState state, boolean caps) {
        int insert = 0;
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(BasePipeBlock.propertyFor(direction)) == SideState.NONE) {
                continue;
            }
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.getBlock() instanceof BasePipeBlock) {
                // Pipes meet at the block edge. Step 128: a covered neighbor draws only its cover, so this arm's end
                // would be open; cap it (not pull arms, they have their own cap).
                // Step 146: not when this pipe is covered too. Its pipe only shows with a wrench (see-through), and
                // then the neighbor's pipe shows as well, so the caps stood between them as walls (it looked disconnected).
                if (caps && neighbor.getValue(BasePipeBlock.COVER) != CoverShape.NONE
                        && state.getValue(BasePipeBlock.COVER) == CoverShape.NONE
                        && state.getValue(BasePipeBlock.propertyFor(direction)) != SideState.EXTRACT) {
                    insert |= 1 << (6 + direction.get3DDataValue());
                }
                continue;
            }
            if (state.getValue(BasePipeBlock.propertyFor(direction)) == SideState.CONNECTED
                    && neighbor.getBlock() != state.getBlock()) {
                insert |= 1 << direction.get3DDataValue();
            }
        }
        return insert;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData extraData, @Nullable RenderType renderType) {
        if (state == null || !(state.getBlock() instanceof BasePipeBlock)) {
            return node.getQuads(state, side, rand, ModelData.EMPTY, renderType);
        }
        BlockState cover = extraData.get(PipeDataBlockEntity.COVER_MODEL);
        Integer seeThrough = extraData.get(SEE_THROUGH);
        if (cover != null && seeThrough != null) {
            // Step 129: wrench in hand. Step 129c: the faded cover only for WrenchCoverOverlay; chunk meshes get the pipe.
            if (extraData.get(OVERLAY) != null) {
                return sharedFace(extraData, side) ? List.of() : fadedCover(cover, side, extraData);
            }
            return seeThroughPipe(state, side, rand, extraData, renderType, seeThrough);
        }
        if (cover != null && (FacadeItem.isTransparent(cover) || sharedFace(extraData, side))) {
            return List.of(); // step F61: looks like no pipe until a wrench is held (134); shared face (135)
        }
        if (cover != null) {
            try {
                return withCoverTints(coverModel(cover).getQuads(cover, side, rand, coverData(extraData), renderType));
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
            lists = build(state, sides & 0b111111, sides >>> 6, glow, rand, renderType, false, 0);
            List<BakedQuad>[] earlier = cache.putIfAbsent(key, lists);
            if (earlier != null) {
                lists = earlier;
            }
        }
        return lists[side == null ? 6 : side.get3DDataValue()];
    }

    /**
     * Step 129: the pipe inside a see-through cover: faces on the block surface inset, light floor baked in. Cached
     * until the wrench goes away (WrenchSeeThrough bumps its generation).
     */
    private List<BakedQuad> seeThroughPipe(BlockState state, @Nullable Direction side, RandomSource rand,
                                           ModelData extraData, @Nullable RenderType renderType, int light) {
        int generation = WrenchSeeThrough.generation();
        if (generation != seeThroughGeneration) {
            seeThroughCache.clear();
            seeThroughGeneration = generation;
        }
        Integer stored = extraData.get(INSERT_SIDES);
        int insert = stored == null ? 0 : stored & 0b111111;
        boolean glow = DaisychainClientConfig.energyGlow();
        long key = ((long) Block.getId(state) << 20) | (long) (light & 0xFF) << 12 | insert
                | (glow ? Long.MIN_VALUE : 0L);
        List<BakedQuad>[] lists = seeThroughCache.get(key);
        if (lists == null) {
            lists = build(state, insert, 0, glow, rand, renderType, true, light & 0xFF);
            List<BakedQuad>[] earlier = seeThroughCache.putIfAbsent(key, lists);
            if (earlier != null) {
                lists = earlier;
            }
        }
        return lists[side == null ? 6 : side.get3DDataValue()];
    }

    /**
     * Step 129: the cover's quads from all of its own render layers, faded to FADE_ALPHA (drawn in the translucent
     * layer). Only while a chunk section with a covered pipe is built and a wrench is held; never per frame.
     */
    private static List<BakedQuad> fadedCover(BlockState cover, @Nullable Direction side, ModelData extraData) {
        Long stored = extraData.get(COVER_SEED);
        long seed = stored == null ? 42L : stored;
        RandomSource random = LAYER_RANDOM.get();
        try {
            BakedModel model = coverModel(cover);
            ModelData data = coverData(extraData);
            random.setSeed(seed);
            ChunkRenderTypeSet layers = model.getRenderTypes(cover, random, data);
            List<BakedQuad> faded = new ArrayList<>();
            for (RenderType layer : layers) {
                random.setSeed(seed);
                List<BakedQuad> quads = model.getQuads(cover, side, random, data, layer);
                for (int i = 0; i < quads.size(); i++) {
                    BakedQuad quad = quads.get(i);
                    if (!faded.contains(quad)) {
                        faded.add(quad);
                    }
                }
            }
            boolean glow = FacadeItem.isTransparent(cover); // step F61 (134/135): the hologram isn't faded, it glows
            for (int i = 0; i < faded.size(); i++) {
                faded.set(i, glow ? glowingQuad(faded.get(i), HOLOGRAM_ALPHA) : fadedQuad(faded.get(i)));
            }
            return faded;
        } catch (RuntimeException e) {
            warnCover(cover, e);
            return List.of(); // the pipe alone
        }
    }

    /** Step 129: a copy with its vertex alpha scaled down to FADE_ALPHA, tint index moved by COVER_TINT_OFFSET. */
    private static BakedQuad fadedQuad(BakedQuad quad) {
        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        for (int v = 0; v < 4; v++) {
            int color = vertices[v * stride + 3]; // BLOCK format: x, y, z, color (ABGR), u, v, light, normal
            int alpha = (color >>> 24) * FADE_ALPHA / 255;
            vertices[v * stride + 3] = (color & 0x00FFFFFF) | alpha << 24;
        }
        return new BakedQuad(vertices, quad.isTinted() ? quad.getTintIndex() + COVER_TINT_OFFSET : quad.getTintIndex(),
                quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    /**
     * Step F61 (135): the hologram's quad lit full-bright: light 15/15 baked in (Forge keeps the brighter of baked and
     * world light), no side shading, no ambient occlusion, so it glows evenly in caves and at night.
     */
    private static BakedQuad glowingQuad(BakedQuad quad, int fade) {
        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        for (int v = 0; v < 4; v++) {
            vertices[v * stride + 6] = FULL_BRIGHT; // BLOCK format: x, y, z, color, u, v, light, normal
            int color = vertices[v * stride + 3]; // color (ABGR): alpha scaled down like fadedQuad
            vertices[v * stride + 3] = (color & 0x00FFFFFF) | ((color >>> 24) * fade / 255) << 24;
        }
        return new BakedQuad(vertices, quad.isTinted() ? quad.getTintIndex() + COVER_TINT_OFFSET : quad.getTintIndex(),
                quad.getDirection(), quad.getSprite(), false, false);
    }

    /**
     * Step 129: tinted cover quads get COVER_TINT_OFFSET so PipeColors knows they are the cover's. The list is only
     * copied when one of its quads is tinted (leaves, grass); plain covers are returned as they are.
     */
    private static List<BakedQuad> withCoverTints(List<BakedQuad> quads) {
        int first = -1;
        for (int i = 0; i < quads.size(); i++) {
            if (quads.get(i).isTinted()) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return quads;
        }
        List<BakedQuad> tinted = new ArrayList<>(quads);
        for (int i = first; i < tinted.size(); i++) {
            BakedQuad quad = tinted.get(i);
            if (quad.isTinted()) {
                tinted.set(i, new BakedQuad(quad.getVertices(), quad.getTintIndex() + COVER_TINT_OFFSET,
                        quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion()));
            }
        }
        return tinted;
    }

    /** Step 126: a covered pipe is drawn in its cover's render layers (glass: cutout, ice: translucent). */
    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState cover = data.get(PipeDataBlockEntity.COVER_MODEL);
        if (cover != null && data.get(SEE_THROUGH) != null) {
            // Step 129c: chunk meshes hold only the pipe's own layers (cutout); WrenchCoverOverlay draws the cover.
            return super.getRenderTypes(state, rand, data);
        }
        if (cover != null && !FacadeItem.isTransparent(cover)) { // step F61: transparent draws nothing, keep the pipe's layers
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
        // Step 129: while see-through, only the overlay's faded cover (129c) uses the cover's setting; the pipe keeps its own.
        // Step F61 (134): an invisible pipe breaks with pipe bits.
        if (cover != null && !FacadeItem.isTransparent(cover)
                && (data.get(SEE_THROUGH) == null || data.get(OVERLAY) != null)) {
            try {
                return coverModel(cover).getParticleIcon(coverData(data));
            } catch (RuntimeException e) {
                warnCover(cover, e);
            }
        }
        // Step 151: the body texture, tinted by PipeParticles (looked up per call: a cheap map lookup, and always the
        // current atlas after a resource reload).
        return Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(particleTexture);
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
                                    @Nullable RenderType renderType, boolean seeThrough, int lightFloor) {
        boolean energy = EnergyPipeBlock.tierOf(state.getBlock()) >= 0;
        int connected = 0;
        int pull = 0;
        for (Direction direction : DIRECTIONS) {
            SideState sideState = state.getValue(BasePipeBlock.propertyFor(direction));
            int bit = 1 << direction.get3DDataValue();
            if (sideState != SideState.NONE) {
                connected |= bit;
            }
            if (sideState == SideState.EXTRACT) {
                pull |= bit;
            }
        }
        // Step 146: a pipe that would be a dead end (or a lone pipe) without its one block side: that side's
        // *_END / *_LONE piece draws the whole full block from the cap back to where a dead end's block starts, so no
        // core faces are drawn.
        int endSide = -1;
        for (int side = 0; side < 6; side++) {
            int bit = 1 << side;
            int rest = connected & ~bit;
            if ((connected & bit) != 0 && ((pull | insert) & bit) != 0 && (rest == 0 || rest == (1 << (side ^ 1)))) {
                endSide = endSide == -1 ? side : -2; // two such sides (a pipe between two blocks): neither
            }
        }
        int coreConn = connected;
        BakedModel[] parts = new BakedModel[6];
        for (int side = 0; side < 6; side++) {
            int bit = 1 << side;
            if ((connected & bit) != 0) {
                int kind = (pull & bit) != 0 ? PULL : (insert & bit) != 0 ? INSERT : LINK;
                if (kind == LINK && Integer.bitCount(connected) == 1) {
                    kind = END;
                } else if (side == endSide) {
                    boolean lone = connected == bit;
                    kind = kind == PULL ? (lone ? PULL_LONE : PULL_END) : (lone ? INSERT_LONE : INSERT_END);
                }
                parts[side] = pieces[kind][side];
                if ((capped & ~pull & bit) != 0 && (kind == LINK || kind == END)) {
                    parts[side] = pieces[CAP][side]; // Step 146: ends here like a dead end (covered neighbor)
                }
            } else {
                parts[side] = endSide >= 0 ? null : cores[side][coreMask(side, coreConn)];
            }
        }
        BakedModel[] extra = new BakedModel[13];
        int extras = 0;
        for (int a = 0; a < 6; a++) {
            for (int b = a + 1; b < 6; b++) {
                if (a / 2 != b / 2 && (connected & (1 << a)) != 0 && (connected & (1 << b)) != 0) {
                    extra[extras++] = plugs[a][b];
                }
            }
        }
        if (connected == 63) {
            extra[extras++] = coreAll;
        }
        List<BakedQuad>[] lists = new List[7];
        for (int i = 0; i < 7; i++) {
            Direction face = i < 6 ? Direction.from3DDataValue(i) : null;
            List<BakedQuad> quads = new ArrayList<>();
            for (int side = 0; side < 6 + extras; side++) {
                BakedModel part = side < 6 ? parts[side] : extra[side - 6];
                if (part == null) {
                    continue; // Step 146: a core face drawn by a *_END / *_LONE piece instead
                }
                for (BakedQuad quad : part.getQuads(state, face, rand, ModelData.EMPTY, renderType)) {
                    int tint = quad.getTintIndex();
                    // Step 154: an energy pipe's pull and insert rings (tints 0-11) glow with its line.
                    BakedQuad shown = glow && (tint == PipeColors.BAND_TINT || energy && tint >= 0 && tint <= 11)
                            ? glowing(quad) : quad;
                    quads.add(seeThrough ? withLightFloor(insetFromSurface(shown), lightFloor) : shown);
                }
            }
            int bit = 1 << i;
            lists[i] = List.copyOf(quads);
        }
        return lists;
    }

    /**
     * Step 129: a pipe face lying on the block's outer surface (a pull cap's or insert arm's end) is moved EDGE_INSET
     * inward, so it doesn't flicker against the faded cover's face in the same plane. Other quads are returned as they
     * are.
     */
    private static BakedQuad insetFromSurface(BakedQuad quad) {
        Direction direction = quad.getDirection();
        int axis = direction.getAxis().ordinal(); // x, y, z: the first three ints of each vertex
        float surface = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        for (int v = 0; v < 4; v++) {
            if (Math.abs(Float.intBitsToFloat(vertices[v * stride + axis]) - surface) > 1.0E-4F) {
                return quad;
            }
        }
        return movedToEdge(quad, direction);
    }

    /**
     * Step 129: a copy with at least this light baked in (block | sky << 4, levels 0-15). NeoForge draws block quads
     * with the brighter of the baked and the world light, so this only ever brightens. Glowing quads (already at full
     * light) and a floor of 0 return the quad as it is.
     */
    private static BakedQuad withLightFloor(BakedQuad quad, int lightFloor) {
        if (lightFloor == 0) {
            return quad;
        }
        int block = (lightFloor & 0xF) << 4;
        int sky = (lightFloor >> 4 & 0xF) << 4;
        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        boolean changed = false;
        for (int v = 0; v < 4; v++) {
            int baked = vertices[v * stride + 6]; // BLOCK format: light (UV2) is the 7th int, block | sky << 16
            int lit = Math.max(baked & 0xFFFF, block) | Math.max(baked >>> 16, sky) << 16;
            if (lit != baked) {
                vertices[v * stride + 6] = lit;
                changed = true;
            }
        }
        return changed ? new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(),
                quad.isShade(), quad.hasAmbientOcclusion()) : quad;
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
