package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.FacadeMassApply;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.item.FacadeItem;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;

/**
 * Step 130: sneak + right-click with facades covers the linked pipes of the same type. Nothing is duplicated or lost:
 * facades used = pipes changed, replaced covers come back, nothing drops. Pipes run along x at y 1, z 2 (x 1 to 7).
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FacadeMassTests {
    private static final String EMPTY = "empty";
    private static final BlockState OAK = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState BRICKS = Blocks.BRICKS.defaultBlockState();

    private FacadeMassTests() {
    }

    /** Clicked pipe uncovered: every uncovered pipe gets one, walking through a covered pipe that is left alone. */
    @GameTest(template = EMPTY)
    public static void massCoversUncovered(GameTestHelper helper) {
        line(helper, 1, 7, ModBlocks.ITEM_PIPE.get());
        setCover(helper, at(4), STONE);
        Player player = sneaker(helper, GameType.SURVIVAL);
        ItemStack oak = FacadeItem.of(OAK, 10);
        massClick(helper, player, 1, oak, 64);
        for (int x = 1; x <= 7; x++) {
            BlockState expected = x == 4 ? STONE : OAK;
            helper.assertTrue(cover(helper, at(x)) == expected, "x " + x + " is " + cover(helper, at(x)));
        }
        helper.assertTrue(oak.getCount() == 4, "Facades left: " + oak.getCount() + " (should be 4)");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /** Stops when the stack runs out: the nearest pipes get covered first. */
    @GameTest(template = EMPTY)
    public static void massStopsWhenStackRunsOut(GameTestHelper helper) {
        line(helper, 1, 7, ModBlocks.ITEM_PIPE.get());
        Player player = sneaker(helper, GameType.SURVIVAL);
        ItemStack oak = FacadeItem.of(OAK, 3);
        massClick(helper, player, 1, oak, 64);
        for (int x = 1; x <= 7; x++) {
            BlockState expected = x <= 3 ? OAK : null;
            helper.assertTrue(cover(helper, at(x)) == expected, "x " + x + " is " + cover(helper, at(x)));
        }
        helper.assertTrue(oak.isEmpty(), "Facades left: " + oak.getCount());
        helper.succeed();
    }

    /** The limit per click is respected (nearest first). */
    @GameTest(template = EMPTY)
    public static void massRespectsMax(GameTestHelper helper) {
        line(helper, 1, 7, ModBlocks.ITEM_PIPE.get());
        Player player = sneaker(helper, GameType.SURVIVAL);
        ItemStack oak = FacadeItem.of(OAK, 10);
        massClick(helper, player, 4, oak, 3);
        int covered = 0;
        for (int x = 1; x <= 7; x++) {
            if (cover(helper, at(x)) == OAK) {
                covered++;
                helper.assertTrue(Math.abs(x - 4) <= 1, "x " + x + " was covered; only the nearest 3 should be");
            }
        }
        helper.assertTrue(covered == 3 && oak.getCount() == 7, "Covered " + covered + ", left " + oak.getCount());
        helper.succeed();
    }

    /** Clicked pipe covered with oak: only oak-covered pipes switch, the old oak facades come back, others untouched. */
    @GameTest(template = EMPTY)
    public static void massReplacesSameCoverOnly(GameTestHelper helper) {
        line(helper, 1, 5, ModBlocks.ITEM_PIPE.get());
        setCover(helper, at(1), OAK);
        setCover(helper, at(2), OAK);
        setCover(helper, at(3), STONE);
        setCover(helper, at(4), OAK);
        Player player = sneaker(helper, GameType.SURVIVAL);
        ItemStack bricks = FacadeItem.of(BRICKS, 10);
        massClick(helper, player, 1, bricks, 64);
        BlockState[] expected = {BRICKS, BRICKS, STONE, BRICKS, null};
        for (int x = 1; x <= 5; x++) {
            helper.assertTrue(cover(helper, at(x)) == expected[x - 1], "x " + x + " is " + cover(helper, at(x)));
        }
        helper.assertTrue(bricks.getCount() == 7, "Facades left: " + bricks.getCount() + " (should be 7)");
        helper.assertTrue(facadesHeld(player, OAK) == 3, "Oak facades back: " + facadesHeld(player, OAK) + " (should be 3)");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
        helper.succeed();
    }

    /** Holding the facade the clicked pipe already wears does nothing. */
    @GameTest(template = EMPTY)
    public static void massSameFacadeDoesNothing(GameTestHelper helper) {
        line(helper, 1, 3, ModBlocks.ITEM_PIPE.get());
        setCover(helper, at(1), OAK);
        Player player = sneaker(helper, GameType.SURVIVAL);
        ItemStack oak = FacadeItem.of(OAK, 5);
        massClick(helper, player, 1, oak, 64);
        helper.assertTrue(oak.getCount() == 5, "Facades used: " + (5 - oak.getCount()));
        helper.assertTrue(cover(helper, at(2)) == null && cover(helper, at(3)) == null, "Other pipes changed");
        helper.assertTrue(facadesHeld(player, OAK) == 5, "Facades came back");
        helper.succeed();
    }

    /** Only the clicked pipe's own block: basic energy pipes stop at iron ones; a touching item pipe is left alone. */
    @GameTest(template = EMPTY)
    public static void massOnlySameType(GameTestHelper helper) {
        line(helper, 1, 3, ModBlocks.ENERGY_PIPE.get());
        line(helper, 4, 6, ModBlocks.IRON_ENERGY_PIPE.get());
        BlockPos side = at(2).north();
        helper.setBlock(side, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        refresh(helper, side);
        refresh(helper, at(2));
        Player player = sneaker(helper, GameType.SURVIVAL);
        ItemStack oak = FacadeItem.of(OAK, 20);
        massClick(helper, player, 1, oak, 64);
        for (int x = 1; x <= 6; x++) {
            BlockState expected = x <= 3 ? OAK : null;
            helper.assertTrue(cover(helper, at(x)) == expected, "x " + x + " is " + cover(helper, at(x)));
        }
        helper.assertTrue(cover(helper, side) == null, "The item pipe next to it got covered");
        helper.assertTrue(oak.getCount() == 17, "Facades left: " + oak.getCount() + " (should be 17)");
        helper.succeed();
    }

    /** Creative uses no facades. */
    @GameTest(template = EMPTY)
    public static void massCreativeUsesNone(GameTestHelper helper) {
        line(helper, 1, 5, ModBlocks.FLUID_PIPE.get());
        Player player = sneaker(helper, GameType.CREATIVE);
        ItemStack oak = FacadeItem.of(OAK, 1);
        massClick(helper, player, 3, oak, 64);
        for (int x = 1; x <= 5; x++) {
            helper.assertTrue(cover(helper, at(x)) == OAK, "x " + x + " is " + cover(helper, at(x)));
        }
        helper.assertTrue(oak.getCount() == 1, "Creative used facades");
        helper.succeed();
    }

    /** No build rights (adventure): nothing changes, nothing used. Through the real click path. */
    @GameTest(template = EMPTY)
    public static void massNeedsBuildRights(GameTestHelper helper) {
        line(helper, 1, 3, ModBlocks.ITEM_PIPE.get());
        Player player = sneaker(helper, GameType.ADVENTURE);
        ItemStack oak = FacadeItem.of(OAK, 5);
        player.setItemInHand(InteractionHand.MAIN_HAND, oak);
        BlockPos pos = helper.absolutePos(at(1));
        helper.getLevel().getBlockState(pos).useItemOn(oak, helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos,
                        false));
        for (int x = 1; x <= 3; x++) {
            helper.assertTrue(cover(helper, at(x)) == null, "x " + x + " got covered");
        }
        helper.assertTrue(oak.getCount() == 5, "Facades used: " + (5 - oak.getCount()));
        helper.succeed();
    }

    /** Through the real click path (useItemOn while sneaking) the whole line is covered. */
    @GameTest(template = EMPTY)
    public static void massThroughClick(GameTestHelper helper) {
        line(helper, 1, 4, ModBlocks.UNIVERSAL_PIPE.get());
        Player player = sneaker(helper, GameType.SURVIVAL);
        ItemStack oak = FacadeItem.of(OAK, 8);
        player.setItemInHand(InteractionHand.MAIN_HAND, oak);
        BlockPos pos = helper.absolutePos(at(2));
        helper.getLevel().getBlockState(pos).useItemOn(oak, helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos,
                        false));
        for (int x = 1; x <= 4; x++) {
            helper.assertTrue(cover(helper, at(x)) == OAK, "x " + x + " is " + cover(helper, at(x)));
        }
        helper.assertTrue(oak.getCount() == 4, "Facades left: " + oak.getCount() + " (should be 4)");
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    private static BlockPos at(int x) {
        return new BlockPos(x, 1, 2);
    }

    private static void line(GameTestHelper helper, int from, int to, Block block) {
        for (int x = from; x <= to; x++) {
            helper.setBlock(at(x), block.defaultBlockState());
        }
        for (int x = Math.max(1, from - 1); x <= to + 1 && x <= 7; x++) {
            refresh(helper, at(x));
        }
    }

    private static void refresh(GameTestHelper helper, BlockPos pos) {
        FlowPipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pos));
    }

    private static void setCover(GameTestHelper helper, BlockPos pos, BlockState block) {
        BlockPos abs = helper.absolutePos(pos);
        FlowPipeBlock.ensureData(helper.getLevel(), abs);
        data(helper, pos).setCover(block);
    }

    private static Player sneaker(GameTestHelper helper, GameType mode) {
        Player player = helper.makeMockPlayer(mode);
        // The mock player doesn't set its abilities from its game type.
        player.getAbilities().mayBuild = mode != GameType.ADVENTURE;
        player.getAbilities().instabuild = mode == GameType.CREATIVE;
        player.setShiftKeyDown(true);
        return player;
    }

    private static void massClick(GameTestHelper helper, Player player, int x, ItemStack stack, int max) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        FacadeMassApply.apply(helper.getLevel(), helper.absolutePos(at(x)), player, stack, max);
    }

    @Nullable
    private static BlockState cover(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        PipeDataBlockEntity data = FlowPipeBlock.getData(helper.getLevel(), abs, helper.getLevel().getBlockState(abs));
        return data == null ? null : data.cover();
    }

    private static PipeDataBlockEntity data(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        ServerLevel level = helper.getLevel();
        PipeDataBlockEntity data = FlowPipeBlock.getData(level, abs, level.getBlockState(abs));
        if (data == null) {
            throw new GameTestAssertException("No settings storage at " + pos);
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

    private static int itemsOnGround(GameTestHelper helper) {
        AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 4, 5))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).size();
    }
}
