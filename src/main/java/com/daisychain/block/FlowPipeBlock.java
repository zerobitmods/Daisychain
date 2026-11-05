package com.daisychain.block;

import com.daisychain.item.SettingsCopierItem;
import com.daisychain.item.UpgradeItem;
import com.daisychain.item.UpgradeTier;
import com.daisychain.menu.PullSideMenu;
import com.daisychain.network.NetworkManager;
import com.daisychain.network.PipeNetwork;
import com.daisychain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.daisychain.registry.ModTags;

import javax.annotation.Nullable;

/**
 * Base class for every Daisychain pipe. A pipe segment is a plain block with a SideState per side.
 * Only pipes with player-changed settings get a (non-ticking) block entity.
 */
public abstract class FlowPipeBlock extends Block implements EntityBlock {

    public static final EnumProperty<SideState> DOWN = EnumProperty.create("down", SideState.class);
    public static final EnumProperty<SideState> UP = EnumProperty.create("up", SideState.class);
    public static final EnumProperty<SideState> NORTH = EnumProperty.create("north", SideState.class);
    public static final EnumProperty<SideState> SOUTH = EnumProperty.create("south", SideState.class);
    public static final EnumProperty<SideState> WEST = EnumProperty.create("west", SideState.class);
    public static final EnumProperty<SideState> EAST = EnumProperty.create("east", SideState.class);
    /** True when this pipe has a PipeDataBlockEntity holding its side settings. */
    public static final BooleanProperty HAS_DATA = BooleanProperty.create("has_data");

    // Indexed by Direction.get3DDataValue(): DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5
    private static final EnumProperty<SideState>[] PROPERTY_BY_DIRECTION = createPropertyArray();
    private static final Direction[] DIRECTIONS = Direction.values();

    // Pipe core is 6 pixels thick (from 5 to 11).
    private static final double CORE_MIN = 5.0 / 16.0;
    private static final double CORE_MAX = 11.0 / 16.0;
    private static final VoxelShape CORE = Block.box(5, 5, 5, 11, 11, 11);
    private static final VoxelShape[] ARMS = {
            Block.box(5, 0, 5, 11, 5, 11),   // down
            Block.box(5, 11, 5, 11, 16, 11), // up
            Block.box(5, 5, 0, 11, 11, 5),   // north
            Block.box(5, 5, 11, 11, 11, 16), // south
            Block.box(0, 5, 5, 5, 11, 11),   // west
            Block.box(11, 5, 5, 16, 11, 11)  // east
    };
    /** Pull side cap (8x8, 2 deep) plus the tier ring behind it (7x7, 1.5 deep), per side. */
    private static final VoxelShape[] PULL_CAPS = {
            Shapes.or(Block.box(4, 0, 4, 12, 2, 12), Block.box(4.5, 2, 4.5, 11.5, 3.5, 11.5)),     // down
            Shapes.or(Block.box(4, 14, 4, 12, 16, 12), Block.box(4.5, 12.5, 4.5, 11.5, 14, 11.5)), // up
            Shapes.or(Block.box(4, 4, 0, 12, 12, 2), Block.box(4.5, 4.5, 2, 11.5, 11.5, 3.5)),     // north
            Shapes.or(Block.box(4, 4, 14, 12, 12, 16), Block.box(4.5, 4.5, 12.5, 11.5, 11.5, 14)), // south
            Shapes.or(Block.box(0, 4, 4, 2, 12, 12), Block.box(2, 4.5, 4.5, 3.5, 11.5, 11.5)),     // west
            Shapes.or(Block.box(14, 4, 4, 16, 12, 12), Block.box(12.5, 4.5, 4.5, 14, 11.5, 11.5))  // east
    };
    /**
     * Step 42a: shapes that match the drawn look, made on first use and kept. Index = arms | pulls << 6 | ghost << 12,
     * ghost 0-5 = that one ghost arm, 6 = none, 7 = every armless side. Filling a slot twice from two threads is harmless.
     */
    private static final VoxelShape[] SHAPE_CACHE = new VoxelShape[1 << 15];
    private static final int GHOST_NONE = 6;
    private static final int GHOST_ALL = 7;
    private static final AABB[] ARM_BOUNDS = {
            ARMS[0].bounds(), ARMS[1].bounds(), ARMS[2].bounds(), ARMS[3].bounds(), ARMS[4].bounds(), ARMS[5].bounds()
    };
    private static final AABB CORE_BOUNDS = CORE.bounds();

    protected FlowPipeBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any().setValue(HAS_DATA, false);
        for (EnumProperty<SideState> property : PROPERTY_BY_DIRECTION) {
            state = state.setValue(property, SideState.NONE);
        }
        registerDefaultState(state);
    }

    /** What this pipe type carries (items, fluids, ...). */
    public abstract PipeKind kind();

    /**
     * Step 84 (zerobit): true if a pipe in this state joins this pipe's network. Pipes of the same block always do;
     * energy pipes of every tier join each other. Pipes of different types never connect.
     */
    public boolean linksWith(BlockState other) {
        return sameNetwork(this, other.getBlock());
    }

    /** True if pipes of these two blocks join one network (see linksWith). */
    public static boolean sameNetwork(Block a, Block b) {
        return a == b || (a instanceof EnergyPipeBlock && b instanceof EnergyPipeBlock);
    }

    /** The kind of the pipe at this state, or ITEM if it isn't a Daisychain pipe. */
    public static PipeKind kindOf(BlockState state) {
        return state.getBlock() instanceof FlowPipeBlock pipe ? pipe.kind() : PipeKind.ITEM;
    }

    /**
     * True if the block at targetPos offers this pipe's resource on targetSide.
     * Only called for positions that are loaded and are not this pipe type.
     */
    protected abstract boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide);

    public static EnumProperty<SideState> propertyFor(Direction direction) {
        return PROPERTY_BY_DIRECTION[direction.get3DDataValue()];
    }

    @SuppressWarnings("unchecked")
    private static EnumProperty<SideState>[] createPropertyArray() {
        return new EnumProperty[]{DOWN, UP, NORTH, SOUTH, WEST, EAST};
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DOWN, UP, NORTH, SOUTH, WEST, EAST, HAS_DATA);
    }

    // ---------------------------------------------------------------- block entity

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HAS_DATA) ? new PipeDataBlockEntity(pos, state) : null;
    }

    /**
     * The pipe's settings, or null if it has none. Always use this instead of getBlockEntity directly:
     * clients can keep a stale block entity after the server removed it (has_data went false).
     */
    @Nullable
    public static PipeDataBlockEntity getData(BlockGetter level, BlockPos pos, BlockState state) {
        if (!state.getValue(HAS_DATA)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof PipeDataBlockEntity data ? data : null;
    }

    /**
     * True if this side is a pull side: drawn as one (step 34: any side marked Pull, with or without a block next to
     * it), or marked to pull while its neighbor's chunk wasn't loaded when the arms were last worked out. Its settings, upgrade and filter can be opened either way; only a real inventory gets pulled from.
     */
    public static boolean isPullSide(BlockGetter level, BlockPos pos, BlockState state, Direction side) {
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return false;
        }
        SideState sideState = state.getValue(propertyFor(side));
        if (sideState == SideState.EXTRACT) {
            return true;
        }
        return sideState == SideState.NONE && hasBit(extractMask(level, pos, state), side);
    }

    private static int extractMask(BlockGetter level, BlockPos pos, BlockState state) {
        PipeDataBlockEntity data = getData(level, pos, state);
        return data == null ? 0 : data.extractMask();
    }

    private static int disconnectMask(BlockGetter level, BlockPos pos, BlockState state) {
        PipeDataBlockEntity data = getData(level, pos, state);
        return data == null ? 0 : data.disconnectMask();
    }

    private static boolean hasUpgrades(BlockGetter level, BlockPos pos, BlockState state) {
        PipeDataBlockEntity data = getData(level, pos, state);
        return data != null && data.hasUpgrades();
    }

    /** Upgrades or insert settings: things that need the block entity even with no pull or disconnected side. */
    private static boolean hasStoredSettings(BlockGetter level, BlockPos pos, BlockState state) {
        PipeDataBlockEntity data = getData(level, pos, state);
        return data != null && (data.hasUpgrades() || data.hasInsertSettings() || data.hasPullSettings());
    }

    /**
     * True if this side is an insert side: a normal (connected) side whose neighbor is not a pipe, i.e. an inventory
     * items can be sent into. Never loads the neighbor's chunk.
     */
    public static boolean isInsertSide(Level level, BlockPos pos, BlockState state, Direction side) {
        if (!(state.getBlock() instanceof FlowPipeBlock) || state.getValue(propertyFor(side)) != SideState.CONNECTED) {
            return false;
        }
        BlockPos neighborPos = pos.relative(side);
        return level.isLoaded(neighborPos) && !(level.getBlockState(neighborPos).getBlock() instanceof FlowPipeBlock);
    }

    /** Server: gives the pipe its block entity if it has none yet (to hold insert settings). Arms don't change. */
    public static void ensureData(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FlowPipeBlock && !state.getValue(HAS_DATA)) {
            level.setBlock(pos, state.setValue(HAS_DATA, true), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    /** Server: removes the block entity again if nothing on the pipe needs it (e.g. an insert screen closed unused). */
    public static void dropDataIfUnused(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FlowPipeBlock && state.getValue(HAS_DATA)
                && (extractMask(level, pos, state) | disconnectMask(level, pos, state)) == 0
                && !hasStoredSettings(level, pos, state)) {
            refreshArms(level, pos);
        }
    }

    private static boolean hasBit(int mask, Direction direction) {
        return (mask & (1 << direction.get3DDataValue())) != 0;
    }

    // ---------------------------------------------------------------- connections

    /**
     * Works out what one side should look like. Returns null when it can't be decided
     * (the level isn't a full Level, e.g. during world generation).
     * Step 34 (zerobit): a side marked Pull is always a pull side, whatever is next to it (even air), until the wrench
     * changes it; only a pipe of the same type next to it overrides that (pipes connect, they never pull from pipes).
     */
    @Nullable
    private SideState computeSide(LevelAccessor level, Direction direction, BlockPos neighborPos,
                                  BlockState neighborState, int extractMask, int disconnectMask) {
        if (hasBit(disconnectMask, direction)) {
            return SideState.NONE;
        }
        if (linksWith(neighborState)) {
            boolean neighborDisconnected = hasBit(disconnectMask(level, neighborPos, neighborState), direction.getOpposite());
            return neighborDisconnected ? SideState.NONE : SideState.CONNECTED;
        }
        if (hasBit(extractMask, direction)) {
            return SideState.EXTRACT;
        }
        if (neighborState.isAir()) {
            return SideState.NONE;
        }
        if (!(level instanceof Level realLevel)) {
            return null;
        }
        return canConnectToBlock(realLevel, neighborPos, direction.getOpposite()) ? SideState.CONNECTED : SideState.NONE;
    }

    /** Recomputes all six sides. Never loads chunks: a pull side next to an unloaded chunk stays a pull side. */
    private BlockState computeAllSides(Level level, BlockPos pos, BlockState state, int extractMask, int disconnectMask) {
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            SideState side = hasBit(extractMask, direction) && !hasBit(disconnectMask, direction)
                    ? SideState.EXTRACT : SideState.NONE;
            if (level.isLoaded(neighborPos)) {
                SideState computed = computeSide(level, direction, neighborPos, level.getBlockState(neighborPos),
                        extractMask, disconnectMask);
                if (computed != null) {
                    side = computed;
                }
            }
            state = state.setValue(propertyFor(direction), side);
        }
        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return computeAllSides(context.getLevel(), context.getClickedPos(), defaultBlockState(), 0, 0);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        SideState side = computeSide(level, direction, neighborPos, neighborState,
                extractMask(level, pos, state), disconnectMask(level, pos, state));
        return side == null ? state : state.setValue(propertyFor(direction), side);
    }

    // ---------------------------------------------------------------- wrench

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                 BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof SettingsCopierItem) {
            // Skip the pipe's own click handling so the copier's useOn copies (or, sneaking, pastes) instead.
            return InteractionResult.PASS;
        }
        if (ModTags.isWrench(stack)) {
            // Step 65 (zerobit): sneak + right-click changes a side, like most pipe mods. Sneak-clicks reach this method
            // through DaisychainEvents.onRightClickBlock. Step 66: a plain right-click with a wrench opens the settings,
            // the same as an empty hand.
            if (!player.isSecondaryUseActive()) {
                if (hand != InteractionHand.MAIN_HAND) {
                    return InteractionResult.PASS;
                }
                openScreen(state, level, pos, player, hit);
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
            if (level instanceof ServerLevel serverLevel) {
                cycleSide(serverLevel, pos, state, targetedSide(state, pos, hit), player, false);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        // Step 93 (zerobit): sneak + right-click an energy pipe with a higher-tier energy pipe swaps it in place, keeping
        // its arms and settings. Sneak-clicks reach this method through DaisychainEvents.onRightClickBlock.
        // Step 95: not when the click is on a pull cap (sneak-clicking a pull cap with a pipe places it there).
        if (hand == InteractionHand.MAIN_HAND && player.isSecondaryUseActive() && EnergyPipeBlock.canSwap(state, stack)
                && !isPullCapHit(state, pos, hit)) {
            if (level instanceof ServerLevel serverLevel) {
                EnergyPipeBlock.swapInPlace(serverLevel, pos, state, player, stack);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        if (stack.getItem() instanceof UpgradeItem upgrade && hand == InteractionHand.MAIN_HAND) {
            if (level instanceof ServerLevel serverLevel) {
                installUpgrade(serverLevel, pos, state, targetedSide(state, pos, hit), player, stack, upgrade);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        // Sneak + right-click with both hands empty pops the upgrade out of the side you clicked.
        // (With anything in either hand, vanilla skips the block when sneaking, so this only fires empty-handed.)
        if (stack.isEmpty() && hand == InteractionHand.MAIN_HAND && player.isSecondaryUseActive()) {
            Direction side = targetedSide(state, pos, hit);
            PipeDataBlockEntity data = getData(level, pos, state);
            if (data != null && data.upgradeLevel(side) > 0) {
                if (level instanceof ServerLevel serverLevel) {
                    removeUpgrade(serverLevel, pos, side, player);
                }
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
        }
        // Step 95 (zerobit): a plain right-click on a pull cap opens that side's settings, even holding a pipe, so a pull
        // side is never lost to a misclick. Sneak + right-click places the pipe there, like any other block.
        if (hand == InteractionHand.MAIN_HAND && !player.isSecondaryUseActive() && isPullCapHit(state, pos, hit)) {
            openScreen(state, level, pos, player, hit);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        // Pipes are placed against pipes as normal, so building lines stays quick.
        // (Sneak + right-click always skips this method in vanilla, so sneaking places any block.)
        if (hand != InteractionHand.MAIN_HAND
                || (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FlowPipeBlock)) {
            return InteractionResult.PASS;
        }
        // Empty hand or any other item (including other blocks): open the settings.
        openScreen(state, level, pos, player, hit);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * zerobit's layout (step 25): clicking the arm of a pull or insert side opens that side's settings (a server-side
     * menu, because it has real slots). Clicking the pipe's center, or an arm that joins another pipe, opens the
     * "Pipe sides" screen (client only), which shows and changes all six sides. Both screens have tabs to switch.
     * Client and server make the same choice from the same synced state, so only one screen opens.
     */
    private static void openScreen(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        Direction side = targetedSide(state, pos, hit);
        boolean sideSettings = !isCoreHit(state, pos, hit.getLocation())
                && (isPullSide(level, pos, state, side) || isInsertSide(level, pos, state, side));
        if (sideSettings) {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
                PullSideMenu.openSettings(serverPlayer, serverLevel, pos, side);
            }
        } else if (level.isClientSide()) {
            com.daisychain.client.ClientAccess.openPipeSidesScreen(pos);
        }
    }

    /** True if the click landed on the pipe's center (core) rather than on one of its arms. */
    public static boolean isCoreHit(BlockState state, BlockPos pos, Vec3 clickLocation) {
        return hitSide(state, clickLocation.subtract(pos.getX(), pos.getY(), pos.getZ())) == null;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        // Only pipes with settings can have pull sides or redstone-controlled pull sides.
        if (level instanceof ServerLevel serverLevel && state.getValue(HAS_DATA)) {
            NetworkManager manager = NetworkManager.getExisting(serverLevel);
            if (manager != null) {
                manager.onRedstoneMaybeChanged(pos);
                manager.onNeighborChanged(pos); // a pull side asleep with nothing next to it may have something now
            }
        }
    }

    /** Which side of the pipe the player clicked: an arm if they hit one, otherwise the core face. */
    public static Direction targetedSide(BlockState state, BlockPos pos, BlockHitResult hit) {
        return targetedSide(state, pos, hit.getLocation(), hit.getDirection());
    }

    /** Same, from the clicked point and face (used by items, whose click context has these). */
    public static Direction targetedSide(BlockState state, BlockPos pos, Vec3 clickLocation, Direction clickedFace) {
        Direction side = hitSide(state, clickLocation.subtract(pos.getX(), pos.getY(), pos.getZ()));
        return side != null ? side : clickedFace;
    }

    /**
     * Step 42b: which side's arm (or pull cap, or ghost arm) the point is on, or null for the middle part (the core).
     * Uses the drawn shape: a side's part starts where the core ends (5 px from the edge; step 55: the core is the same
     * 6 px everywhere) and is as wide as its arm (6 px) or its pull cap (8 px).
     */
    @Nullable
    private static Direction hitSide(BlockState state, Vec3 local) {
        double x = local.x * 16.0;
        double y = local.y * 16.0;
        double z = local.z * 16.0;
        int pulls = state.getBlock() instanceof FlowPipeBlock ? pullMask(state) : 0;
        double inner = 5.0;
        double eps = 1.0E-3;
        for (Direction direction : Direction.values()) {
            int i = direction.get3DDataValue();
            boolean pull = (pulls & (1 << i)) != 0;
            double crossMin = (pull ? 4.0 : 5.0) - eps;
            double crossMax = (pull ? 12.0 : 11.0) + eps;
            double along;
            double a;
            double b;
            switch (direction.getAxis()) {
                case X -> {
                    along = x;
                    a = y;
                    b = z;
                }
                case Y -> {
                    along = y;
                    a = x;
                    b = z;
                }
                default -> {
                    along = z;
                    a = x;
                    b = y;
                }
            }
            double depth = direction.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? along : 16.0 - along;
            if (depth < inner - eps && a >= crossMin && a <= crossMax && b >= crossMin && b <= crossMax) {
                return direction;
            }
        }
        return null;
    }

    /** Side modes (also sent by SetSideModePayload, step 48). Normal reads as Insert next to a block, Link next to a pipe. */
    public static final int MODE_NORMAL = 0;
    public static final int MODE_PULL = 1;
    public static final int MODE_DISCONNECTED = 2;
    private static final String[] MODE_KEYS = {"normal", "pull", "disconnected"};
    /**
     * Sides facing a block. Step 41 (zerobit): Pull sits in the middle, so the first click from Insert or from Off is
     * Pull, and the click after that goes on to the other end: off -> pull -> insert -> pull -> off ... (see
     * nextInventoryMode). This list only matters for a backward cycle.
     */
    private static final int[] INVENTORY_MODES = {MODE_NORMAL, MODE_PULL, MODE_DISCONNECTED};
    /** Nothing next to the side: normal and off look the same (a ghost arm), so it's just ghost <-> pull. */
    private static final int[] EMPTY_MODES = {MODE_NORMAL, MODE_PULL};
    private static final int[] PIPE_MODES = {MODE_NORMAL, MODE_DISCONNECTED};

    /**
     * Step 48: which modes a side may be set to, as bits (1 << MODE_...): next to a pipe of this type, normal (link) or
     * disconnected; next to a block this pipe connects to, normal (insert), pull or disconnected; otherwise (air or a
     * block it can't use) normal or pull. 0 when the neighbor isn't loaded. Used by the server to check a request and by
     * the Pipe sides screen to grey out the other keys (client side it asks the client level, same rule).
     */
    public int allowedModes(Level level, BlockPos pos, Direction side) {
        BlockPos neighborPos = pos.relative(side);
        if (!level.isLoaded(neighborPos)) {
            return 0;
        }
        BlockState neighborState = level.getBlockState(neighborPos);
        if (linksWith(neighborState)) {
            return (1 << MODE_NORMAL) | (1 << MODE_DISCONNECTED);
        }
        boolean inventory = !neighborState.isAir() && canConnectToBlock(level, neighborPos, side.getOpposite());
        return inventory ? (1 << MODE_NORMAL) | (1 << MODE_PULL) | (1 << MODE_DISCONNECTED)
                : (1 << MODE_NORMAL) | (1 << MODE_PULL);
    }

    /** Step 48: a mode key on the Pipe sides screen (already validated as a request). Only allowed modes apply. */
    public void setSideModeFromScreen(ServerLevel level, BlockPos pos, Direction side, Player player, int mode) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() == this && (allowedModes(level, pos, side) & (1 << mode)) != 0) {
            setSideMode(level, pos, state, side, player, mode, false);
        }
    }

    /**
     * Steps a side to its next (or previous) mode.
     * Inventories: normal -> pull -> disconnected -> normal. Pipes: normal <-> disconnected.
     */
    private void cycleSide(ServerLevel level, BlockPos pos, BlockState state, Direction side, Player player, boolean backward) {
        BlockPos neighborPos = pos.relative(side);
        if (!level.isLoaded(neighborPos)) {
            return;
        }
        BlockState neighborState = level.getBlockState(neighborPos);
        boolean neighborIsPipe = linksWith(neighborState);
        boolean neighborIsInventory = !neighborIsPipe && !neighborState.isAir()
                && canConnectToBlock(level, neighborPos, side.getOpposite());

        int bit = 1 << side.get3DDataValue();
        int current = (disconnectMask(level, pos, state) & bit) != 0 ? MODE_DISCONNECTED
                : (extractMask(level, pos, state) & bit) != 0 ? MODE_PULL : MODE_NORMAL;

        // Step 34 (zerobit): any side that doesn't face a pipe of the same type can be made a pull side, even with
        // nothing there yet. Pipe-to-pipe sides only switch between normal and disconnected.
        int[] modes = neighborIsPipe ? PIPE_MODES : neighborIsInventory ? INVENTORY_MODES : EMPTY_MODES;
        int index = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == current) {
                index = i;
            }
        }
        // An empty side marked off counts as "ghost" (normal): the next click makes it Pull; leaving Pull makes it
        // normal again, so a block placed there later connects as insert (zerobit's choice).
        int target = modes == INVENTORY_MODES && !backward
                ? nextInventoryMode(level, pos, state, side, current)
                : modes[Math.floorMod(index + (backward ? -1 : 1), modes.length)];
        setSideMode(level, pos, state, side, player, target, true);
    }

    /**
     * Sets one side to a mode: the shared part of a wrench click / cycle and the Pipe sides screen's mode keys (step 48).
     * Hands back a pull side's upgrade when it stops pulling, keeps a neighbor pipe's matching side in step, and
     * (announce) says the new mode in the action bar.
     */
    private void setSideMode(ServerLevel level, BlockPos pos, BlockState state, Direction side, Player player, int target,
                             boolean announce) {
        BlockPos neighborPos = pos.relative(side);
        if (!level.isLoaded(neighborPos)) {
            return;
        }
        BlockState neighborState = level.getBlockState(neighborPos);
        boolean neighborIsPipe = linksWith(neighborState);
        boolean neighborIsInventory = !neighborIsPipe && !neighborState.isAir()
                && canConnectToBlock(level, neighborPos, side.getOpposite());

        int extract = extractMask(level, pos, state);
        int disconnect = disconnectMask(level, pos, state);
        int bit = 1 << side.get3DDataValue();
        int current = (disconnect & bit) != 0 ? MODE_DISCONNECTED : (extract & bit) != 0 ? MODE_PULL : MODE_NORMAL;
        if (current == target) {
            return;
        }

        extract &= ~bit;
        disconnect &= ~bit;
        if (target == MODE_PULL) {
            extract |= bit;
        } else if (target == MODE_DISCONNECTED) {
            disconnect |= bit;
        }
        if (neighborIsPipe) {
            setNeighborPipeDisconnected(level, neighborPos, neighborState, side.getOpposite(), target == MODE_DISCONNECTED);
        }
        // Step 67 (zerobit): a side that stops pulling keeps its upgrade "parked" (it does nothing until the side pulls
        // again) and keeps its pull settings, so switching back restores everything. Parked upgrades show in the insert
        // screen and the Pipe sides screen, tint the insert groove, and drop when the pipe breaks.

        applyConfig(level, pos, extract, disconnect);
        if (neighborIsInventory && target == MODE_PULL) {
            // Remember which end it came from, so the next click goes on to the other one (step 41).
            PipeDataBlockEntity pullData = getData(level, pos, level.getBlockState(pos));
            if (pullData != null) {
                pullData.setPullCameFromInsert(side, current == MODE_NORMAL);
            }
        }
        // The neighbor pipe may have been waiting on our settings; recompute it now that both are stored.
        if (neighborIsPipe) {
            BlockState updatedNeighbor = level.getBlockState(neighborPos);
            applyConfig(level, neighborPos, extractMask(level, neighborPos, updatedNeighbor),
                    disconnectMask(level, neighborPos, updatedNeighbor));
        }
        if (!announce) {
            return;
        }
        // "Normal" reads as Insert next to an inventory and Connected next to another pipe (zerobit, step 23a).
        String modeKey = MODE_KEYS[target];
        if (target == MODE_NORMAL && neighborIsInventory) {
            modeKey = "insert";
        } else if (target == MODE_NORMAL && neighborIsPipe) {
            modeKey = "connected";
        }
        player.displayClientMessage(Component.translatable("message.daisychain.side_mode",
                Component.translatable("message.daisychain.direction." + side.getSerializedName()),
                Component.translatable("message.daisychain.mode." + modeKey)), true);
    }

    /**
     * Step 41: insert -> pull and off -> pull; from pull, on to the end it didn't come from. A pull side set some other
     * way (nothing remembered) goes back to insert.
     */
    private static int nextInventoryMode(ServerLevel level, BlockPos pos, BlockState state, Direction side, int current) {
        if (current != MODE_PULL) {
            return MODE_PULL;
        }
        PipeDataBlockEntity data = getData(level, pos, state);
        return data != null && data.pullCameFromInsert(side) ? MODE_DISCONNECTED : MODE_NORMAL;
    }

    private void setNeighborPipeDisconnected(ServerLevel level, BlockPos neighborPos, BlockState neighborState,
                                             Direction sideTowardUs, boolean disconnected) {
        int extract = extractMask(level, neighborPos, neighborState);
        int disconnect = disconnectMask(level, neighborPos, neighborState);
        int bit = 1 << sideTowardUs.get3DDataValue();
        disconnect = disconnected ? (disconnect | bit) : (disconnect & ~bit);
        storeMasks(level, neighborPos, extract, disconnect);
    }

    /** Saves the masks into the block entity, creating it first if needed. Does not recompute the arms. */
    private void storeMasks(ServerLevel level, BlockPos pos, int extract, int disconnect) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return;
        }
        boolean needsData = (extract | disconnect) != 0 || hasStoredSettings(level, pos, state);
        if (needsData && !state.getValue(HAS_DATA)) {
            // Only adds the block entity; the arms are recomputed afterwards by applyConfig.
            level.setBlock(pos, state.setValue(HAS_DATA, true), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        if (level.getBlockEntity(pos) instanceof PipeDataBlockEntity data) {
            data.setMasks(extract, disconnect);
        }
    }

    /**
     * Server: an upgrade, filter or setting changed, which can decide whether a pull side with no inventory stays drawn.
     * Recomputes the arms with the current masks.
     */
    public static void refreshArms(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FlowPipeBlock pipe) {
            pipe.applyConfig(level, pos, extractMask(level, pos, state), disconnectMask(level, pos, state));
        }
    }

    /** Saves the masks and updates the pipe's arms to match. */
    private void applyConfig(ServerLevel level, BlockPos pos, int extract, int disconnect) {
        storeMasks(level, pos, extract, disconnect);
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return;
        }
        // Installed upgrades and insert settings also keep the block entity alive, so they're never lost.
        boolean needsData = (extract | disconnect) != 0 || hasStoredSettings(level, pos, state);
        BlockState newState = computeAllSides(level, pos, state.setValue(HAS_DATA, needsData), extract, disconnect);
        if (newState != state) {
            level.setBlock(pos, newState, Block.UPDATE_ALL);
        } else {
            // Arms unchanged, but clients still need the new settings.
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        // Called for new pipes and for arm/settings changes on existing pipes.
        if (level instanceof ServerLevel serverLevel && oldState != state) {
            NetworkManager.get(serverLevel).onPipeChanged(pos);
            // Step 101: a pipe put here by something else (a building tool's paste, a schematic) can say "pull side" in
            // its block state with no pull settings stored. Check again in 2 ticks (our own code stores the settings by
            // then, e.g. a tier swap) and, if it still doesn't match, work the arms out from what is stored.
            if (!oldState.is(state.getBlock()) && armsDisagreeWithSettings(serverLevel, pos, state)) {
                serverLevel.scheduleTick(pos, this, 2);
            }
        }
    }

    /** Step 101: a side drawn as a pull side that isn't one in the stored settings. */
    private static boolean armsDisagreeWithSettings(ServerLevel level, BlockPos pos, BlockState state) {
        int extract = extractMask(level, pos, state);
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(propertyFor(direction)) == SideState.EXTRACT && !hasBit(extract, direction)) {
                return true;
            }
        }
        return false;
    }

    /** Step 101: only scheduled by onPlace for a pasted pipe whose arms disagree with its settings (pipes never tick). */
    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (armsDisagreeWithSettings(level, pos, state)) {
            refreshArms(level, pos);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // A freshly placed pipe: forget old "disconnected" marks that neighbor pipes kept toward this spot.
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            if (!level.isLoaded(neighborPos)) {
                continue;
            }
            BlockState neighborState = level.getBlockState(neighborPos);
            if (linksWith(neighborState)
                    && hasBit(extractMask(level, neighborPos, neighborState), direction.getOpposite())) {
                // The neighbor pulled from this spot. Pipes connect instead of pulling from each other, so its pull
                // mark goes; its filter and amounts stay stored, and its upgrade pops out so it's never hidden.
                dropPullTowardNewPipe(serverLevel, neighborPos, neighborState, direction.getOpposite());
                neighborState = level.getBlockState(neighborPos);
            }
            if (linksWith(neighborState)) {
                // Step 67: a parked upgrade on a side that now joins another pipe could never work again: pop it out.
                popParkedUpgrade(serverLevel, neighborPos, neighborState, direction.getOpposite());
                neighborState = level.getBlockState(neighborPos);
            }
            if (linksWith(neighborState)
                    && hasBit(disconnectMask(level, neighborPos, neighborState), direction.getOpposite())) {
                setNeighborPipeDisconnected(serverLevel, neighborPos, neighborState, direction.getOpposite(), false);
                BlockState updated = level.getBlockState(neighborPos);
                applyConfig(serverLevel, neighborPos, extractMask(level, neighborPos, updated),
                        disconnectMask(level, neighborPos, updated));
            }
        }
    }

    /** Step 67: drops a parked (not pulling) upgrade on that side as an item at the pipe. */
    private static void popParkedUpgrade(ServerLevel level, BlockPos pipePos, BlockState pipeState, Direction side) {
        PipeDataBlockEntity data = getData(level, pipePos, pipeState);
        if (data == null || hasBit(extractMask(level, pipePos, pipeState), side)) {
            return;
        }
        UpgradeItem item = ModItems.upgradeFor(data.upgradeLevel(side));
        if (item != null) {
            data.setUpgradeLevel(side, 0);
            Block.popResource(level, pipePos, new ItemStack(item));
        }
    }

    /** A same-type pipe was placed where this pipe's pull side pointed: clear the pull mark, pop the upgrade. */
    private void dropPullTowardNewPipe(ServerLevel level, BlockPos pipePos, BlockState pipeState, Direction side) {
        PipeDataBlockEntity data = getData(level, pipePos, pipeState);
        if (data == null) {
            return;
        }
        int upgrade = data.upgradeLevel(side);
        if (upgrade > 0) {
            data.setUpgradeLevel(side, 0);
            UpgradeItem item = ModItems.upgradeFor(upgrade);
            if (item != null) {
                Block.popResource(level, pipePos, new ItemStack(item));
            }
        }
        BlockState updated = level.getBlockState(pipePos);
        applyConfig(level, pipePos, extractMask(level, pipePos, updated) & ~(1 << side.get3DDataValue()),
                disconnectMask(level, pipePos, updated));
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        boolean sameBlock = state.is(newState.getBlock());
        if (level instanceof ServerLevel serverLevel && !sameBlock) {
            NetworkManager.get(serverLevel).onPipeChanged(pos);
            // Broken, exploded or replaced: drop any installed upgrades so they're never deleted. Not during a tier swap
            // (step 93): the settings, upgrades included, move to the new pipe there.
            PipeDataBlockEntity data = getData(level, pos, state);
            if (data != null && !swappingTier) {
                data.dropUpgrades(level, pos);
            }
        }
        // Same pipe but no settings left: drop the block entity so plain pipes stay cheap.
        if (sameBlock && state.getValue(HAS_DATA) && !newState.getValue(HAS_DATA)) {
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Step 95: the click landed on a drawn pull cap (a pull side's arm, not the pipe's middle). Uses only the block state,
     * so client and server always agree.
     */
    public static boolean isPullCapHit(BlockState state, BlockPos pos, BlockHitResult hit) {
        if (!(state.getBlock() instanceof FlowPipeBlock) || isCoreHit(state, pos, hit.getLocation())) {
            return false;
        }
        return state.getValue(propertyFor(targetedSide(state, pos, hit))) == SideState.EXTRACT;
    }

    /** Step 93: true only while EnergyPipeBlock.swapInPlace replaces a pipe (server thread). */
    static boolean swappingTier;

    // ---------------------------------------------------------------- upgrades

    /** Right-click a pull side with an upgrade: installs it, swapping out (and giving back) any other tier. */
    private void installUpgrade(ServerLevel level, BlockPos pos, BlockState state, Direction side, Player player,
                                ItemStack stack, UpgradeItem upgrade) {
        if (this instanceof EnergyPipeBlock) {
            // Step 84: energy pipes get their speed from their tier (craft faster pipes), not from upgrades.
            player.displayClientMessage(Component.translatable("message.daisychain.upgrade_energy"), true);
            return;
        }
        if (state.getValue(propertyFor(side)) != SideState.EXTRACT) {
            player.displayClientMessage(Component.translatable("message.daisychain.upgrade_pull_only"), true);
            return;
        }
        PipeDataBlockEntity data = getData(level, pos, state);
        if (data == null) {
            return;
        }
        int current = data.upgradeLevel(side);
        int newLevel = upgrade.tier().level();
        if (current == newLevel) {
            player.displayClientMessage(Component.translatable("message.daisychain.upgrade_already"), true);
            return;
        }
        if (newLevel < current) {
            // Upgrades only ever go up (zerobit): to fit a lower tier, take the better one out first.
            player.displayClientMessage(Component.translatable("message.daisychain.upgrade_lower"), true);
            return;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        giveUpgradeBack(player, current);
        data.setUpgradeLevel(side, newLevel);
        player.displayClientMessage(Component.translatable("message.daisychain.upgrade_installed",
                Component.translatable("message.daisychain.direction." + side.getSerializedName()),
                Component.translatable("upgrade.daisychain.tier." + upgrade.tier().id())), true);
    }

    /** Takes the side's upgrade out: into an empty main hand, else the inventory, else dropped at the player's feet. */
    private void removeUpgrade(ServerLevel level, BlockPos pos, Direction side, Player player) {
        BlockState state = level.getBlockState(pos);
        PipeDataBlockEntity data = getData(level, pos, state);
        if (data == null) {
            return;
        }
        int current = data.upgradeLevel(side);
        UpgradeItem item = ModItems.upgradeFor(current);
        if (item == null) {
            return;
        }
        data.setUpgradeLevel(side, 0);
        ItemStack removed = new ItemStack(item);
        if (player.getMainHandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, removed);
        } else if (!player.getInventory().add(removed)) {
            player.drop(removed, false);
        }
        player.displayClientMessage(Component.translatable("message.daisychain.upgrade_removed",
                Component.translatable("upgrade.daisychain.tier." + UpgradeTier.byLevel(current).id())), true);
        // If nothing else needs the settings data any more, let the pipe drop it again.
        BlockState updated = level.getBlockState(pos);
        applyConfig(level, pos, extractMask(level, pos, updated), disconnectMask(level, pos, updated));
    }

    /**
     * Sneak + right-click a pipe with upgrades: installs one into every pull side on that network that has a
     * lower tier (or none), until the stack runs out. Replaced upgrades go back to the player.
     */
    public static void installOnNetwork(ServerLevel level, BlockPos pos, Player player, ItemStack stack, UpgradeItem upgrade) {
        if (level.getBlockState(pos).getBlock() instanceof EnergyPipeBlock) {
            player.displayClientMessage(Component.translatable("message.daisychain.upgrade_energy"), true);
            return;
        }
        PipeNetwork network = NetworkManager.get(level).getOrBuild(pos);
        if (network == null) {
            return;
        }
        boolean creative = player.getAbilities().instabuild;
        int newLevel = upgrade.tier().level();
        int installed = 0;
        for (PipeNetwork.Endpoint endpoint : network.endpoints()) {
            if (!creative && stack.isEmpty()) {
                break;
            }
            if (!endpoint.extract()) {
                continue;
            }
            BlockPos pipePos = endpoint.pipePos();
            PipeDataBlockEntity data = getData(level, pipePos, level.getBlockState(pipePos));
            if (data == null || data.upgradeLevel(endpoint.side()) >= newLevel) {
                continue;
            }
            int current = data.upgradeLevel(endpoint.side());
            if (!creative) {
                stack.shrink(1);
            }
            giveUpgradeBack(player, current);
            data.setUpgradeLevel(endpoint.side(), newLevel);
            installed++;
        }
        player.displayClientMessage(installed > 0
                ? Component.translatable("message.daisychain.network_upgraded", installed)
                : Component.translatable("message.daisychain.network_nothing"), true);
    }

    /** Gives a replaced upgrade back to the player (or drops it at their feet if their inventory is full). */
    public static void giveUpgradeBack(Player player, int level) {
        UpgradeItem item = ModItems.upgradeFor(level);
        if (item == null) {
            return;
        }
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    // ---------------------------------------------------------------- mining

    /** Ticks to break a pipe: the same with any tool, enchantment, Haste or Mining Fatigue (4 ticks = 0.2 s). */
    private static final float BREAK_TICKS = 3.5F;

    /**
     * Fixed break speed. Vanilla would make bare hands slow and pickaxes near-instant, and apply Haste, Mining
     * Fatigue, underwater and in-air penalties; pipes ignore all of that. (Creative mode still breaks instantly.)
     */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return 1.0F / BREAK_TICKS;
    }

    // ---------------------------------------------------------------- shapes

    /**
     * Outline and click shape. A player holding a wrench also gets the "ghost" arms of disconnected sides, so they can
     * be clicked to reconnect (and the client draws them, see client/WrenchGhostRenderer). Reuses the 64 prebuilt
     * shapes: no allocation.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Step 66: only while sneaking, since only a sneak-click changes a side (a plain click opens the settings).
        // And not while the player has the holograms turned off (client side only; toggled with the Bloom Wrench).
        boolean wrench = context instanceof EntityCollisionContext entityContext
                && entityContext.getEntity() instanceof Player player && isHoldingWrench(player)
                && player.isSecondaryUseActive() && !(clientGhostsHidden && player.level().isClientSide());
        return cachedShape(connectionMask(state), pullMask(state), wrench ? GHOST_ALL : GHOST_NONE);
    }

    /** What entities bump into: never includes ghost arms. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return cachedShape(connectionMask(state), pullMask(state), GHOST_NONE);
    }

    /** Set every client tick from the hologram setting. Never set on a dedicated server. */
    private static volatile boolean clientGhostsHidden;

    public static void setClientGhostsHidden(boolean hidden) {
        clientGhostsHidden = hidden;
    }

    public static boolean clientGhostsHidden() {
        return clientGhostsHidden;
    }

    /** True if the player's main hand holds a wrench (the hand that clicks pipes). */
    public static boolean isHoldingWrench(Player player) {
        return ModTags.isWrench(player.getMainHandItem());
    }

    /**
     * Sides set to disconnected on this pipe (one bit per side). Only reads the pipe's own block entity, never a
     * neighbor, so it can't load chunks.
     */
    public static int ghostArmMask(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return 0;
        }
        // Step 34a (zerobit): every side without an arm gets a ghost arm, so any side can be clicked with the wrench.
        return ~connectionMask(state) & 0b111111;
    }

    /**
     * Test worlds only (/daisychain stress, step 35): makes exactly these sides pull and recomputes the arms, as if each had
     * been wrenched. Server only.
     */
    public static void setPullSidesForTest(ServerLevel level, BlockPos pos, int pullMask) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FlowPipeBlock pipe) {
            pipe.applyConfig(level, pos, pullMask & 0b111111, disconnectMask(level, pos, state));
        }
    }

    /**
     * The pipe's outline as drawn (node, tube, pull caps) plus at most one ghost arm (ghostMask = 0 or one side bit).
     * Cached, never allocates after the first use.
     */
    public static VoxelShape shapeFor(BlockState state, int ghostMask) {
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return Shapes.block();
        }
        int ghost = ghostMask == 0 ? GHOST_NONE : Integer.numberOfTrailingZeros(ghostMask) & 7;
        return cachedShape(connectionMask(state), pullMask(state), Math.min(ghost, GHOST_NONE));
    }

    /** The arms this pipe really has (connected or pulling sides), as side bits. */
    public static int armMask(BlockState state) {
        return state.getBlock() instanceof FlowPipeBlock ? connectionMask(state) : 0;
    }

    /** Sides set to disconnected (Off): their ghost arms are drawn red instead of blue. Client and server. */
    public static int offMask(BlockGetter level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof FlowPipeBlock ? disconnectMask(level, pos, state) & 0b111111 : 0;
    }

    /** The box of one arm, in block coordinates (0 to 1). Index = Direction.get3DDataValue(). */
    public static AABB armBounds(int side) {
        return ARM_BOUNDS[side];
    }

    /** The middle cube (5 to 11 px), in block coordinates (0 to 1). */
    public static AABB coreBounds() {
        return CORE_BOUNDS;
    }

    private static int connectionMask(BlockState state) {
        int mask = 0;
        for (int i = 0; i < PROPERTY_BY_DIRECTION.length; i++) {
            if (state.getValue(PROPERTY_BY_DIRECTION[i]) != SideState.NONE) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private static int pullMask(BlockState state) {
        int mask = 0;
        for (int i = 0; i < PROPERTY_BY_DIRECTION.length; i++) {
            if (state.getValue(PROPERTY_BY_DIRECTION[i]) == SideState.EXTRACT) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private static VoxelShape cachedShape(int arms, int pulls, int ghost) {
        int index = arms | pulls << 6 | ghost << 12;
        VoxelShape shape = SHAPE_CACHE[index];
        if (shape == null) {
            shape = buildShape(arms, pulls, ghost);
            SHAPE_CACHE[index] = shape;
        }
        return shape;
    }

    /**
     * Same as the model (client/PipeBakedModel, step 55): the 6 px core plus an arm per connection. Pull sides add the
     * cap and ring.
     */
    private static VoxelShape buildShape(int arms, int pulls, int ghost) {
        VoxelShape shape = CORE;
        for (int i = 0; i < 6; i++) {
            if ((arms & (1 << i)) != 0) {
                shape = Shapes.or(shape, ARMS[i]);
            }
        }
        for (int i = 0; i < 6; i++) {
            if ((pulls & (1 << i)) != 0) {
                shape = Shapes.or(shape, PULL_CAPS[i]);
            }
        }
        int ghosts = ghost == GHOST_ALL ? ~arms & 0b111111 : ghost < GHOST_NONE ? (1 << ghost) & ~arms : 0;
        for (int i = 0; i < 6; i++) {
            if ((ghosts & (1 << i)) != 0) {
                shape = Shapes.or(shape, ARMS[i]);
            }
        }
        return shape.optimize();
    }
}
