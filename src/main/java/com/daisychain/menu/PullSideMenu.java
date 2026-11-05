package com.daisychain.menu;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.filter.FluidGhost;
import com.daisychain.filter.ItemFilter;
import com.daisychain.filter.MatchMode;
import com.daisychain.item.UpgradeItem;
import com.daisychain.network.NetworkManager;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

import javax.annotation.Nullable;

/**
 * The container behind the Pull side screen and (from step 23) the Insert side screen: one upgrade slot (pull sides
 * only; on insert sides it only shows a parked upgrade, step 67), 54 filter "ghost" slots and the player's inventory.
 * On a pull side the filter says what to pull; on an insert side it says what the destination accepts.
 * Filter slots only ever hold copies: clicking one with an item lists that item, clicking with an empty hand or
 * shift-clicking clears it. Real items are never moved into or out of them.
 * The settings buttons use their own validated packets; "Clear filter" uses the vanilla menu-button packet.
 */
public final class PullSideMenu extends AbstractContainerMenu {
    // Layout (step 44, design spec v8). Item positions in pixels from the menu origin, which is the main panel's
    // top-left in Advanced view. Simple view draws the panel 36 px lower (one filter row instead of three) and the
    // short energy panel 54 px lower, so the upgrade slot and the inventory land on the same positions in every view.
    /** Advanced filter: 3 rows x 9 at (8 + 18c, 22 + 18r); page 2 uses the same places. */
    public static final int FILTER_X = 8;
    public static final int FILTER_Y = 22;
    /** Simple filter: entries 1-9 in one row, where Advanced has its third row. */
    public static final int SIMPLE_FILTER_Y = FILTER_Y + 36;
    public static final int UPGRADE_X = 8;
    public static final int UPGRADE_Y = 80;
    public static final int INVENTORY_X = 8;
    /** Top of the player's main inventory; the hotbar is 58 pixels lower. */
    public static final int INVENTORY_Y = 104;
    /** Main panel top relative to the menu origin, per view. */
    public static final int PANEL_OFFSET_ADVANCED = 0;
    public static final int PANEL_OFFSET_SIMPLE = 36;
    public static final int PANEL_OFFSET_NO_FILTER = 54;
    /** Filter entries per page in Advanced view (2 pages = all 54). */
    public static final int PAGE_SIZE = 27;
    /** Entries shown in Simple view. */
    public static final int SIMPLE_SIZE = 9;

    /** Menu button id for "Clear filter". */
    public static final int BUTTON_CLEAR_FILTER = 0;
    /** Menu button ids 10, 11, 12: show the Items / Fluids / Energy tab (universal pipes, step 33a). */
    public static final int BUTTON_SHOW_RESOURCE = 10;
    /** Step 44: the client says which view it shows (20 = Simple, 21 = Advanced). Not stored. */
    public static final int BUTTON_VIEW_SIMPLE = 20;
    public static final int BUTTON_VIEW_ADVANCED = 21;
    /** Step 44: the Advanced key: switches the view and stores it on the pipe (22 = Simple, 23 = Advanced). */
    public static final int BUTTON_STORE_SIMPLE = 22;
    public static final int BUTTON_STORE_ADVANCED = 23;
    /** Step 44: filter page keys (30 = page 1, 31 = page 2). */
    public static final int BUTTON_PAGE = 30;

    private static final int UPGRADE_SLOT = 0;
    /** Menu slots 1-54: filter entries 0-53 (Advanced view, by page). */
    private static final int FILTER_START = 1;
    private static final int FILTER_END = FILTER_START + ItemFilter.SIZE; // exclusive
    /** Menu slots 55-63: filter entries 0-8 again, placed for Simple view (only one of the two sets is ever active). */
    private static final int SIMPLE_START = FILTER_END;
    private static final int SIMPLE_END = SIMPLE_START + SIMPLE_SIZE; // exclusive
    private static final int INVENTORY_START = SIMPLE_END;
    private static final int INVENTORY_END = INVENTORY_START + 36; // exclusive: 27 main + 9 hotbar
    private static final int OFFHAND_BUTTON = 40; // vanilla's "swap with offhand" key (F) sends this
    private static final double MAX_DISTANCE_SQR = 8.0 * 8.0;

    private final BlockPos pos;
    private final Direction side;
    /** True for an insert side (a normal side facing an inventory), false for a pull side. */
    private final boolean insert;
    /** What the pipe carries. */
    private final PipeKind kind;
    /**
     * Which of the side's resources (PipeKind.ITEMS / FLUIDS / ENERGY_RES) the screen shows and edits: the filter
     * slots, amounts and filter mode all belong to it (step 31). Always one the pipe carries.
     */
    private int resource;
    /** Only set on the server. */
    @Nullable
    private final PipeDataBlockEntity data;
    /** 1 = Keep in source is paused because the source is too big to count. Synced to the client by vanilla. */
    private final DataSlot keepPaused;
    /**
     * Step 44: what the screen shows. Simple = filter entries 1-9; Advanced = 27 per page. Set on both sides (the client
     * tells the server with BUTTON_VIEW_* / BUTTON_STORE_* / BUTTON_PAGE), so slot clicks mean the same thing on both.
     */
    private boolean simpleView = true;
    private int page;

    /** Client: built from the extra data the server sent when opening. */
    public PullSideMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos(), readSide(extra), extra.readBoolean(), readKind(extra),
                extra.readByte(), null);
    }

    private PullSideMenu(int containerId, Inventory inventory, BlockPos pos, Direction side, boolean insert,
                         PipeKind kind, int resource, @Nullable PipeDataBlockEntity data) {
        super(ModMenus.PULL_SIDE.get(), containerId);
        this.pos = pos;
        this.side = side;
        this.insert = insert;
        this.kind = kind;
        this.resource = kind.carries(resource) ? resource : kind.firstResource();
        this.data = data;

        keepPaused = data != null ? new DataSlot() {
            @Override
            public int get() {
                if (data.getLevel() instanceof ServerLevel serverLevel) {
                    NetworkManager manager = NetworkManager.getExisting(serverLevel);
                    if (manager == null) {
                        return 0;
                    }
                    if (insert) {
                        // Bit 4: this destination's Max stock skipped it (too many slots to count).
                        return manager.isStockPaused(pos, side, PullSideMenu.this.resource) ? 4 : 0;
                    }
                    // Bit 1: Keep in source paused. Bit 2: Fill destination to skipped a destination.
                    int r = PullSideMenu.this.resource;
                    return (manager.isKeepPaused(pos, side, r) ? 1 : 0) | (manager.isFillPaused(pos, side, r) ? 2 : 0);
                }
                return 0;
            }

            @Override
            public void set(int value) {
            }
        } : DataSlot.standalone();
        addDataSlot(keepPaused);
        // Which resource is shown (step 33a): the server's choice, synced so the client screen follows it.
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return PullSideMenu.this.resource;
            }

            @Override
            public void set(int value) {
                if (PullSideMenu.this.kind.carries(value)) {
                    PullSideMenu.this.resource = value;
                }
            }
        });

        // Slot 0 always exists so slot numbers are the same for both kinds. On insert sides (step 67) it only shows a
        // parked upgrade (kept from when the side pulled), which can be taken out; nothing can be put in.
        Container upgrade = data != null ? new UpgradeSlotContainer(data, side) : new SimpleContainer(1);
        addSlot(new Slot(upgrade, 0, UPGRADE_X, UPGRADE_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                if (upgradeParked() || !(stack.getItem() instanceof UpgradeItem upgrade)) {
                    return false;
                }
                // Only a higher tier may replace the one in the slot (take it out first to fit a lower one).
                return !(getItem().getItem() instanceof UpgradeItem current)
                        || upgrade.tier().level() > current.tier().level();
            }

            @Override
            public boolean mayPickup(Player player) {
                return true;
            }

            @Override
            public boolean isActive() {
                return !upgradeParked() || hasItem();
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        Container filter = data != null ? new FilterSlotContainer(data, side, insert, this::resource)
                : new SimpleContainer(ItemFilter.SIZE);
        for (int entry = 0; entry < ItemFilter.SIZE; entry++) {
            int onPage = entry % PAGE_SIZE;
            addSlot(new GhostSlot(filter, entry, FILTER_X + (onPage % 9) * 18, FILTER_Y + (onPage / 9) * 18, this,
                    false));
        }
        for (int entry = 0; entry < SIMPLE_SIZE; entry++) {
            addSlot(new GhostSlot(filter, entry, FILTER_X + entry * 18, SIMPLE_FILTER_Y, this, true));
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, INVENTORY_Y + 58));
        }
    }

    private static PipeKind readKind(RegistryFriendlyByteBuf extra) {
        int value = extra.readByte();
        PipeKind[] kinds = PipeKind.values();
        return kinds[value >= 0 && value < kinds.length ? value : 0];
    }

    private static Direction readSide(RegistryFriendlyByteBuf extra) {
        int value = extra.readByte();
        return Direction.from3DDataValue(value >= 0 && value < 6 ? value : 0);
    }

    /**
     * Server: opens the settings of one side, the pull screen on a pull side or the insert screen on an insert side.
     * Does nothing on other sides, or for players who can't build. Callers check distance and that the pipe is loaded.
     */
    public static void openSettings(ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {
        if (player.isSpectator() || !player.mayBuild()) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (FlowPipeBlock.isPullSide(level, pos, state, side)) {
            PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, state);
            if (data != null) {
                open(player, data, pos, side, false);
            }
        } else if (FlowPipeBlock.isInsertSide(level, pos, state, side)) {
            // Insert settings live in the pipe's block entity; create it now (removed again if left unused).
            FlowPipeBlock.ensureData(level, pos);
            PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, level.getBlockState(pos));
            if (data != null) {
                open(player, data, pos, side, true);
            }
        }
    }

    /** Server: opens the Pull side screen (insert = false) or the Insert side screen (insert = true) for one side. */
    public static void open(ServerPlayer player, PipeDataBlockEntity data, BlockPos pos, Direction side, boolean insert) {
        PipeKind kind = FlowPipeBlock.kindOf(data.getBlockState());
        int resource = kind.firstResource();
        Component title = Component.translatable(insert ? "screen.daisychain.insert_side" : "screen.daisychain.pull_side",
                Component.translatable("message.daisychain.direction." + side.getSerializedName()));
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> new PullSideMenu(containerId, inventory, pos, side, insert, kind, resource,
                        data),
                title),
                buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeByte(side.get3DDataValue());
                    buffer.writeBoolean(insert);
                    buffer.writeByte(kind.ordinal());
                    buffer.writeByte(resource);
                });
    }

    /**
     * True if this side's upgrade slot only shows an upgrade that does nothing here (it can be taken out, nothing put
     * in): insert sides (step 67), and since step 84 every energy pipe side (energy pipes get their speed from tiers).
     */
    public boolean upgradeParked() {
        return insert || kind == PipeKind.ENERGY;
    }

    /** True for an insert side's menu, false for a pull side's. */
    public boolean isInsert() {
        return insert;
    }

    /** What the pipe carries. */
    public PipeKind kind() {
        return kind;
    }

    /** The resource the screen shows (PipeKind.ITEMS / FLUIDS / ENERGY_RES). */
    public int resource() {
        return resource;
    }

    /** The shown resource as a kind (ITEM, FLUID or ENERGY): decides units, steps, limits and help texts. */
    public PipeKind resourceKind() {
        return PipeKind.ofResource(resource);
    }

    /** True if the shown resource has the 54-slot filter (items or fluids; energy has none). */
    public boolean hasFilter() {
        return resourceKind().hasFilter();
    }

    /** True if the shown resource has the amounts (Batch / Keep / Fill / Max stock): items, mB or FE. */
    public boolean hasAmounts() {
        return resourceKind().hasAmounts();
    }

    /** True when the shown resource is fluids: the filter lists fluids. */
    public boolean isFluid() {
        return resource == PipeKind.FLUIDS;
    }

    public BlockPos pos() {
        return pos;
    }

    public Direction side() {
        return side;
    }

    /** Client: true if the server says this side's Keep in source is paused (source too big to count). */
    public boolean isKeepPaused() {
        return (keepPaused.get() & 1) != 0;
    }

    /** Client (insert side): the server says Max stock skipped this inventory because it's too big to count. */
    public boolean isStockPaused() {
        return (keepPaused.get() & 4) != 0;
    }

    /** Client: the server says Fill destination to skipped a destination that is too big to count. */
    public boolean isFillPaused() {
        return (keepPaused.get() & 2) != 0;
    }

    /** True for every filter slot (both the Advanced and the Simple set). */
    public static boolean isFilterSlot(int index) {
        return index >= FILTER_START && index < SIMPLE_END;
    }

    /** True while the screen shows the Simple view (step 44). */
    public boolean isSimpleView() {
        return simpleView;
    }

    /** The Advanced view's filter page (0 or 1). */
    public int page() {
        return page;
    }

    /** Client: the screen changed the view; the server hears it through a menu button. */
    public void setView(boolean simple) {
        simpleView = simple;
    }

    /** Client: the screen changed the page; the server hears it through a menu button. */
    public void setPage(int page) {
        this.page = page == 1 ? 1 : 0;
    }

    /** First and last+1 filter entry the current view shows (shift-click and JEI add to the first empty one). */
    public int firstShownEntry() {
        return simpleView ? 0 : page * PAGE_SIZE;
    }

    public int endShownEntry() {
        return simpleView ? SIMPLE_SIZE : page * PAGE_SIZE + PAGE_SIZE;
    }

    /** Server: closes the screen if the pipe is gone, the side stopped pulling, or the player walked away. */
    @Override
    public boolean stillValid(Player player) {
        if (data == null) {
            return true; // the client screen does its own check; the server's answer is the one that counts
        }
        Level level = player.level();
        if (data.isRemoved() || level.getBlockEntity(pos) != data) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        boolean rightKind = insert ? FlowPipeBlock.isInsertSide(level, pos, state, side)
                : FlowPipeBlock.isPullSide(level, pos, state, side);
        return rightKind && player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_DISTANCE_SQR;
    }

    /**
     * Server: when an insert side's screen closes without any insert setting made, the block entity that was created
     * for it is removed again if the pipe doesn't need it for anything else.
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (insert && data != null && !data.isRemoved() && data.getLevel() instanceof ServerLevel serverLevel) {
            FlowPipeBlock.dropDataIfUnused(serverLevel, pos);
        }
    }

    /**
     * Filter slots handle their own clicks and never touch real items. Everything else is vanilla.
     * Runs on the client too (as a prediction) and again on the server, which has the final say.
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (isFilterSlot(slotId)) {
            if (hasFilter()) {
                clickFilterSlot(slots.get(slotId), button, clickType, player);
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private void clickFilterSlot(Slot slot, int button, ClickType clickType, Player player) {
        switch (clickType) {
            // Click an empty slot with an item: list a copy of it. (Clicking a used slot never changes it;
            // with an empty hand the screen opens the entry's detail page instead.)
            case PICKUP -> listInSlot(slot, getCarried(), player);
            // Shift-click: the only way to remove an entry, so it can't happen by accident.
            case QUICK_MOVE -> slot.set(ItemStack.EMPTY);
            // Hotbar number key (or F for the offhand) while hovering: list a copy of that item.
            case SWAP -> {
                if ((button >= 0 && button < 9) || button == OFFHAND_BUTTON) {
                    listInSlot(slot, player.getInventory().getItem(button), player);
                }
            }
            // Dragging, throwing, middle-click and double-click do nothing on filter slots.
            default -> {
            }
        }
    }

    /**
     * Lists a copy of the stack in this slot if the slot is empty and no other slot lists it as a plain item.
     * Never clears or overwrites an entry (that would lose its match settings): only shift-click removes.
     */
    private void listInSlot(Slot slot, ItemStack stack, Player player) {
        if (stack.isEmpty() || slot.hasItem()) {
            return;
        }
        ItemStack ghost = ghostFor(stack, player);
        if (ghost.isEmpty() || findPlainEntry(ghost, slot.index) >= 0) {
            return; // nothing to list, or already listed; the server checks this too
        }
        slot.set(ghost);
    }

    /**
     * What clicking with this stack lists. Item pipes: a copy of the item (never a fluid ghost). Fluid pipes: the
     * fluid it holds (bucket, tank, cell...), or EMPTY if it holds none.
     */
    private ItemStack ghostFor(ItemStack stack, Player player) {
        if (isFluid()) {
            return FluidGhost.of(FluidGhost.fromClicked(stack), player.level().registryAccess());
        }
        return FluidGhost.isGhost(stack) ? ItemStack.EMPTY : ItemFilter.ghostOf(stack, player.level().registryAccess());
    }

    /**
     * Server: an entry dragged in from JEI (SetFilterGhostPayload, already checked: open menu, still valid, may build).
     * Same rules as clicking the slot with that stack.
     */
    public void listDropped(int filterSlot, ItemStack stack, Player player) {
        if (!hasFilter() || filterSlot < 0 || filterSlot >= ItemFilter.SIZE || stack.isEmpty()) {
            return;
        }
        listInSlot(slots.get(FILTER_START + filterSlot), stack, player);
    }

    /** Menu index of another filter slot listing this item in plain item mode, or -1. */
    private int findPlainEntry(ItemStack stack, int exceptIndex) {
        for (int i = FILTER_START; i < FILTER_END; i++) {
            if (i == exceptIndex) {
                continue;
            }
            ItemStack entry = slots.get(i).getItem();
            if (entry.isEmpty() || entry.has(ModDataComponents.FILTER_MATCH.get()) || !entry.is(stack.getItem())) {
                continue;
            }
            SimpleFluidContent mine = FluidGhost.contentOf(stack);
            SimpleFluidContent theirs = FluidGhost.contentOf(entry);
            if (mine == null || (theirs != null && theirs.getFluid() == mine.getFluid())) {
                return i; // same item, or for fluid ghosts the same fluid
            }
        }
        return -1;
    }

    /**
     * Server: the detail page changed how an entry matches. The payload handler already checked the player,
     * the open menu and stillValid; this checks the values. MOD mode always uses the entry's own mod.
     */
    public void setMatch(int filterSlot, int modeId, String value) {
        if (data == null || !hasFilter() || filterSlot < 0 || filterSlot >= ItemFilter.SIZE) {
            return;
        }
        MatchMode mode = MatchMode.byId(modeId);
        if (mode == null) {
            return;
        }
        ResourceLocation tag = null;
        if (mode == MatchMode.TAG) {
            tag = value.length() <= 256 ? ResourceLocation.tryParse(value) : null;
            if (tag == null) {
                return;
            }
            // Fluid pipes list fluid tags, item pipes item tags; either way it must exist on this server.
            boolean exists = isFluid()
                    ? BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, tag)).isPresent()
                    : BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tag)).isPresent();
            if (!exists) {
                return; // no such tag on this server
            }
        }
        data.setFilterMatch(side, filterSlot, mode, tag, insert, resource);
    }

    /**
     * Server: the detail page changed an entry's own Batch / Keep / Fill. The payload handler already checked the
     * player, the open menu and stillValid; the filter checks the entry and the range.
     */
    public void setNumber(int filterSlot, int which, int value) {
        if (data == null || !hasAmounts() || filterSlot < 0 || filterSlot >= ItemFilter.SIZE) {
            return;
        }
        if (insert && which != ItemFilter.FILL) {
            return; // insert filter entries only have Max stock (stored as their FILL number)
        }
        data.setFilterNumber(side, filterSlot, which, value, insert, resource);
    }

    /** Filter entry number (0-53) for a menu slot index (either set), or -1. */
    public static int filterSlotOf(int menuIndex) {
        if (menuIndex >= SIMPLE_START && menuIndex < SIMPLE_END) {
            return menuIndex - SIMPLE_START;
        }
        return menuIndex >= FILTER_START && menuIndex < FILTER_END ? menuIndex - FILTER_START : -1;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return !(slot instanceof GhostSlot) && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return !(slot instanceof GhostSlot) && super.canDragTo(slot);
    }

    /**
     * Server: the "Clear filter" button, and the resource tabs. Vanilla only calls this after stillValid. A tab
     * switches which resource the filter slots, amounts and filter mode belong to; the slots resync by themselves.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == BUTTON_CLEAR_FILTER && data != null && hasFilter()) {
            data.clearFilter(side, insert, resource);
            return true;
        }
        int shown = id - BUTTON_SHOW_RESOURCE;
        if (shown >= 0 && shown < PipeKind.RESOURCE_COUNT && kind.carries(shown)) {
            resource = shown;
            return true;
        }
        switch (id) {
            case BUTTON_VIEW_SIMPLE, BUTTON_VIEW_ADVANCED -> {
                simpleView = id == BUTTON_VIEW_SIMPLE;
                return true;
            }
            case BUTTON_STORE_SIMPLE, BUTTON_STORE_ADVANCED -> {
                simpleView = id == BUTTON_STORE_SIMPLE;
                if (data != null) {
                    data.setViewMode(simpleView ? PipeDataBlockEntity.VIEW_SIMPLE : PipeDataBlockEntity.VIEW_ADVANCED);
                }
                return true;
            }
            case BUTTON_PAGE, BUTTON_PAGE + 1 -> {
                page = id - BUTTON_PAGE;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** True if the pipe carries several resources, so the screen shows Items / Fluids / Energy tabs. */
    public boolean hasResourceTabs() {
        return kind == PipeKind.UNIVERSAL;
    }

    /**
     * Shift-click:
     * - upgrade slot -> inventory;
     * - an upgrade from the inventory -> the empty upgrade slot (nothing if it's taken; never the filter);
     * - anything else from the inventory -> listed in the first empty filter slot (the item stays in your inventory).
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || isFilterSlot(index)) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        if (index == UPGRADE_SLOT) {
            ItemStack original = stack.copy();
            if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true)) {
                return ItemStack.EMPTY;
            }
            finishMove(slot, stack);
            return original;
        }
        if (!insert && stack.getItem() instanceof UpgradeItem) {
            // Upgrades only ever go to the upgrade slot. Vanilla repeats shift-click while the stack has items left,
            // so without this the 2nd upgrade of a stack ended up listed in the filter too.
            // (An upgrade can still be listed in the filter by clicking it onto a filter slot.)
            if (slots.get(UPGRADE_SLOT).hasItem()) {
                return ItemStack.EMPTY;
            }
            ItemStack original = stack.copy();
            if (!moveItemStackTo(stack, UPGRADE_SLOT, UPGRADE_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
            finishMove(slot, stack);
            return original;
        }
        if (hasFilter()) {
            addToFilter(stack, player);
        }
        return ItemStack.EMPTY; // nothing moved, so vanilla stops here
    }

    private static void finishMove(Slot slot, ItemStack stack) {
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
    }

    /** Lists a copy of the stack in the first empty filter slot, unless it's already listed as a plain item. */
    private void addToFilter(ItemStack stack, Player player) {
        ItemStack ghost = ghostFor(stack, player);
        if (ghost.isEmpty() || findPlainEntry(ghost, -1) >= 0) {
            return;
        }
        for (int entry = firstShownEntry(); entry < endShownEntry(); entry++) {
            Slot slot = slots.get(FILTER_START + entry);
            if (slot.getItem().isEmpty()) {
                slot.set(ghost);
                return;
            }
        }
    }

    /** A filter slot: shows a copy, can't be taken from or placed into by normal means. */
    static final class GhostSlot extends Slot {
        /** The slot is hidden and ignores clicks while the menu shows a resource without a filter (energy). */
        private final PullSideMenu menu;
        /** True for the Simple view's copy of entries 1-9. */
        private final boolean simple;

        GhostSlot(Container container, int index, int x, int y, PullSideMenu menu, boolean simple) {
            super(container, index, x, y);
            this.menu = menu;
            this.simple = simple;
        }

        /** Only the slots of the current view (and page) exist for clicks and drawing. */
        @Override
        public boolean isActive() {
            if (!menu.hasFilter() || simple != menu.simpleView) {
                return false;
            }
            return simple || getContainerSlot() / PAGE_SIZE == menu.page;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
