package com.daisychain.item;

import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.config.UpgradeSpeeds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Locale;

/** Step 84 (zerobit): an energy pipe of one tier. Its tooltip shows the tier's speed. */
public final class EnergyPipeItem extends BlockItem {
    private final EnergyPipeBlock pipe;

    public EnergyPipeItem(EnergyPipeBlock pipe, Properties properties) {
        super(pipe, properties);
        this.pipe = pipe;
    }

    public int tier() {
        return pipe.tier();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (UpgradeSpeeds.available()) {
            int fe = UpgradeSpeeds.energyFe(pipe.tier());
            int interval = Math.max(1, UpgradeSpeeds.energyInterval(pipe.tier()));
            tooltip.add(Component.translatable("tooltip.daisychain.energy_pipe.speed",
                    String.format(Locale.ROOT, "%,d", fe / interval)).withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.daisychain.energy_pipe.route").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.energy_pipe.swap").withStyle(ChatFormatting.DARK_GRAY));
    }
}
