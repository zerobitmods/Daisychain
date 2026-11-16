package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.CoverShape;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FacadeMassApply;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.item.FacadeItem;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Step F53 (port of step 125, facades step 1): a cover goes on, swaps, comes off and drops exactly once; never lost on a tier swap;
 * saved and synced; the recipe takes only full blocks. Same rules as the other tests: the empty 9x4x5 space, y 1 is
 * the floor row.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FacadeTests {
    private static final String EMPTY = "empty";
    private static final BlockPos PIPE = new BlockPos(4, 1, 2);
    private static final BlockState OAK = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE_BRICKS.defaultBlockState();

    private FacadeTests() {
    }

    /** Right-click covers the pipe and uses one facade; sneak + empty hand puts it back in the hand, nothing drops. */
    @GameTest(template = EMPTY)
    public static void facadeOnAndOff(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer();
        ItemStack facades = FacadeItem.of(OAK, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, facades);
        helper.assertTrue(click(helper, player, facades).consumesAction(), "Click wasn't handled");
        helper.assertTrue(cover(helper, PIPE) == OAK, "Cover is " + cover(helper, PIPE) + " (should be oak)");
        helper.assertTrue(facades.getCount() == 1, "Facades left: " + facades.getCount() + " (should be 1)");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        click(helper, player, ItemStack.EMPTY);
        ItemStack inHand = player.getMainHandItem();
        helper.assertTrue(cover(helper, PIPE) == null, "The cover is still on");
        helper.assertTrue(inHand.is(ModItems.FACADE.get()) && FacadeItem.coverOf(inHand) == OAK && inHand.getCount() == 1,
                "Hand holds " + inHand + " (should be 1 oak facade)");
        helper.assertTrue(!helper.getBlockState(PIPE).getValue(BasePipeBlock.HAS_DATA),
                "The pipe kept its settings storage with nothing in it");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /**
     * Step F53a: a removed facade joins a stack of the same facades in the inventory instead of taking the empty hand
     * slot each time; a facade with no stack to join still lands in the hand.
     */
    @GameTest(template = EMPTY)
    public static void facadeRemovalStacks(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer();
        player.getInventory().setItem(5, FacadeItem.of(OAK, 3));
        player.setShiftKeyDown(true);
        for (int i = 0; i < 2; i++) {
            coverWithSurvivalPlayer(helper, PIPE, OAK);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            click(helper, player, ItemStack.EMPTY);
            helper.assertTrue(cover(helper, PIPE) == null, "The cover is still on");
        }
        ItemStack stack = player.getInventory().getItem(5);
        helper.assertTrue(FacadeItem.coverOf(stack) == OAK && stack.getCount() == 5,
                "Slot 5 holds " + stack + " (should be 5 oak facades)");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "A facade went to the hand instead of onto the stack");
        helper.assertTrue(facadesHeld(player, OAK) == 5, "Oak facades held: " + facadesHeld(player, OAK) + " (should be 5)");

        coverWithSurvivalPlayer(helper, PIPE, STONE);
        click(helper, player, ItemStack.EMPTY);
        helper.assertTrue(FacadeItem.coverOf(player.getMainHandItem()) == STONE, "The stone facade isn't in the hand");
        helper.assertTrue(player.getInventory().getItem(5).getCount() == 5, "The oak stack changed");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /** Right-click a covered pipe with another facade: the new one goes on, the old one comes back, nothing drops. */
    @GameTest(template = EMPTY)
    public static void facadeSwapGivesOldBack(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer();
        ItemStack oak = FacadeItem.of(OAK, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, oak);
        click(helper, player, oak);
        ItemStack stone = FacadeItem.of(STONE, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, stone);
        click(helper, player, stone);
        helper.assertTrue(cover(helper, PIPE) == STONE, "Cover is " + cover(helper, PIPE) + " (should be stone bricks)");
        helper.assertTrue(stone.isEmpty(), "The stone facade wasn't used");
        helper.assertTrue(facadesHeld(player, OAK) == 1, "Oak facades given back: " + facadesHeld(player, OAK));
        helper.assertTrue(facadesHeld(player, STONE) == 0, "A stone facade came back too");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /** The same facade again: nothing changes, nothing is used. */
    @GameTest(template = EMPTY)
    public static void facadeSameTwiceUsesOne(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer();
        ItemStack oak = FacadeItem.of(OAK, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, oak);
        click(helper, player, oak);
        click(helper, player, oak);
        helper.assertTrue(oak.getCount() == 1, "Facades left: " + oak.getCount() + " (should be 1)");
        helper.assertTrue(facadesHeld(player, OAK) == 1, "Facades held: " + facadesHeld(player, OAK) + " (should be 1)");
        helper.succeed();
    }

    /** Players who can't build (adventure mode) can't put a cover on or take it off. */
    @GameTest(template = EMPTY)
    public static void facadeNeedsBuildRights(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer();
        player.getAbilities().mayBuild = false; // like adventure mode (1.20.1's mock player has no game type)
        ItemStack oak = FacadeItem.of(OAK, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, oak);
        click(helper, player, oak);
        helper.assertTrue(cover(helper, PIPE) == null, "An adventure player put a cover on");
        helper.assertTrue(oak.getCount() == 1, "An adventure player's facade was used");

        coverWithSurvivalPlayer(helper, PIPE, OAK);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        click(helper, player, ItemStack.EMPTY);
        helper.assertTrue(cover(helper, PIPE) == OAK, "An adventure player took the cover off");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "An adventure player got a facade");
        helper.succeed();
    }

    /** A facade holding a block that can't be a cover (edited in creative): refused, not used. */
    @GameTest(template = EMPTY)
    public static void facadeOfBadBlockRefused(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer();
        ItemStack stairs = FacadeItem.of(Blocks.OAK_STAIRS.defaultBlockState(), 1);
        ItemStack empty = new ItemStack(ModItems.FACADE.get());
        for (ItemStack bad : List.of(stairs, empty)) {
            player.setItemInHand(InteractionHand.MAIN_HAND, bad);
            click(helper, player, bad);
            helper.assertTrue(cover(helper, PIPE) == null, "A bad facade went on: " + bad);
            helper.assertTrue(bad.getCount() == 1, "A bad facade was used: " + bad);
        }
        helper.succeed();
    }

    /** Breaking a covered pipe drops the pipe and exactly one facade. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void facadeDropsOnBreak(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        helper.getLevel().destroyBlock(helper.absolutePos(PIPE), true);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(facadesOnGround(helper, OAK) == 1, facadesOnGround(helper, OAK) + " oak facades dropped");
            helper.assertTrue(groundCount(helper, ModItems.ITEM_PIPE.get()) == 1, "The pipe didn't drop");
            helper.succeed();
        });
    }

    /** A covered pipe replaced by another block (/setblock, a building tool) drops exactly one facade. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void facadeDropsWhenReplaced(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        helper.setBlock(PIPE, Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(facadesOnGround(helper, OAK) == 1, facadesOnGround(helper, OAK) + " oak facades dropped");
            helper.succeed();
        });
    }

    /** A covered pipe blown up drops exactly one facade. Own batch, so the blast can't reach other tests. */
    @GameTest(template = EMPTY, batch = "facadeExplosion", timeoutTicks = 40)
    public static void facadeDropsOnExplosion(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(PIPE));
        helper.getLevel().explode(null, center.x, center.y, center.z, 4.0F, Level.ExplosionInteraction.BLOCK);
        helper.runAfterDelay(2, () -> {
            helper.assertBlockNotPresent(ModBlocks.ITEM_PIPE.get(), PIPE);
            helper.assertTrue(facadesOnGround(helper, OAK) == 1, facadesOnGround(helper, OAK) + " oak facades dropped");
            helper.succeed();
        });
    }

    /** An energy pipe tier swap keeps the cover on the new pipe; no facade drops or comes back. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void facadeKeptOnTierSwap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        pipe(helper, PIPE, ModBlocks.energyPipes()[0]);
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        Player player = helper.makeMockPlayer();
        ItemStack gold = new ItemStack(ModBlocks.energyPipes()[2], 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, gold);
        BlockPos at = helper.absolutePos(PIPE);
        boolean swapped = EnergyPipeBlock.swapInPlace(level, at, level.getBlockState(at), player, gold);
        helper.assertTrue(swapped, "The tier swap didn't happen");
        helper.assertBlockPresent(ModBlocks.energyPipes()[2], PIPE);
        helper.assertTrue(cover(helper, PIPE) == OAK, "Cover after the swap: " + cover(helper, PIPE));
        helper.assertTrue(level.getBlockState(at).getValue(BasePipeBlock.COVER) == CoverShape.SOLID,
                "Cover state after the swap: " + level.getBlockState(at)); // step 127
        helper.assertTrue(facadesHeld(player, OAK) == 0, "A facade came back on the swap");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(facadesOnGround(helper, OAK) == 0, "A facade dropped on the swap");
            helper.succeed();
        });
    }

    /** On a covered pull side with an upgrade, the cover comes off first, the upgrade on the next click. */
    @GameTest(template = EMPTY)
    public static void facadeComesOffBeforeUpgrade(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(PIPE), 1 << Direction.SOUTH.get3DDataValue());
        data(helper, PIPE).setUpgradeLevel(Direction.SOUTH, 2);
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        Player player = helper.makeMockPlayer();
        player.setShiftKeyDown(true);
        BlockPos at = helper.absolutePos(PIPE);
        BlockHitResult cap = new BlockHitResult(Vec3.atCenterOf(at).add(0, 0, 0.45), Direction.SOUTH, at, false);

        clickAt(helper, player, ItemStack.EMPTY, cap);
        helper.assertTrue(cover(helper, PIPE) == null, "First click left the cover on");
        helper.assertTrue(data(helper, PIPE).upgradeLevel(Direction.SOUTH) == 2, "First click took the upgrade");
        helper.assertTrue(FacadeItem.coverOf(player.getMainHandItem()) == OAK, "The facade isn't in the hand");

        player.getInventory().add(player.getMainHandItem().copy()); // put it away, as a player would
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        clickAt(helper, player, ItemStack.EMPTY, cap);
        helper.assertTrue(data(helper, PIPE).upgradeLevel(Direction.SOUTH) == 0, "Second click left the upgrade in");
        helper.assertTrue(player.getMainHandItem().is(ModItems.GOLD_UPGRADE.get()), "The upgrade isn't in the hand");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /** The cover survives a disk save and a client update (both read back exactly). */
    @GameTest(template = EMPTY)
    public static void facadeSavesAndSyncs(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, Blocks.OAK_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, Direction.Axis.X));
        PipeDataBlockEntity data = data(helper, PIPE);
        BlockState expected = data.cover();
        CompoundTag disk = data.saveWithoutMetadata();
        CompoundTag update = data.getUpdateTag();
        BlockPos at = helper.absolutePos(PIPE);
        for (CompoundTag tag : List.of(disk, update)) {
            PipeDataBlockEntity copy = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
            copy.load(tag);
            helper.assertTrue(copy.cover() == expected, "Read back " + copy.cover() + " (should be " + expected + ")");
        }
        // And no cover reads back as none (a client update after removal must clear it).
        data.setCover(null);
        PipeDataBlockEntity copy = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
        copy.load(disk);
        copy.load(data.getUpdateTag());
        helper.assertTrue(copy.cover() == null, "A removed cover came back from an update");
        helper.succeed();
    }

    /** Block + item pipe makes a facade of that block; stairs, chests, two pipes or a lone block make nothing. */
    /** Step F54 (126): the block entity hands its cover to the pipe model (model data), and nothing once it's off. */
    @GameTest(template = EMPTY)
    public static void facadeCoverReachesModel(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        PipeDataBlockEntity data = data(helper, PIPE);
        helper.assertTrue(data.getModelData().get(PipeDataBlockEntity.COVER_MODEL) == OAK,
                "Model data doesn't carry the oak cover");
        data.setCover(STONE);
        helper.assertTrue(data.getModelData().get(PipeDataBlockEntity.COVER_MODEL) == STONE,
                "Model data didn't follow the swap to stone bricks");
        data.setCover(null);
        helper.assertTrue(data.getModelData().get(PipeDataBlockEntity.COVER_MODEL) == null,
                "Model data still has a cover after it came off");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void facadeRecipe(GameTestHelper helper) {
        ItemStack pipe = new ItemStack(ModItems.ITEM_PIPE.get());
        ItemStack made = craft(helper, new ItemStack(Items.OAK_PLANKS), pipe);
        helper.assertTrue(made.is(ModItems.FACADE.get()) && FacadeItem.coverOf(made) == OAK
                && made.getCount() == 8, "Oak planks + pipe made " + made);
        ItemStack glass = craft(helper, pipe, new ItemStack(Items.GLASS));
        helper.assertTrue(FacadeItem.coverOf(glass) == Blocks.GLASS.defaultBlockState(), "Glass + pipe made " + glass);
        // Not "nothing at all": a lone plank still makes a button, for example. Just never a facade.
        helper.assertTrue(!craft(helper, new ItemStack(Items.OAK_STAIRS), pipe).is(ModItems.FACADE.get()),
                "Stairs made a facade");
        helper.assertTrue(!craft(helper, new ItemStack(Items.CHEST), pipe).is(ModItems.FACADE.get()),
                "A chest made a facade");
        helper.assertTrue(!craft(helper, pipe, pipe).is(ModItems.FACADE.get()), "Two pipes made a facade");
        helper.assertTrue(!craft(helper, new ItemStack(Items.OAK_PLANKS), ItemStack.EMPTY).is(ModItems.FACADE.get()),
                "Planks alone made a facade");
        helper.assertTrue(!craft(helper, new ItemStack(Items.OAK_PLANKS), new ItemStack(ModItems.FLUID_PIPE.get()))
                .is(ModItems.FACADE.get()), "A fluid pipe made a facade");
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    // ---------------------------------------------------------------- step 127 (facades step 3)

    /** An opaque cover makes a full, light-blocking block; taking it off gives the plain pipe back. */
    @GameTest(template = EMPTY)
    public static void facadeSolidIsFullBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(PIPE);
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        helper.assertFalse(level.getBlockState(at).isCollisionShapeFullBlock(level, at), "Plain pipe is a full block");
        coverWithSurvivalPlayer(helper, PIPE, STONE);
        BlockState state = level.getBlockState(at);
        helper.assertTrue(state.getValue(BasePipeBlock.COVER) == CoverShape.SOLID, "Cover state: " + state);
        helper.assertTrue(state.isCollisionShapeFullBlock(level, at), "Covered pipe can be walked into");
        helper.assertTrue(Block.isShapeFullBlock(state.getShape(level, at)), "Outline isn't a full block");
        helper.assertTrue(state.getLightBlock(level, at) == 15, "Light through stone cover: " + state.getLightBlock(level, at));
        helper.assertFalse(state.propagatesSkylightDown(level, at), "Sky light goes through a stone cover");
        helper.assertTrue(state.isRedstoneConductor(level, at), "Stone cover doesn't conduct redstone");
        Player player = helper.makeMockPlayer();
        player.setShiftKeyDown(true);
        clickAt(helper, player, ItemStack.EMPTY, centerHit(helper, PIPE));
        state = level.getBlockState(at);
        helper.assertTrue(cover(helper, PIPE) == null, "Cover didn't come off");
        helper.assertTrue(state.getValue(BasePipeBlock.COVER) == CoverShape.NONE, "Cover state after removal: " + state);
        helper.assertFalse(state.isCollisionShapeFullBlock(level, at), "Still a full block after removal");
        helper.assertTrue(state.getLightBlock(level, at) == 0, "Bare pipe blocks light: " + state.getLightBlock(level, at));
        helper.succeed();
    }

    /** A glass cover is solid to walk on but lets light through, and doesn't conduct redstone or suffocate. */
    @GameTest(template = EMPTY)
    public static void facadeGlassLetsLightThrough(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(PIPE);
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, Blocks.GLASS.defaultBlockState());
        BlockState state = level.getBlockState(at);
        helper.assertTrue(state.getValue(BasePipeBlock.COVER) == CoverShape.CLEAR, "Cover state: " + state);
        helper.assertTrue(state.isCollisionShapeFullBlock(level, at), "Glass-covered pipe can be walked into");
        helper.assertTrue(state.getLightBlock(level, at) == 0, "Glass cover blocks light: " + state.getLightBlock(level, at));
        helper.assertTrue(state.propagatesSkylightDown(level, at), "Sky light stops at a glass cover");
        helper.assertFalse(state.isRedstoneConductor(level, at), "Glass cover conducts redstone");
        helper.assertFalse(state.isSuffocating(level, at), "Glass cover suffocates");
        helper.succeed();
    }

    /**
     * On a covered pipe the clicked face is the side, anywhere on the face: a corner of the east face counts as the
     * east pull side. On a bare pipe the same point isn't on the pull cap.
     */
    @GameTest(template = EMPTY)
    public static void facadeFaceClickPicksSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(PIPE);
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        BasePipeBlock.setPullSidesForTest(level, at, 1 << Direction.EAST.get3DDataValue());
        Vec3 corner = new Vec3(at.getX() + 1.0, at.getY() + 0.1, at.getZ() + 0.9);
        BlockHitResult hit = new BlockHitResult(corner, Direction.EAST, at, false);
        BlockState bare = level.getBlockState(at);
        helper.assertTrue(BasePipeBlock.isCoreHit(bare, at, corner), "Setup: bare pipe corner isn't the core");
        helper.assertFalse(BasePipeBlock.isPullCapHit(bare, at, hit), "Setup: bare pipe corner is on the pull cap");
        coverWithSurvivalPlayer(helper, PIPE, STONE);
        BlockState covered = level.getBlockState(at);
        helper.assertFalse(BasePipeBlock.isCoreHit(covered, at, corner), "Covered face corner counts as the core");
        helper.assertTrue(BasePipeBlock.targetedSide(covered, at, hit) == Direction.EAST,
                "Covered face corner side: " + BasePipeBlock.targetedSide(covered, at, hit));
        helper.assertTrue(BasePipeBlock.isPullCapHit(covered, at, hit), "Covered pull face isn't the pull side");
        Vec3 top = new Vec3(at.getX() + 0.2, at.getY() + 1.0, at.getZ() + 0.2);
        helper.assertTrue(BasePipeBlock.targetedSide(covered, at, top, Direction.UP) == Direction.UP, "Top face side");
        helper.succeed();
    }

    /**
     * A covered pipe whose block state lost its cover value (saved before step 127) gets it back on its next tick; a
     * pipe pasted as "covered" with no cover stored goes back to plain.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void facadeCoverStateRepaired(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(PIPE);
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, STONE);
        level.setBlock(at, level.getBlockState(at).setValue(BasePipeBlock.COVER, CoverShape.NONE), Block.UPDATE_CLIENTS);
        helper.assertTrue(cover(helper, PIPE) == STONE, "Setup: the stored cover went away");
        level.scheduleTick(at, level.getBlockState(at).getBlock(), 1); // what the chunk-load scan does
        BlockPos other = new BlockPos(2, 1, 2);
        BlockPos otherAt = helper.absolutePos(other);
        helper.setBlock(other, ModBlocks.ITEM_PIPE.get().defaultBlockState().setValue(BasePipeBlock.COVER, CoverShape.SOLID));
        helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockState(at).getValue(BasePipeBlock.COVER) == CoverShape.SOLID,
                    "Cover state not repaired: " + level.getBlockState(at));
            helper.assertTrue(level.getBlockState(otherAt).getValue(BasePipeBlock.COVER) == CoverShape.NONE,
                    "Pasted cover state with nothing stored: " + level.getBlockState(otherAt));
        });
    }

    // ---------------------------------------------------------------- step F61 (port of 134 / 134a, transparent facade)

    /** A plain glass pane + item pipe makes 8 transparent facades; stained panes and a lone pane don't. */
    @GameTest(template = EMPTY)
    public static void transparentFacadeRecipe(GameTestHelper helper) {
        ItemStack pipe = new ItemStack(ModItems.ITEM_PIPE.get());
        ItemStack made = craft(helper, new ItemStack(Items.GLASS_PANE), pipe);
        helper.assertTrue(made.is(ModItems.FACADE.get()) && FacadeItem.isTransparent(FacadeItem.coverOf(made))
                && made.getCount() == 8, "Glass pane + pipe made " + made);
        helper.assertTrue(!craft(helper, new ItemStack(Items.RED_STAINED_GLASS_PANE), pipe).is(ModItems.FACADE.get()),
                "A stained glass pane made a facade");
        helper.assertTrue(!craft(helper, new ItemStack(Items.GLASS_PANE), ItemStack.EMPTY).is(ModItems.FACADE.get()),
                "A pane alone made a facade");
        helper.succeed();
    }

    /**
     * 134a: a transparent cover acts like glass: block state CLEAR, full block to walk on and click, light passes,
     * no redstone. It comes off with sneak + empty hand (anywhere on the block), nothing drops.
     */
    @GameTest(template = EMPTY)
    public static void transparentFacadeIsClearBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(PIPE);
        BlockState clear = FacadeItem.transparentCover();
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, clear);
        BlockState state = level.getBlockState(at);
        helper.assertTrue(state.getValue(BasePipeBlock.COVER) == CoverShape.CLEAR, "Cover state: " + state);
        helper.assertTrue(data(helper, PIPE).coverStateMatches(), "Stored cover and block state disagree");
        helper.assertTrue(state.isCollisionShapeFullBlock(level, at), "Transparent-covered pipe isn't a full block to walk on");
        helper.assertTrue(Block.isShapeFullBlock(state.getShape(level, at)), "Outline isn't a full block");
        helper.assertTrue(state.propagatesSkylightDown(level, at), "Sky light stops at a transparent cover");
        helper.assertTrue(state.getLightBlock(level, at) == 0, "Transparent cover blocks light: " + state.getLightBlock(level, at));
        helper.assertFalse(state.isRedstoneConductor(level, at), "Transparent cover conducts redstone");
        helper.assertTrue(data(helper, PIPE).getModelData().get(PipeDataBlockEntity.COVER_MODEL) == clear,
                "The model doesn't hear about the transparent cover");
        Player player = helper.makeMockPlayer();
        player.setShiftKeyDown(true);
        // A corner of the top face: on the full block, nowhere near the pipe inside.
        BlockHitResult corner = new BlockHitResult(Vec3.atLowerCornerOf(at).add(0.05, 1.0, 0.05), Direction.UP, at, false);
        clickAt(helper, player, ItemStack.EMPTY, corner);
        ItemStack inHand = player.getMainHandItem();
        helper.assertTrue(cover(helper, PIPE) == null, "Transparent cover didn't come off from a corner click");
        helper.assertTrue(inHand.is(ModItems.FACADE.get()) && FacadeItem.isTransparent(FacadeItem.coverOf(inHand))
                && inHand.getCount() == 1, "Hand holds " + inHand + " (should be 1 transparent facade)");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /**
     * Config allow_transparent = false: no crafting, no putting one on (nothing used), but a pipe already wearing one
     * can still take it off. The config is set back in the same tick, so other tests never see it off.
     */
    @GameTest(template = EMPTY)
    public static void transparentFacadeTurnedOff(GameTestHelper helper) {
        BlockState clear = FacadeItem.transparentCover();
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        coverWithSurvivalPlayer(helper, PIPE, clear); // put on while allowed
        BlockPos other = PIPE.east(2);
        pipe(helper, other, ModBlocks.ITEM_PIPE.get());
        setInMemory(DaisychainConfig.ALLOW_TRANSPARENT_FACADES, false);
        try {
            helper.assertTrue(!craft(helper, new ItemStack(Items.GLASS_PANE), new ItemStack(ModItems.ITEM_PIPE.get()))
                    .is(ModItems.FACADE.get()), "Crafted a transparent facade while they're off");
            Player player = helper.makeMockPlayer();
            ItemStack facade = FacadeItem.of(clear, 1);
            player.setItemInHand(InteractionHand.MAIN_HAND, facade);
            clickAt(helper, player, facade, centerHit(helper, other));
            helper.assertTrue(cover(helper, other) == null, "A transparent facade went on while they're off");
            helper.assertTrue(facade.getCount() == 1, "The refused facade was used up");

            Player remover = helper.makeMockPlayer();
            remover.setShiftKeyDown(true);
            clickAt(helper, remover, ItemStack.EMPTY, centerHit(helper, PIPE));
            helper.assertTrue(cover(helper, PIPE) == null, "Couldn't take a transparent facade off while they're off");
            helper.assertTrue(FacadeItem.isTransparent(FacadeItem.coverOf(remover.getMainHandItem())),
                    "Taking it off gave " + remover.getMainHandItem());
        } finally {
            DaisychainConfig.ALLOW_TRANSPARENT_FACADES.clearCache(); // back to the value in the config file
        }
        helper.succeed();
    }

    /**
     * Sets a server config value for this test only, without touching the config file. ConfigValue.set() saves the file
     * (Forge opens it with autosave), and Forge's file watcher then reloads it on another thread; on Linux that reload
     * can land mid-write, "correct" the half-written file back to the defaults and undo the test's value (CI, F61).
     * get() returns the cached value first, so setting only the cache changes what the mod sees and nothing else.
     */
    private static <T> void setInMemory(ForgeConfigSpec.ConfigValue<T> value, T newValue) {
        try {
            java.lang.reflect.Field cached = ForgeConfigSpec.ConfigValue.class.getDeclaredField("cachedValue");
            cached.setAccessible(true);
            cached.set(value, newValue);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't set config value in memory", e);
        }
    }

    /** Sneak + right-click with transparent facades covers the linked pipes too (mass apply treats it like any cover). */
    @GameTest(template = EMPTY)
    public static void transparentFacadeMassApply(GameTestHelper helper) {
        BlockState clear = FacadeItem.transparentCover();
        for (int x = 2; x <= 6; x++) {
            pipe(helper, new BlockPos(x, 1, 2), ModBlocks.ITEM_PIPE.get());
        }
        for (int x = 2; x <= 6; x++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 2)));
        }
        Player player = helper.makeMockPlayer();
        ItemStack facades = FacadeItem.of(clear, 8);
        player.setItemInHand(InteractionHand.MAIN_HAND, facades);
        FacadeMassApply.apply(helper.getLevel(), helper.absolutePos(PIPE), player, facades, 64);
        for (int x = 2; x <= 6; x++) {
            BlockPos pos = new BlockPos(x, 1, 2);
            helper.assertTrue(cover(helper, pos) == clear, "Pipe at x " + x + " has " + cover(helper, pos));
            helper.assertTrue(helper.getBlockState(pos).getValue(BasePipeBlock.COVER) == CoverShape.CLEAR,
                    "Pipe at x " + x + " isn't a clear full block");
        }
        helper.assertTrue(facades.getCount() == 3, "Facades left: " + facades.getCount() + " (should be 3)");
        helper.succeed();
    }

    /**
     * Step F62 (port of 136): pick block (middle click) on a covered pipe gives a facade of its cover, transparent ones included;
     * on a bare pipe (also once the cover is off) it gives the pipe as before.
     */
    @GameTest(template = EMPTY)
    public static void facadePickBlockGivesFacade(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer();
        helper.assertTrue(picked(helper, player).is(ModItems.ITEM_PIPE.get()), "Bare pipe: picked " + picked(helper, player));
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        ItemStack oak = picked(helper, player);
        helper.assertTrue(oak.is(ModItems.FACADE.get()) && FacadeItem.coverOf(oak) == OAK && oak.getCount() == 1,
                "Oak cover: picked " + oak + " holding " + FacadeItem.coverOf(oak));
        BlockState clear = FacadeItem.transparentCover();
        data(helper, PIPE).setCover(clear);
        ItemStack transparent = picked(helper, player);
        helper.assertTrue(transparent.is(ModItems.FACADE.get()) && FacadeItem.coverOf(transparent) == clear,
                "Transparent cover: picked " + transparent);
        data(helper, PIPE).setCover(null);
        BasePipeBlock.syncCoverState(helper.getLevel(), helper.absolutePos(PIPE));
        helper.assertTrue(picked(helper, player).is(ModItems.ITEM_PIPE.get()),
                "Cover off: picked " + picked(helper, player));
        helper.succeed();
    }

    /**
     * Step F62 (port of 136): footsteps, hitting and breaking a covered pipe use the cover's sounds (all of them ask the block state
     * for its sound type at that spot); a bare pipe keeps its metal sounds, also once the cover is off.
     */
    @GameTest(template = EMPTY)
    public static void facadeUsesCoverSounds(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        helper.assertTrue(sound(helper) == SoundType.METAL, "Bare pipe sounds like " + sound(helper));
        coverWithSurvivalPlayer(helper, PIPE, STONE);
        helper.assertTrue(sound(helper) == SoundType.STONE, "Stone cover sounds like " + sound(helper));
        data(helper, PIPE).setCover(OAK);
        helper.assertTrue(sound(helper) == SoundType.WOOD, "Oak cover sounds like " + sound(helper));
        data(helper, PIPE).setCover(Blocks.GLASS.defaultBlockState());
        BasePipeBlock.syncCoverState(helper.getLevel(), helper.absolutePos(PIPE));
        helper.assertTrue(sound(helper) == SoundType.GLASS, "Glass cover sounds like " + sound(helper));
        data(helper, PIPE).setCover(null);
        BasePipeBlock.syncCoverState(helper.getLevel(), helper.absolutePos(PIPE));
        helper.assertTrue(sound(helper) == SoundType.METAL, "Cover off: sounds like " + sound(helper));
        helper.succeed();
    }

    private static ItemStack picked(GameTestHelper helper, Player player) {
        BlockPos at = helper.absolutePos(PIPE);
        BlockState state = helper.getLevel().getBlockState(at);
        return state.getCloneItemStack(centerHit(helper, PIPE), helper.getLevel(), at, player);
    }

    private static SoundType sound(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(PIPE);
        return helper.getLevel().getBlockState(at).getSoundType(helper.getLevel(), at, null);
    }

    private static ItemStack craft(GameTestHelper helper, ItemStack a, ItemStack b) {
        // No menu: the items go in through the constructor (setItem would tell the missing menu).
        TransientCraftingContainer input = new TransientCraftingContainer(null, 2, 1,
                NonNullList.of(ItemStack.EMPTY, a.copy(), b.copy()));
        ServerLevel level = helper.getLevel();
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.assemble(input, level.registryAccess()))
                .orElse(ItemStack.EMPTY);
    }

    private static void pipe(GameTestHelper helper, BlockPos pos, Block block) {
        helper.setBlock(pos, block.defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pos));
    }

    private static void coverWithSurvivalPlayer(GameTestHelper helper, BlockPos pos, BlockState block) {
        Player player = helper.makeMockPlayer();
        ItemStack facade = FacadeItem.of(block, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, facade);
        clickAt(helper, player, facade, centerHit(helper, pos));
        helper.assertTrue(cover(helper, pos) == block, "Setup: the cover didn't go on");
    }

    private static InteractionResult click(GameTestHelper helper, Player player, ItemStack stack) {
        return clickAt(helper, player, stack, centerHit(helper, PIPE));
    }

    private static InteractionResult clickAt(GameTestHelper helper, Player player, ItemStack stack, BlockHitResult hit) {
        // 1.20.1: the block reads the stack from the player's hand (the callers put it there).
        BlockPos at = hit.getBlockPos();
        return helper.getLevel().getBlockState(at).use(helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }

    private static BlockHitResult centerHit(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        return new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
    }

    @javax.annotation.Nullable
    private static BlockState cover(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        PipeDataBlockEntity data = BasePipeBlock.getData(helper.getLevel(), at, helper.getLevel().getBlockState(at));
        return data == null ? null : data.cover();
    }

    private static PipeDataBlockEntity data(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        PipeDataBlockEntity data = BasePipeBlock.getData(helper.getLevel(), at, helper.getLevel().getBlockState(at));
        if (data == null) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("No settings storage at " + pos);
        }
        return data;
    }

    private static int facadesHeld(Player player, BlockState block) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(ModItems.FACADE.get()) && FacadeItem.coverOf(stack) == block) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static List<ItemEntity> ground(GameTestHelper helper) {
        AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 4, 5))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area);
    }

    private static int itemsOnGround(GameTestHelper helper) {
        return ground(helper).size();
    }

    private static int facadesOnGround(GameTestHelper helper, BlockState block) {
        int count = 0;
        for (ItemEntity entity : ground(helper)) {
            ItemStack stack = entity.getItem();
            if (stack.is(ModItems.FACADE.get()) && FacadeItem.coverOf(stack) == block) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int groundCount(GameTestHelper helper, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemEntity entity : ground(helper)) {
            if (entity.getItem().is(item)) {
                count += entity.getItem().getCount();
            }
        }
        return count;
    }
}
