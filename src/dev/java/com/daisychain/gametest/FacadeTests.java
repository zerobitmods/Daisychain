package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
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
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Step 125 (facades step 1): a cover goes on, swaps, comes off and drops exactly once; never lost on a tier swap;
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
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack facades = FacadeItem.of(OAK, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, facades);
        helper.assertTrue(click(helper, player, facades) == ItemInteractionResult.SUCCESS, "Click wasn't handled");
        helper.assertTrue(cover(helper, PIPE) == OAK, "Cover is " + cover(helper, PIPE) + " (should be oak)");
        helper.assertTrue(facades.getCount() == 1, "Facades left: " + facades.getCount() + " (should be 1)");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        click(helper, player, ItemStack.EMPTY);
        ItemStack inHand = player.getMainHandItem();
        helper.assertTrue(cover(helper, PIPE) == null, "The cover is still on");
        helper.assertTrue(inHand.is(ModItems.FACADE.get()) && FacadeItem.coverOf(inHand) == OAK && inHand.getCount() == 1,
                "Hand holds " + inHand + " (should be 1 oak facade)");
        helper.assertTrue(!helper.getBlockState(PIPE).getValue(FlowPipeBlock.HAS_DATA),
                "The pipe kept its settings storage with nothing in it");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /**
     * Step 125b: a removed facade joins a stack of the same facades in the inventory instead of taking the empty hand
     * slot each time; a facade with no stack to join still lands in the hand.
     */
    @GameTest(template = EMPTY)
    public static void facadeRemovalStacks(GameTestHelper helper) {
        pipe(helper, PIPE, ModBlocks.ITEM_PIPE.get());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
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
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
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
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
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
        Player player = helper.makeMockPlayer(GameType.ADVENTURE);
        player.getAbilities().mayBuild = false; // the mock player doesn't set this from its game type
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
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
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
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack gold = new ItemStack(ModBlocks.energyPipes()[2], 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, gold);
        BlockPos at = helper.absolutePos(PIPE);
        boolean swapped = EnergyPipeBlock.swapInPlace(level, at, level.getBlockState(at), player, gold);
        helper.assertTrue(swapped, "The tier swap didn't happen");
        helper.assertBlockPresent(ModBlocks.energyPipes()[2], PIPE);
        helper.assertTrue(cover(helper, PIPE) == OAK, "Cover after the swap: " + cover(helper, PIPE));
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
        FlowPipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(PIPE), 1 << Direction.SOUTH.get3DDataValue());
        data(helper, PIPE).setUpgradeLevel(Direction.SOUTH, 2);
        coverWithSurvivalPlayer(helper, PIPE, OAK);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
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
        var registries = helper.getLevel().registryAccess();
        CompoundTag disk = data.saveWithoutMetadata(registries);
        CompoundTag update = data.getUpdateTag(registries);
        BlockPos at = helper.absolutePos(PIPE);
        for (CompoundTag tag : List.of(disk, update)) {
            PipeDataBlockEntity copy = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
            copy.loadWithComponents(tag, registries);
            helper.assertTrue(copy.cover() == expected, "Read back " + copy.cover() + " (should be " + expected + ")");
        }
        // And no cover reads back as none (a client update after removal must clear it).
        data.setCover(null);
        PipeDataBlockEntity copy = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
        copy.loadWithComponents(disk, registries);
        copy.loadWithComponents(data.getUpdateTag(registries), registries);
        helper.assertTrue(copy.cover() == null, "A removed cover came back from an update");
        helper.succeed();
    }

    /** Block + item pipe makes a facade of that block; stairs, chests, two pipes or a lone block make nothing. */
    /** Step 126: the block entity hands its cover to the pipe model (model data), and nothing once it's off. */
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

    private static ItemStack craft(GameTestHelper helper, ItemStack a, ItemStack b) {
        CraftingInput input = CraftingInput.of(2, 1, List.of(a.copy(), b.copy()));
        ServerLevel level = helper.getLevel();
        return level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.value().assemble(input, level.registryAccess()))
                .orElse(ItemStack.EMPTY);
    }

    private static void pipe(GameTestHelper helper, BlockPos pos, Block block) {
        helper.setBlock(pos, block.defaultBlockState());
        FlowPipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pos));
    }

    private static void coverWithSurvivalPlayer(GameTestHelper helper, BlockPos pos, BlockState block) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack facade = FacadeItem.of(block, 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, facade);
        clickAt(helper, player, facade, centerHit(helper, pos));
        helper.assertTrue(cover(helper, pos) == block, "Setup: the cover didn't go on");
    }

    private static ItemInteractionResult click(GameTestHelper helper, Player player, ItemStack stack) {
        return clickAt(helper, player, stack, centerHit(helper, PIPE));
    }

    private static ItemInteractionResult clickAt(GameTestHelper helper, Player player, ItemStack stack, BlockHitResult hit) {
        BlockPos at = hit.getBlockPos();
        return helper.getLevel().getBlockState(at)
                .useItemOn(stack, helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }

    private static BlockHitResult centerHit(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        return new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
    }

    @javax.annotation.Nullable
    private static BlockState cover(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        PipeDataBlockEntity data = FlowPipeBlock.getData(helper.getLevel(), at, helper.getLevel().getBlockState(at));
        return data == null ? null : data.cover();
    }

    private static PipeDataBlockEntity data(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        PipeDataBlockEntity data = FlowPipeBlock.getData(helper.getLevel(), at, helper.getLevel().getBlockState(at));
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
