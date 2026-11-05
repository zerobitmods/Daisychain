package com.daisychain.item;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.config.UpgradeSpeeds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A pipe upgrade. Right-click a pull side to install (the pipe block handles that).
 * Sneak + right-click any pipe installs one into every pull side of that network that has a lower tier.
 */
public final class UpgradeItem extends Item {
    private final UpgradeTier tier;

    public UpgradeItem(UpgradeTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public UpgradeTier tier() {
        return tier;
    }

    // Vanilla only calls this when the pipe itself didn't handle the click, i.e. when sneaking.
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockState(context.getClickedPos()).getBlock() instanceof FlowPipeBlock)) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel serverLevel && context.getPlayer() != null) {
            FlowPipeBlock.installOnNetwork(serverLevel, context.getClickedPos(), context.getPlayer(),
                    context.getItemInHand(), this);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (UpgradeSpeeds.available()) {
            int items = UpgradeSpeeds.items(tier.level());
            int interval = UpgradeSpeeds.interval(tier.level());
            long perSecond = Math.round(items * 20.0 / interval);
            tooltip.add(Component.translatable("tooltip.daisychain.upgrade.speed", items, interval, perSecond)
                    .withStyle(ChatFormatting.GRAY));
            int mb = UpgradeSpeeds.fluidMb(tier.level());
            int fluidInterval = UpgradeSpeeds.fluidInterval(tier.level());
            long mbPerSecond = Math.round(mb * 20.0 / fluidInterval);
            tooltip.add(Component.translatable("tooltip.daisychain.upgrade.fluid_speed", mb, fluidInterval, mbPerSecond)
                    .withStyle(ChatFormatting.GRAY));
            int fe = UpgradeSpeeds.energyFe(tier.level());
            int energyInterval = UpgradeSpeeds.energyInterval(tier.level());
            tooltip.add(Component.translatable("tooltip.daisychain.upgrade.energy_speed",
                    String.format(java.util.Locale.ROOT, "%,d", fe), energyInterval,
                    String.format(java.util.Locale.ROOT, "%,d", fe / energyInterval))
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.daisychain.upgrade.install").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.upgrade.network").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.upgrade.remove").withStyle(ChatFormatting.DARK_GRAY));
    }
}
