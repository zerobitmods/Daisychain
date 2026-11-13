package com.daisychain;

import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.command.DaisychainCommands;
import com.daisychain.item.CopierSessions;
import com.daisychain.net.PipeAccess;
import com.daisychain.network.NetworkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import com.daisychain.registry.ModTags;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-event listeners (not mod-loading events). */
public final class DaisychainEvents {

    private DaisychainEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onServerStopped);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onChunkTicketLevel);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onServerTickStart);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onLevelTickEnd);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(DaisychainEvents::onRightClickBlock);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DaisychainCommands.register(event.getDispatcher());
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            NetworkManager.remove(serverLevel);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        NetworkManager.clearAll();
        CopierSessions.clearAll();
        PipeAccess.clearAll();
    }

    /**
     * Step 65: sneak + right-click on a pipe with any wrench (c:tools/wrench) in the main hand still reaches the pipe.
     * Vanilla skips a block's click handling when you sneak while holding an item, so this asks for it explicitly.
     * Runs on both client and server, so both make the same choice.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isSecondaryUseActive()) {
            return;
        }
        BlockState target = event.getLevel().getBlockState(event.getPos());
        // Step 93: also a higher-tier energy pipe on an energy pipe (swaps it in place instead of placing next to it).
        if ((event.getItemStack().is(ModTags.WRENCHES) && target.getBlock() instanceof FlowPipeBlock)
                || (EnergyPipeBlock.canSwap(target, event.getItemStack())
                        && !FlowPipeBlock.isPullCapHit(target, event.getPos(), event.getHitVec()))) {
            event.setUseBlock(TriState.TRUE);
        }
    }

    /** Forget the player's Settings Copier requests and undo history. */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CopierSessions.remove(event.getEntity().getUUID());
        PipeAccess.remove(event.getEntity().getUUID());
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            NetworkManager.onChunkEvent(serverLevel, event.getChunk().getPos().toLong());
            NetworkManager.onChunkEventForWatches(serverLevel, event.getChunk().getPos().toLong(), true);
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            NetworkManager.onChunkEvent(serverLevel, event.getChunk().getPos().toLong());
            NetworkManager.onChunkEventForWatches(serverLevel, event.getChunk().getPos().toLong(), false);
        }
    }

    /**
     * Review R17: a chunk that loses its ticket, or gets it back, while it stays in memory gets no load or unload
     * event. This one fires for every ticket level change of every chunk, so the handler does next to nothing.
     */
    public static void onChunkTicketLevel(ChunkTicketLevelUpdatedEvent event) {
        NetworkManager.onTicketLevel(event.getLevel(), event.getChunkPos(), event.getOldTicketLevel(),
                event.getNewTicketLevel());
    }

    public static void onServerTickStart(ServerTickEvent.Pre event) {
        NetworkManager.processPendingChunkEvents();
    }

    public static void onLevelTickEnd(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            NetworkManager manager = NetworkManager.getExisting(serverLevel);
            if (manager != null) {
                manager.tick();
            }
        }
    }
}
