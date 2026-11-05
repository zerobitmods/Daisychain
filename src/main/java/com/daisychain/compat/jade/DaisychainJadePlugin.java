package com.daisychain.compat.jade;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Optional Jade integration. Jade finds this class through the @WailaPlugin annotation, and only when Jade is
 * installed, so Daisychain never needs Jade. Shows a pipe side's mode, upgrade and speed.
 */
@WailaPlugin
public final class DaisychainJadePlugin implements IWailaPlugin {
    /** Step 85: energy pipes send their network's route speeds (server side). */
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(PipeNetworkDataProvider.INSTANCE, PipeDataBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PipeInfoProvider.INSTANCE, FlowPipeBlock.class);
    }
}
