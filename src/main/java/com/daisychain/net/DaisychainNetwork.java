package com.daisychain.net;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.menu.PullSideMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers Daisychain's packets. Every client packet is checked on the server before it changes anything. */
public final class DaisychainNetwork {
    /** Players must be within this many blocks of the pipe (squared) to change it. */
    private static final double MAX_DISTANCE_SQR = 8.0 * 8.0;

    private DaisychainNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // "7" since step 116c (LagHighlightPayload); "6" was step 48h (UndoPastePayload gained redo). A client and
        // server with different packet sets refuse to connect.
        PayloadRegistrar registrar = event.registrar("7");
        registrar.playToServer(UpdatePullSettingPayload.TYPE, UpdatePullSettingPayload.STREAM_CODEC,
                DaisychainNetwork::handleUpdatePullSetting);
        registrar.playToServer(SetSideModePayload.TYPE, SetSideModePayload.STREAM_CODEC,
                DaisychainNetwork::handleSetSideMode);
        registrar.playToServer(OpenPullSidePayload.TYPE, OpenPullSidePayload.STREAM_CODEC,
                DaisychainNetwork::handleOpenPullSide);
        registrar.playToServer(SetFilterMatchPayload.TYPE, SetFilterMatchPayload.STREAM_CODEC,
                DaisychainNetwork::handleSetFilterMatch);
        registrar.playToServer(SetFilterNumberPayload.TYPE, SetFilterNumberPayload.STREAM_CODEC,
                DaisychainNetwork::handleSetFilterNumber);
        registrar.playToServer(SetFilterGhostPayload.TYPE, SetFilterGhostPayload.STREAM_CODEC,
                DaisychainNetwork::handleSetFilterGhost);
        registrar.playToServer(UndoPastePayload.TYPE, UndoPastePayload.STREAM_CODEC,
                DaisychainNetwork::handleUndoPaste);
        registrar.playToClient(PasteHighlightPayload.TYPE, PasteHighlightPayload.STREAM_CODEC,
                DaisychainNetwork::handlePasteHighlight);
        registrar.playToClient(LagHighlightPayload.TYPE, LagHighlightPayload.STREAM_CODEC,
                DaisychainNetwork::handleLagHighlight);
    }

    /**
     * The undo / redo keys: undoes the player's newest Settings Copier paste (or redoes the newest undo), only while
     * they hold the copier. Undo itself
     * checks everything else (still loaded, unchanged since, may build, upgrades from the inventory).
     */
    private static void handleUndoPaste(UndoPastePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) {
            return;
        }
        if (!player.getMainHandItem().is(com.daisychain.registry.ModItems.SETTINGS_COPIER.get())
                && !player.getOffhandItem().is(com.daisychain.registry.ModItems.SETTINGS_COPIER.get())) {
            return;
        }
        if (payload.redo()) {
            com.daisychain.item.SettingsCopierItem.redo(player);
        } else {
            com.daisychain.item.SettingsCopierItem.undo(player, 0);
        }
    }

    /** Client, step 116c: /daisychain lag outlines. Only runs on the client (see handlePasteHighlight). */
    private static void handleLagHighlight(LagHighlightPayload payload, IPayloadContext context) {
        com.daisychain.client.ClientAccess.showLagHighlight(payload.dimension(), payload.positions(), payload.sides());
    }

    /** Client: Settings Copier outlines. Only runs on the client, so the client class is never loaded on a server. */
    private static void handlePasteHighlight(PasteHighlightPayload payload, IPayloadContext context) {
        com.daisychain.client.ClientAccess.showPasteHighlight(payload.kind(), payload.positions(), payload.sides());
    }

    /**
     * A filter entry dragged in from JEI. Only for the side settings screen the player has open right now, while it's
     * still valid; the menu applies the same rules as a click (empty slot only, right kind, no duplicates).
     */
    private static void handleSetFilterGhost(SetFilterGhostPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (!(player.containerMenu instanceof PullSideMenu menu) || menu.containerId != payload.containerId()
                || !menu.stillValid(player)) {
            return;
        }
        menu.listDropped(payload.slot(), payload.stack(), player);
    }

    /** Only accepted for the Pull side screen the player has open right now, while it's still valid. */
    private static void handleSetFilterNumber(SetFilterNumberPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (!(player.containerMenu instanceof PullSideMenu menu) || menu.containerId != payload.containerId()
                || !menu.stillValid(player)) {
            return;
        }
        menu.setNumber(payload.slot(), payload.which(), payload.value());
    }

    /** Only accepted for the Pull side screen the player has open right now, while it's still valid. */
    private static void handleSetFilterMatch(SetFilterMatchPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (!(player.containerMenu instanceof PullSideMenu menu) || menu.containerId != payload.containerId()
                || !menu.stillValid(player)) {
            return;
        }
        menu.setMatch(payload.slot(), payload.mode(), payload.value());
    }

    private static void handleOpenPullSide(OpenPullSidePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (payload.side() < 0 || payload.side() >= 6) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = payload.pos();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE_SQR) {
            return;
        }
        if (!PipeAccess.allowed(player, level, pos)) {
            return; // review R3: not the pipe this player last clicked with the server's consent
        }
        PullSideMenu.openSettings(player, level, pos, Direction.from3DDataValue(payload.side()));
    }

    /** Step 48: a mode key on the Pipe sides screen. Player and distance checks as above; the pipe refuses modes the side can't have. */
    private static void handleSetSideMode(SetSideModePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (payload.side() < 0 || payload.side() >= 6 || payload.mode() < 0 || payload.mode() > FlowPipeBlock.MODE_DISCONNECTED) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = payload.pos();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE_SQR) {
            return;
        }
        if (!PipeAccess.allowed(player, level, pos)) {
            return; // review R3: not the pipe this player last clicked with the server's consent
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FlowPipeBlock pipe) {
            pipe.setSideModeFromScreen(level, pos, Direction.from3DDataValue(payload.side()), player, payload.mode());
        }
    }

    // Runs on the server thread (NeoForge's default for payload handlers).
    private static void handleUpdatePullSetting(UpdatePullSettingPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (payload.side() < 0 || payload.side() >= 6) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = payload.pos();
        if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE_SQR) {
            return;
        }
        Direction side = Direction.from3DDataValue(payload.side());
        // Review R3: only for the side whose settings screen this player has open right now. The server opened that
        // screen itself, after a click it allowed.
        if (!(player.containerMenu instanceof PullSideMenu menu) || !menu.pos().equals(pos) || menu.side() != side
                || !menu.stillValid(player)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        // Pull settings only on pull sides, insert settings only on insert sides.
        boolean allowed = PipeDataBlockEntity.isInsertSetting(payload.setting())
                ? FlowPipeBlock.isInsertSide(level, pos, state, side)
                : FlowPipeBlock.isPullSide(level, pos, state, side);
        if (!allowed) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof PipeDataBlockEntity data) {
            data.applySetting(side, payload.setting(), payload.value(), payload.resource());
        }
    }
}
