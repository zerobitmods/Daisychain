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
import com.daisychain.Daisychain;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Registers Daisychain's packets. Every client packet is checked on the server before it changes anything. */
public final class DaisychainNetwork {
    /** Players must be within this many blocks of the pipe (squared) to change it. */
    private static final double MAX_DISTANCE_SQR = 8.0 * 8.0;

    private DaisychainNetwork() {
    }

    /** Bumped whenever a packet changes: a client and server with different packet sets refuse to connect. */
    private static final String PROTOCOL = "f1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Daisychain.MOD_ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        int id = 0;
        toServer(id++, UpdatePullSettingPayload.class, UpdatePullSettingPayload::encode, UpdatePullSettingPayload::decode,
                DaisychainNetwork::handleUpdatePullSetting);
        toServer(id++, SetSideModePayload.class, SetSideModePayload::encode, SetSideModePayload::decode,
                DaisychainNetwork::handleSetSideMode);
        toServer(id++, OpenPullSidePayload.class, OpenPullSidePayload::encode, OpenPullSidePayload::decode,
                DaisychainNetwork::handleOpenPullSide);
        toServer(id++, SetFilterMatchPayload.class, SetFilterMatchPayload::encode, SetFilterMatchPayload::decode,
                DaisychainNetwork::handleSetFilterMatch);
        toServer(id++, SetFilterNumberPayload.class, SetFilterNumberPayload::encode, SetFilterNumberPayload::decode,
                DaisychainNetwork::handleSetFilterNumber);
        toServer(id++, SetFilterGhostPayload.class, SetFilterGhostPayload::encode, SetFilterGhostPayload::decode,
                DaisychainNetwork::handleSetFilterGhost);
        toServer(id++, UndoPastePayload.class, UndoPastePayload::encode, UndoPastePayload::decode,
                DaisychainNetwork::handleUndoPaste);
        CHANNEL.messageBuilder(PasteHighlightPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PasteHighlightPayload::encode)
                .decoder(PasteHighlightPayload::decode)
                .consumerMainThread((payload, context) -> {
                    context.get().setPacketHandled(true);
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handlePasteHighlight(payload));
                })
                .add();
    }

    /** A client -> server packet; its handler runs on the server thread with the sending player (never null). */
    private static <T> void toServer(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder, BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, id, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((payload, context) -> {
                    context.get().setPacketHandled(true);
                    ServerPlayer sender = context.get().getSender();
                    if (sender != null) {
                        handler.accept(payload, sender);
                    }
                })
                .add();
    }

    public static void sendToServer(Object payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    /**
     * The undo / redo keys: undoes the player's newest Settings Copier paste (or redoes the newest undo), only while
     * they hold the copier. Undo itself
     * checks everything else (still loaded, unchanged since, may build, upgrades from the inventory).
     */
    private static void handleUndoPaste(UndoPastePayload payload, ServerPlayer player) {
        if (player.isSpectator()) {
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

    /** Client: Settings Copier outlines. Only runs on the client, so the client class is never loaded on a server. */
    private static void handlePasteHighlight(PasteHighlightPayload payload) {
        com.daisychain.client.ClientAccess.showPasteHighlight(payload.kind(), payload.positions(), payload.sides());
    }

    /**
     * A filter entry dragged in from JEI. Only for the side settings screen the player has open right now, while it's
     * still valid; the menu applies the same rules as a click (empty slot only, right kind, no duplicates).
     */
    private static void handleSetFilterGhost(SetFilterGhostPayload payload, ServerPlayer player) {
        if (player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (!(player.containerMenu instanceof PullSideMenu menu) || menu.containerId != payload.containerId()
                || !menu.stillValid(player)) {
            return;
        }
        menu.listDropped(payload.slot(), payload.stack(), player);
    }

    /** Only accepted for the Pull side screen the player has open right now, while it's still valid. */
    private static void handleSetFilterNumber(SetFilterNumberPayload payload, ServerPlayer player) {
        if (player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (!(player.containerMenu instanceof PullSideMenu menu) || menu.containerId != payload.containerId()
                || !menu.stillValid(player)) {
            return;
        }
        menu.setNumber(payload.slot(), payload.which(), payload.value());
    }

    /** Only accepted for the Pull side screen the player has open right now, while it's still valid. */
    private static void handleSetFilterMatch(SetFilterMatchPayload payload, ServerPlayer player) {
        if (player.isSpectator() || !player.mayBuild()) {
            return;
        }
        if (!(player.containerMenu instanceof PullSideMenu menu) || menu.containerId != payload.containerId()
                || !menu.stillValid(player)) {
            return;
        }
        menu.setMatch(payload.slot(), payload.mode(), payload.value());
    }

    private static void handleOpenPullSide(OpenPullSidePayload payload, ServerPlayer player) {
        if (player.isSpectator() || !player.mayBuild()) {
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
    private static void handleSetSideMode(SetSideModePayload payload, ServerPlayer player) {
        if (player.isSpectator() || !player.mayBuild()) {
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

    // Runs on the server thread (consumerMainThread).
    private static void handleUpdatePullSetting(UpdatePullSettingPayload payload, ServerPlayer player) {
        if (player.isSpectator() || !player.mayBuild()) {
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
