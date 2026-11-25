package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.SideState;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Step 146: the outline (what the crosshair and clicks see) follows the thin pipe as drawn: the tube's long edges are
 * notched 1 px, an insert side has a solid cap with a ring set in behind it. What things bump into stays plain boxes.
 * Points are in px inside the block.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PipeOutlineTests {
    private static final String EMPTY = "empty";

    private PipeOutlineTests() {
    }

    /** A straight run along x: the middle pipe's arm and core leave out the edge notches; collision doesn't. */
    @GameTest(template = EMPTY)
    public static void outlineFollowsNotches(GameTestHelper helper) {
        BlockPos west = new BlockPos(3, 1, 2);
        BlockPos middle = new BlockPos(4, 1, 2);
        BlockPos east = new BlockPos(5, 1, 2);
        for (BlockPos pos : new BlockPos[] {west, middle, east}) {
            helper.setBlock(pos, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        }
        for (BlockPos pos : new BlockPos[] {west, middle, east}) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pos));
        }
        BlockPos at = helper.absolutePos(middle);
        BlockState state = helper.getLevel().getBlockState(at);
        VoxelShape outline = state.getShape(helper.getLevel(), at, CollisionContext.empty());
        VoxelShape collision = state.getCollisionShape(helper.getLevel(), at, CollisionContext.empty());
        expect(helper, outline, 2, 8, 8, true, "arm middle in the outline");
        expect(helper, outline, 2, 5.5, 5.5, false, "arm edge notch left out of the outline");
        expect(helper, outline, 8, 10.5, 5.5, false, "core edge notch left out of the outline (straight pipe)");
        expect(helper, outline, 8, 8, 1, false, "nothing on the unconnected north side");
        expect(helper, collision, 2, 5.5, 5.5, true, "collision keeps the plain arm box");
        helper.succeed();
    }

    /** A lone pipe on a chest: the insert cap is solid to its corners, the ring behind it is set in. */
    @GameTest(template = EMPTY)
    public static void outlineFindsInsertSide(GameTestHelper helper) {
        BlockPos chest = new BlockPos(4, 1, 1);
        BlockPos pipe = new BlockPos(4, 1, 2);
        helper.setBlock(chest, Blocks.CHEST.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe));
        BlockPos at = helper.absolutePos(pipe);
        BlockState state = helper.getLevel().getBlockState(at);
        helper.assertTrue(state.getValue(BasePipeBlock.propertyFor(Direction.NORTH)) == SideState.CONNECTED,
                "Setup: the pipe didn't connect to the chest");
        VoxelShape outline = state.getShape(helper.getLevel(), at, CollisionContext.empty());
        expect(helper, outline, 5.5, 5.5, 0.75, true, "insert cap corner in the outline");
        expect(helper, outline, 5.25, 5.25, 2, false, "insert ring set in");
        expect(helper, outline, 8, 8, 2, true, "insert ring middle in the outline");
        helper.succeed();
    }

    /**
     * Step 157: a pipe between two chests (an insert side on both ends): behind each ring the arm is solid to its
     * corners, as drawn. The outline used to notch it there, so its edges ran across the pipe's faces.
     */
    @GameTest(template = EMPTY)
    public static void outlineInsertBetweenBlocks(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(4, 1, 2);
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.CHEST.defaultBlockState());
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe));
        BlockPos at = helper.absolutePos(pipe);
        BlockState state = helper.getLevel().getBlockState(at);
        helper.assertTrue(state.getValue(BasePipeBlock.propertyFor(Direction.NORTH)) == SideState.CONNECTED
                && state.getValue(BasePipeBlock.propertyFor(Direction.SOUTH)) == SideState.CONNECTED,
                "Setup: the pipe didn't connect to both chests");
        VoxelShape outline = state.getShape(helper.getLevel(), at, CollisionContext.empty());
        expect(helper, outline, 5.5, 5.5, 4, true, "north arm corner behind the ring in the outline");
        expect(helper, outline, 10.5, 10.5, 12, true, "south arm corner behind the ring in the outline");
        expect(helper, outline, 5.25, 5.25, 2, false, "insert ring still set in");
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, VoxelShape shape, double x, double y, double z, boolean inside,
                               String what) {
        boolean found = false;
        for (AABB box : shape.toAabbs()) {
            if (box.contains(x / 16, y / 16, z / 16)) {
                found = true;
                break;
            }
        }
        helper.assertTrue(found == inside, what + " (point " + x + ", " + y + ", " + z + " px)");
    }
}
