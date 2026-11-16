package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Step 133: the Settings Copier's network paste leaves alone the pipes a claim mod refuses (like FTB Chunks in someone
 * else's claim). Three item pipes along x at y 1, z 2 (x 1 to 3), each pulling from a chest to its north.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CopierClaimTests {
    private static final String EMPTY = "empty";

    private CopierClaimTests() {
    }

    @GameTest(template = EMPTY)
    public static void networkPasteSkipsPipesAClaimModRefuses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 1; x <= 3; x++) {
            helper.setBlock(new BlockPos(x, 1, 1), Blocks.CHEST.defaultBlockState());
            helper.setBlock(pipe(x), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        }
        for (int x = 1; x <= 3; x++) {
            BasePipeBlock.refreshArms(level, helper.absolutePos(pipe(x)));
        }
        for (int x = 1; x <= 3; x++) {
            BasePipeBlock.setPullSidesForTest(level, helper.absolutePos(pipe(x)), 1 << Direction.NORTH.get3DDataValue());
        }
        // Pipe 1's pull side gets a diamond in its filter, so a paste shows.
        BasePipeBlock.ensureData(level, helper.absolutePos(pipe(1)));
        data(helper, 1).setFilterEntry(Direction.NORTH, 0, new ItemStack(Items.DIAMOND), false, 0);
        CompoundTag wanted = side(helper, 1);

        Player player = helper.makeMockPlayer(); // Step F60: 1.20.1 has no game type here
        player.getAbilities().mayBuild = true; // the mock player doesn't set its abilities from its game type
        ItemStack copier = new ItemStack(ModItems.SETTINGS_COPIER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, copier);

        // Copy: click pipe 1's pull cap (north end of its arm).
        BlockPos first = helper.absolutePos(pipe(1));
        use(player, new BlockHitResult(Vec3.atCenterOf(first).add(0, 0, -0.45), Direction.NORTH, first, false));
        helper.assertTrue(ModDataComponents.COPIED_SETTINGS.has(copier), "Nothing was copied");

        // Network paste: sneak + click pipe 3's center twice (the second click confirms), pipe 2 refused.
        BlockPos refused = helper.absolutePos(pipe(2));
        BlockPos third = helper.absolutePos(pipe(3));
        TestClaims.refuse(refused);
        try {
            player.setShiftKeyDown(true);
            BlockHitResult center = new BlockHitResult(Vec3.atCenterOf(third), Direction.UP, third, false);
            use(player, center);
            use(player, center);
        } finally {
            TestClaims.allow(refused);
        }
        helper.assertTrue(side(helper, 3).equals(wanted), "Pipe 3 wasn't pasted onto");
        helper.assertFalse(side(helper, 2).equals(wanted), "Pipe 2 (refused) was pasted onto");
        helper.succeed();
    }

    private static BlockPos pipe(int x) {
        return new BlockPos(x, 1, 2);
    }

    private static void use(Player player, BlockHitResult hit) {
        ModItems.SETTINGS_COPIER.get().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static CompoundTag side(GameTestHelper helper, int x) {
        BlockPos abs = helper.absolutePos(pipe(x));
        ServerLevel level = helper.getLevel();
        PipeDataBlockEntity data = BasePipeBlock.getData(level, abs, level.getBlockState(abs));
        return data == null ? new CompoundTag() // no settings storage: all defaults
                : data.copySide(Direction.NORTH, false, level.registryAccess());
    }

    private static PipeDataBlockEntity data(GameTestHelper helper, int x) {
        BlockPos abs = helper.absolutePos(pipe(x));
        ServerLevel level = helper.getLevel();
        PipeDataBlockEntity data = BasePipeBlock.getData(level, abs, level.getBlockState(abs));
        if (data == null) {
            throw new GameTestAssertException("No settings storage at pipe " + x);
        }
        return data;
    }
}
