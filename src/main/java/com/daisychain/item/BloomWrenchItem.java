package com.daisychain.item;

import net.minecraft.world.level.Level;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.block.FlowPipeBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The Bloom Wrench (step 65). What it does comes from the c:tools/wrench tag (any wrench works the same on pipes);
 * this class only adds tooltip lines saying how to use it, because changing a side needs sneaking.
 */
public class BloomWrenchItem extends Item {
    public BloomWrenchItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.daisychain.bloom_wrench").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.bloom_wrench.open").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.bloom_wrench.holograms").withStyle(ChatFormatting.GRAY));
    }

    /**
     * Sneak + right-click on any block that isn't a pipe: turn the side holograms on or off (zerobit). Sneak-clicks on
     * pipes never get here: the pipe takes them to change a side.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isSecondaryUseActive()
                || context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof FlowPipeBlock) {
            return InteractionResult.PASS;
        }
        toggleHolograms(context.getLevel(), player);
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    /** Sneak + right-click on air: the same toggle. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive()) {
            return InteractionResultHolder.pass(stack);
        }
        toggleHolograms(level, player);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** The setting is the player's own (client config), so only the client flips it and says so in the action bar. */
    private static void toggleHolograms(Level level, Player player) {
        if (level.isClientSide()) {
            boolean on = DaisychainClientConfig.toggleWrenchHolograms();
            player.displayClientMessage(Component.translatable(
                    on ? "message.daisychain.holograms_on" : "message.daisychain.holograms_off"), true);
        }
    }
}
