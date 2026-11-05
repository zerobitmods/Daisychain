package com.daisychain.item;

import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.network.NetworkManager;
import com.daisychain.network.PipeNetwork;
import com.daisychain.net.PasteHighlightPayload;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Settings Copier (step 24). Right-click a pull or insert side: copies that side's settings (filter, numbers, modes,
 * and for pull sides the upgrade tier). Sneak + right-click a side of the same kind (pull onto pull, insert onto
 * insert) on any pipe of the same type: pastes them there. Other kinds are refused with a message. A pasted upgrade is
 * taken from the player's inventory (never created). Sneak + right-click the air: clears the copier. Feedback goes to
 * the action bar (above the hotbar).
 * <p>
 * Step 48b/48d: a paste needs a confirming second click on the same target within 3 seconds. After a confirmed side
 * paste, more sides paste with one click until the player stops for 5 seconds (a streak). Sneak + right-click a pipe's
 * center (twice) pastes onto every side of the same kind on that network. Each network paste or streak can be undone
 * once (Z while holding the copier, or /daisychain undo [number]), see {@link CopierSessions}. Since step 48g all
 * feedback is one line above the hotbar; nothing goes to chat.
 */
public final class SettingsCopierItem extends Item {

    public SettingsCopierItem(Properties properties) {
        super(properties);
    }

    // The pipe block skips its own click handling for this item, so this runs for both plain and sneaking clicks.
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            // Sneaking on another block: stop here, so the click doesn't fall through to "clear" (that's for the air).
            return player != null && player.isSecondaryUseActive() ? InteractionResult.FAIL : InteractionResult.PASS;
        }
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel serverLevel) {
            ItemStack stack = context.getItemInHand();
            if (player.isSecondaryUseActive() && FlowPipeBlock.isCoreHit(state, pos, context.getClickLocation())) {
                pasteNetwork(serverLevel, pos, state, player, stack);
            } else {
                Direction side = FlowPipeBlock.targetedSide(state, pos, context.getClickLocation(),
                        context.getClickedFace());
                if (player.isSecondaryUseActive()) {
                    paste(serverLevel, pos, state, side, player, stack);
                } else {
                    copy(serverLevel, pos, state, side, player, stack);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** Sneak + right-click the air clears what the copier holds. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive() || !stack.has(ModDataComponents.COPIED_SETTINGS.get())) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide()) {
            stack.remove(ModDataComponents.COPIED_SETTINGS.get());
            CopierSessions.of(player.getUUID()).reset();
            highlight(player, PasteHighlightPayload.CLEAR, BlockPos.ZERO, Direction.DOWN);
            tell(player, Component.translatable("message.daisychain.copier.cleared"), ChatFormatting.GRAY);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private static void copy(ServerLevel level, BlockPos pos, BlockState state, Direction side, Player player,
                             ItemStack stack) {
        boolean pull = FlowPipeBlock.isPullSide(level, pos, state, side);
        boolean insert = !pull && FlowPipeBlock.isInsertSide(level, pos, state, side);
        if (!pull && !insert) {
            tell(player, Component.translatable("message.daisychain.copier.no_side"), ChatFormatting.YELLOW);
            return;
        }
        PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, state);
        // A side nobody changed has no data yet: copy the defaults (an empty tag means "all default").
        CompoundTag settings = data != null ? data.copySide(side, insert, level.registryAccess()) : new CompoundTag();
        if (state.getBlock() instanceof EnergyPipeBlock) {
            settings.remove("Upgrade"); // step 84: energy pipes don't use upgrades
        }
        CopiedSettings copied = new CopiedSettings(insert, pipeId(state), settings);
        stack.set(ModDataComponents.COPIED_SETTINGS.get(), copied);
        // New settings in the copier: a paste waiting for its second click, or a streak, starts over.
        CopierSessions.of(player.getUUID()).reset();
        highlight(player, PasteHighlightPayload.COPIED, pos, side);
        tell(player, Component.translatable("message.daisychain.copier.copied", kindName(insert), sideName(side),
                copied.filterEntries()), ChatFormatting.GREEN);
    }

    /** The copier's settings if they may be pasted on this pipe by this player, else null (with a message). */
    @Nullable
    private static CopiedSettings usableCopy(BlockState state, Player player, ItemStack stack) {
        CopiedSettings copied = stack.get(ModDataComponents.COPIED_SETTINGS.get());
        if (copied == null) {
            tell(player, Component.translatable("message.daisychain.copier.empty"), ChatFormatting.YELLOW);
            return null;
        }
        if (!player.mayBuild()) {
            return null;
        }
        if (!copied.pipe().equals(pipeId(state))) {
            tell(player, Component.translatable("message.daisychain.copier.wrong_pipe"), ChatFormatting.YELLOW);
            return null;
        }
        return copied;
    }

    private static void paste(ServerLevel level, BlockPos pos, BlockState state, Direction side, Player player,
                              ItemStack stack) {
        CopiedSettings copied = usableCopy(state, player, stack);
        if (copied == null) {
            return;
        }
        boolean pullHere = FlowPipeBlock.isPullSide(level, pos, state, side);
        boolean insertHere = !pullHere && FlowPipeBlock.isInsertSide(level, pos, state, side);
        if (!pullHere && !insertHere) {
            tell(player, Component.translatable("message.daisychain.copier.no_side"), ChatFormatting.YELLOW);
            return;
        }
        if (copied.insert() != insertHere) {
            // Pull settings only go on pull sides, insert settings on insert sides; the player changes the type
            // themselves (wrench or Pipe sides screen), so a paste never changes how a side works (zerobit, step 25).
            tell(player, Component.translatable(copied.insert() ? "message.daisychain.copier.wrong_kind_insert"
                    : "message.daisychain.copier.wrong_kind_pull"), ChatFormatting.YELLOW);
            return;
        }
        CopierSessions.Session session = CopierSessions.of(player.getUUID());
        long now = level.getGameTime();
        boolean streak = session.inStreak(level.dimension(), now);
        if (!streak) {
            if (!session.confirm(level.dimension(), pos, side, now)) {
                highlight(player, PasteHighlightPayload.PREVIEW, pos, side);
                tell(player, Component.translatable("message.daisychain.copier.confirm_paste"), ChatFormatting.GOLD);
                return;
            }
            session.newAction();
        }
        Item[] missing = new Item[1];
        CopierSessions.SideChange change = pasteOne(level, pos, side, copied, player, missing);
        session.extendStreak(level.dimension(), now);
        // The first paste of a streak replaces the old green/blue outlines; later ones add to them.
        highlight(player, streak ? PasteHighlightPayload.PASTED_STREAK : PasteHighlightPayload.PASTED, pos, side);
        if (change != null) {
            session.record(change, level.dimension(), copied.pipe(), maxSides());
        }
        CopierSessions.Batch batch = session.openBatch();
        // One line above the hotbar, no chat (step 48g): the outlines show what changed, Z undoes it.
        MutableComponent message;
        if (change == null) {
            message = Component.translatable("message.daisychain.copier.already", sideName(side));
        } else if (missing[0] != null) {
            message = Component.translatable("message.daisychain.copier.pasted_no_upgrade", sideName(side),
                    missing[0].getDescription());
        } else {
            message = Component.translatable("message.daisychain.copier.pasted", sideName(side));
        }
        if (batch != null) {
            message.append(" ").append(Component.translatable("message.daisychain.copier.paste_count", batch.id,
                    batch.changes.size())).append(undoKeyHint());
        }
        tell(player, message, change == null ? ChatFormatting.GRAY
                : missing[0] != null ? ChatFormatting.GOLD : ChatFormatting.GREEN);
    }

    /** Sneak + right-click a pipe's center: paste onto every pull (or insert) side of this network, after a confirm. */
    private static void pasteNetwork(ServerLevel level, BlockPos pos, BlockState state, Player player,
                                     ItemStack stack) {
        CopiedSettings copied = usableCopy(state, player, stack);
        if (copied == null) {
            return;
        }
        PipeNetwork network = NetworkManager.get(level).getOrBuild(pos);
        if (network == null) {
            return;
        }
        // Collect the targets first: pasting insert settings rebuilds the network.
        LongArrayList targetPipes = new LongArrayList();
        List<Direction> targetSides = new java.util.ArrayList<>();
        for (int i = 0; i < network.pipeCount(); i++) {
            BlockPos pipePos = BlockPos.of(network.pipeAt(i));
            if (!level.isLoaded(pipePos)) {
                continue;
            }
            BlockState pipeState = level.getBlockState(pipePos);
            if (!(pipeState.getBlock() instanceof FlowPipeBlock)) {
                continue;
            }
            for (Direction side : Direction.values()) {
                boolean match = copied.insert() ? FlowPipeBlock.isInsertSide(level, pipePos, pipeState, side)
                        : FlowPipeBlock.isPullSide(level, pipePos, pipeState, side);
                if (match) {
                    targetPipes.add(pipePos.asLong());
                    targetSides.add(side);
                }
            }
        }
        int count = targetSides.size();
        String kind = copied.insert() ? "insert" : "pull";
        if (count == 0) {
            tell(player, Component.translatable("message.daisychain.copier.network_none_" + kind), ChatFormatting.YELLOW);
            return;
        }
        if (count > maxSides()) {
            tell(player, Component.translatable("message.daisychain.copier.network_too_many", count, maxSides()),
                    ChatFormatting.YELLOW);
            return;
        }
        CopierSessions.Session session = CopierSessions.of(player.getUUID());
        if (!session.confirm(level.dimension(), pos, null, level.getGameTime())) {
            highlight(player, PasteHighlightPayload.PREVIEW, targetPipes, targetSides);
            tell(player, Component.translatable("message.daisychain.copier.confirm_network_" + kind, count),
                    ChatFormatting.GOLD);
            return;
        }
        session.reset();
        session.newAction();
        CopierSessions.Batch previous = session.find(0);
        int withoutUpgrade = 0;
        Item missingItem = null;
        Item[] missing = new Item[1];
        for (int i = 0; i < count; i++) {
            BlockPos pipePos = BlockPos.of(targetPipes.getLong(i));
            if (!(level.getBlockState(pipePos).getBlock() instanceof FlowPipeBlock)) {
                continue;
            }
            missing[0] = null;
            CopierSessions.SideChange change = pasteOne(level, pipePos, targetSides.get(i), copied, player, missing);
            if (missing[0] != null) {
                withoutUpgrade++;
                missingItem = missing[0];
            }
            if (change != null) {
                session.record(change, level.dimension(), copied.pipe(), maxSides());
            }
        }
        session.newAction(); // a network paste is complete: the next paste starts its own batch
        highlight(player, PasteHighlightPayload.PASTED, targetPipes, targetSides);
        CopierSessions.Batch batch = session.find(0);
        if (batch == null || batch == previous) {
            tell(player, Component.translatable("message.daisychain.copier.network_unchanged"), ChatFormatting.GRAY);
            return;
        }
        MutableComponent message = Component.translatable("message.daisychain.copier.pasted_network", batch.id,
                batch.changes.size());
        if (missingItem != null) {
            message.append(" ").append(Component.translatable("message.daisychain.copier.pasted_network_missing",
                    withoutUpgrade, missingItem.getDescription()));
        }
        message.append(undoKeyHint());
        tell(player, message, missingItem != null ? ChatFormatting.GOLD : ChatFormatting.GREEN);
    }

    /**
     * Pastes onto one side (already checked to be the right kind) and its upgrade. Returns what changed, for undo, or
     * null if the side already had these settings. missing[0] is set to the upgrade the player didn't have.
     */
    @Nullable
    private static CopierSessions.SideChange pasteOne(ServerLevel level, BlockPos pos, Direction side,
                                                      CopiedSettings copied, Player player, Item[] missing) {
        boolean insert = copied.insert();
        PipeDataBlockEntity existing = FlowPipeBlock.getData(level, pos, level.getBlockState(pos));
        CompoundTag before = existing != null ? existing.copySide(side, insert, level.registryAccess()) : null;
        int beforeUpgrade = existing != null ? existing.upgradeLevel(side) : 0;
        FlowPipeBlock.ensureData(level, pos);
        PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, level.getBlockState(pos));
        if (data == null) {
            return null;
        }
        if (before == null) {
            before = data.copySide(side, insert, level.registryAccess()); // no data yet: the defaults
        }
        data.pasteSide(side, insert, copied.settings(), level.registryAccess());
        boolean energyPipe = level.getBlockState(pos).getBlock() instanceof EnergyPipeBlock;
        missing[0] = insert || energyPipe ? null : pasteUpgrade(data, side, copied.upgradeLevel(), player);
        CompoundTag after = data.copySide(side, insert, level.registryAccess());
        // Pasting all-default insert settings leaves nothing the block entity must keep: let the pipe drop it.
        FlowPipeBlock.dropDataIfUnused(level, pos);
        return before.equals(after) ? null
                : new CopierSessions.SideChange(pos.immutable(), side, insert, before, beforeUpgrade, after);
    }

    /**
     * The undo key or /daisychain undo [number] (0 = newest): puts every side of the player's latest paste batch back the way it
     * was. Sides changed since (by anyone), removed, turned into another kind or not loaded are left alone. Upgrades
     * follow the paste rules: the pasted one goes back to the player, the old one is taken from their inventory.
     */
    public static void undo(ServerPlayer player, int id) {
        CopierSessions.Session session = CopierSessions.existing(player.getUUID());
        CopierSessions.Batch batch = session != null ? session.find(id) : null;
        if (batch == null) {
            tell(player, id == 0 ? Component.translatable("message.daisychain.copier.undo_none")
                    : Component.translatable("message.daisychain.copier.undo_gone", id, CopierSessions.HISTORY),
                    ChatFormatting.GRAY);
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != batch.dimension) {
            tell(player, Component.translatable("message.daisychain.copier.undo_other_dimension"), ChatFormatting.YELLOW);
            return;
        }
        if (!player.mayBuild()) {
            return;
        }
        session.remove(batch);
        CopierSessions.Batch undone = new CopierSessions.Batch(batch.id, batch.dimension, batch.pipe);
        LongArrayList restoredPipes = new LongArrayList();
        List<Direction> restoredSides = new java.util.ArrayList<>();
        int restored = 0;
        int skipped = 0;
        int keptUpgrade = 0;
        Item missingItem = null;
        for (int i = batch.changes.size() - 1; i >= 0; i--) {
            CopierSessions.SideChange change = batch.changes.get(i);
            BlockPos pos = change.pos();
            if (!level.isLoaded(pos)) {
                skipped++;
                continue;
            }
            BlockState state = level.getBlockState(pos);
            boolean sameKind = state.getBlock() instanceof FlowPipeBlock && pipeId(state).equals(batch.pipe)
                    && (change.insert() ? FlowPipeBlock.isInsertSide(level, pos, state, change.side())
                    : FlowPipeBlock.isPullSide(level, pos, state, change.side()));
            if (!sameKind) {
                skipped++;
                continue;
            }
            FlowPipeBlock.ensureData(level, pos);
            PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, level.getBlockState(pos));
            if (data == null) {
                skipped++;
                continue;
            }
            if (!data.copySide(change.side(), change.insert(), level.registryAccess()).equals(change.after())) {
                FlowPipeBlock.dropDataIfUnused(level, pos);
                skipped++;
                continue;
            }
            data.pasteSide(change.side(), change.insert(), change.before(), level.registryAccess());
            if (!change.insert()) {
                Item missing = restoreUpgrade(data, change.side(), change.beforeUpgrade(), player);
                if (missing != null) {
                    keptUpgrade++;
                    missingItem = missing;
                }
            }
            FlowPipeBlock.dropDataIfUnused(level, pos);
            restored++;
            restoredPipes.add(pos.asLong());
            restoredSides.add(change.side());
            undone.changes.add(0, change); // keep the paste's own order for redo
        }
        if (!undone.changes.isEmpty()) {
            session.pushRedo(undone);
        }
        highlight(player, PasteHighlightPayload.UNDONE, restoredPipes, restoredSides);
        // One line above the hotbar; anything left alone turns it yellow.
        MutableComponent message = Component.translatable("message.daisychain.copier.undo_done", batch.id, restored);
        if (skipped > 0) {
            message.append(" ").append(Component.translatable("message.daisychain.copier.undo_skipped", skipped));
        }
        if (missingItem != null) {
            message.append(" ").append(Component.translatable("message.daisychain.copier.undo_missing", keptUpgrade,
                    missingItem.getDescription()));
        }
        if (!undone.changes.isEmpty()) {
            message.append(redoKeyHint());
        }
        tell(player, message, skipped > 0 || missingItem != null ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
    }

    /**
     * The redo key or /daisychain redo (step 48h): pastes the newest undone batch again, onto the sides that still look
     * the way the undo left them (anything changed since is left alone). Upgrades by the paste rules: the pasted tier
     * is taken from the inventory again, the one it replaces goes back. The batch becomes undoable again.
     */
    public static void redo(ServerPlayer player) {
        CopierSessions.Session session = CopierSessions.existing(player.getUUID());
        CopierSessions.Batch batch = session != null ? session.peekRedo() : null;
        if (batch == null) {
            tell(player, Component.translatable("message.daisychain.copier.redo_none"), ChatFormatting.GRAY);
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != batch.dimension) {
            tell(player, Component.translatable("message.daisychain.copier.undo_other_dimension"), ChatFormatting.YELLOW);
            return;
        }
        if (!player.mayBuild()) {
            return;
        }
        session.removeRedo(batch);
        CopierSessions.Batch redone = new CopierSessions.Batch(batch.id, batch.dimension, batch.pipe);
        LongArrayList pipes = new LongArrayList();
        List<Direction> sides = new java.util.ArrayList<>();
        int skipped = 0;
        int withoutUpgrade = 0;
        Item missingItem = null;
        for (CopierSessions.SideChange change : batch.changes) {
            BlockPos pos = change.pos();
            if (!level.isLoaded(pos)) {
                skipped++;
                continue;
            }
            BlockState state = level.getBlockState(pos);
            boolean sameKind = state.getBlock() instanceof FlowPipeBlock && pipeId(state).equals(batch.pipe)
                    && (change.insert() ? FlowPipeBlock.isInsertSide(level, pos, state, change.side())
                    : FlowPipeBlock.isPullSide(level, pos, state, change.side()));
            if (!sameKind) {
                skipped++;
                continue;
            }
            FlowPipeBlock.ensureData(level, pos);
            PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, level.getBlockState(pos));
            if (data == null) {
                skipped++;
                continue;
            }
            if (!data.copySide(change.side(), change.insert(), level.registryAccess()).equals(change.before())) {
                FlowPipeBlock.dropDataIfUnused(level, pos);
                skipped++;
                continue;
            }
            data.pasteSide(change.side(), change.insert(), change.after(), level.registryAccess());
            if (!change.insert()) {
                Item missing = restoreUpgrade(data, change.side(), change.after().getByte("Upgrade"), player);
                if (missing != null) {
                    withoutUpgrade++;
                    missingItem = missing;
                }
            }
            FlowPipeBlock.dropDataIfUnused(level, pos);
            redone.changes.add(change);
            pipes.add(pos.asLong());
            sides.add(change.side());
        }
        if (!redone.changes.isEmpty()) {
            session.pushHistory(redone, maxSides());
        }
        highlight(player, PasteHighlightPayload.PASTED, pipes, sides);
        MutableComponent message = Component.translatable("message.daisychain.copier.redo_done", batch.id,
                redone.changes.size());
        if (skipped > 0) {
            message.append(" ").append(Component.translatable("message.daisychain.copier.undo_skipped", skipped));
        }
        if (missingItem != null) {
            message.append(" ").append(Component.translatable("message.daisychain.copier.pasted_network_missing",
                    withoutUpgrade, missingItem.getDescription()));
        }
        if (!redone.changes.isEmpty()) {
            message.append(undoKeyHint());
        }
        tell(player, message, skipped > 0 || missingItem != null ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
    }

    /** Undo: back to the old upgrade level. Removing one gives it back; an old one is taken from the inventory. */
    @Nullable
    private static Item restoreUpgrade(PipeDataBlockEntity data, Direction side, int before, Player player) {
        int current = data.upgradeLevel(side);
        if (current == before) {
            return null;
        }
        if (before == 0) {
            data.setUpgradeLevel(side, 0);
            FlowPipeBlock.giveUpgradeBack(player, current);
            return null;
        }
        return pasteUpgrade(data, side, before, player);
    }

    private static int maxSides() {
        return DaisychainConfig.MAX_NETWORK_PASTE_SIDES.get();
    }

    /** Outlines one side for the player (see client/PasteHighlights). */
    private static void highlight(Player player, byte kind, BlockPos pos, Direction side) {
        highlight(player, kind, LongArrayList.wrap(new long[]{pos.asLong()}), List.of(side));
    }

    /** Outlines these sides for the player only. Fake players (machines using the copier) get nothing. */
    private static void highlight(Player player, byte kind, LongArrayList pipes, List<Direction> sides) {
        if (!(player instanceof ServerPlayer serverPlayer) || player instanceof FakePlayer) {
            return;
        }
        int count = Math.min(pipes.size(), sides.size());
        long[] positions = new long[count];
        byte[] sideValues = new byte[count];
        for (int i = 0; i < count; i++) {
            positions[i] = pipes.getLong(i);
            sideValues[i] = (byte) sides.get(i).get3DDataValue();
        }
        PacketDistributor.sendToPlayer(serverPlayer, new PasteHighlightPayload(kind, positions, sideValues));
    }

    /** " · R to redo" with the player's own key for "Redo last undo". */
    private static Component redoKeyHint() {
        return Component.translatable("message.daisychain.copier.redo_key", Component.keybind("key.daisychain.redo_paste"))
                .withStyle(ChatFormatting.GRAY);
    }

    /** " · Z to undo" with the player's own key for "Undo last paste" (the client fills in the key name). */
    private static Component undoKeyHint() {
        return Component.translatable("message.daisychain.copier.undo_key", Component.keybind("key.daisychain.undo_paste"))
                .withStyle(ChatFormatting.GRAY);
    }

    /**
     * Installs the copied upgrade tier on the side, taking one from the player's inventory (creative: free) and giving
     * back the one it replaces. Copied "no upgrade" leaves the side's own upgrade alone. Returns the upgrade item if the
     * player didn't have one, else null.
     */
    @Nullable
    private static Item pasteUpgrade(PipeDataBlockEntity data, Direction side, int wanted, Player player) {
        UpgradeItem item = ModItems.upgradeFor(wanted);
        int current = data.upgradeLevel(side);
        if (item == null || current == wanted) {
            return null;
        }
        if (!player.getAbilities().instabuild) {
            Inventory inventory = player.getInventory();
            int slot = -1;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).is(item)) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                return item;
            }
            inventory.removeItem(slot, 1);
        }
        data.setUpgradeLevel(side, wanted);
        FlowPipeBlock.giveUpgradeBack(player, current);
        return null;
    }

    /** The pipe type settings belong to. Step 84: every energy pipe tier counts as the basic energy pipe. */
    private static String pipeId(BlockState state) {
        if (state.getBlock() instanceof EnergyPipeBlock) {
            return BuiltInRegistries.BLOCK.getKey(ModBlocks.ENERGY_PIPE.get()).toString();
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static Component kindName(boolean insert) {
        return Component.translatable(insert ? "message.daisychain.copier.kind.insert" : "message.daisychain.copier.kind.pull");
    }

    private static Component sideName(Direction side) {
        return Component.translatable("message.daisychain.direction." + side.getSerializedName());
    }

    /** A short line above the hotbar. */
    private static void tell(Player player, Component message, ChatFormatting color) {
        player.displayClientMessage(message.copy().withStyle(color), true);
    }

    /** Glints while it holds settings, so a loaded copier is easy to tell apart. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModDataComponents.COPIED_SETTINGS.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CopiedSettings copied = stack.get(ModDataComponents.COPIED_SETTINGS.get());
        if (copied == null) {
            tooltip.add(Component.translatable("tooltip.daisychain.copier.empty").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.daisychain.copier.holds", kindName(copied.insert()))
                    .withStyle(ChatFormatting.GREEN));
            int entries = copied.filterEntries();
            if (entries > 0) {
                tooltip.add(Component.translatable(copied.filterDenies()
                                ? "tooltip.daisychain.copier.filter_deny" : "tooltip.daisychain.copier.filter_allow", entries)
                        .withStyle(ChatFormatting.GRAY));
            }
            UpgradeItem upgrade = ModItems.upgradeFor(copied.upgradeLevel());
            if (upgrade != null) {
                tooltip.add(Component.translatable("tooltip.daisychain.copier.upgrade", upgrade.getDescription())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (copied.insert() && copied.settings().getInt("Priority") != 0) {
                tooltip.add(Component.translatable("tooltip.daisychain.copier.priority",
                        copied.settings().getInt("Priority")).withStyle(ChatFormatting.GRAY));
            }
        }
        tooltip.add(Component.translatable("tooltip.daisychain.copier.copy").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.copier.paste").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.copier.paste_network").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.copier.clear").withStyle(ChatFormatting.DARK_GRAY));
    }
}
