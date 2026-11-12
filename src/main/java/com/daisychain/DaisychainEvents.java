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
import net.minecraftforge.common.MinecraftForge;
import com.daisychain.registry.ModTags;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;

/** Game-event listeners (not mod-loading events). */
public final class DaisychainEvents {

    private DaisychainEvents() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onLevelUnload);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onChunkTicketLevel);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onServerTickStart);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onLevelTickEnd);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(DaisychainEvents::onRightClickBlock);
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
        com.daisychain.command.StressMeasure.clear(); // step 115
    }

    /**
     * Step 65: sneak + right-click on a pipe with any wrench (forge:tools/wrench or forge:wrenches) in the main hand still reaches the pipe.
     * Vanilla skips a block's click handling when you sneak while holding an item, so this asks for it explicitly.
     * Runs on both client and server, so both make the same choice.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isSecondaryUseActive()) {
            return;
        }
        BlockState target = event.getLevel().getBlockState(event.getPos());
        // Step 93: also a higher-tier energy pipe on an energy pipe (swaps it in place instead of placing next to it).
        if ((ModTags.isWrench(event.getItemStack()) && target.getBlock() instanceof FlowPipeBlock)
                || (EnergyPipeBlock.canSwap(target, event.getItemStack())
                        && !FlowPipeBlock.isPullCapHit(target, event.getPos(), event.getHitVec()))) {
            event.setUseBlock(Event.Result.ALLOW);
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
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            NetworkManager.onChunkEvent(serverLevel, event.getChunk().getPos().toLong());
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

    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        NetworkManager.processPendingChunkEvents();
    }

    public static void onLevelTickEnd(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel serverLevel) {
            NetworkManager manager = NetworkManager.getExisting(serverLevel);
            if (manager != null) {
                manager.tick();
            }
        }
    }
}
