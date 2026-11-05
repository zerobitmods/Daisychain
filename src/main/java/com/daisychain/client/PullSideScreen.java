package com.daisychain.client;

import com.daisychain.block.Distribution;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.block.RedstoneMode;
import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.config.UpgradeSpeeds;
import com.daisychain.item.UpgradeItem;
import com.daisychain.Daisychain;
import com.daisychain.filter.FilterMatch;
import com.daisychain.filter.FilterNumbers;
import com.daisychain.filter.FluidGhost;
import com.daisychain.filter.ItemFilter;
import com.daisychain.filter.MatchMode;
import com.daisychain.item.UpgradeTier;
import com.daisychain.menu.PullSideMenu;
import com.daisychain.net.SetFilterMatchPayload;
import com.daisychain.net.SetFilterNumberPayload;
import com.daisychain.net.UpdatePullSettingPayload;
import com.daisychain.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings for one pull side or insert side, in the Daisychain GUI from the design spec (step 44, v8 "GUI v3").
 * <ul>
 *   <li>Simple view: one panel with 9 filter slots and a control row (upgrade, filter mode, redstone or priority,
 *   Advanced key).</li>
 *   <li>Advanced view: the same panel with 3 filter rows and 2 pages (all 54 entries), plus a drawer on the right with
 *   the other settings. The view is stored on the pipe (shared by all players); a pipe with none opens in the
 *   player's own default (daisychain-client.toml).</li>
 * </ul>
 * The drawer (step 46) follows spec 5.2: dim labels on the left, choice keys and number fields right-aligned. Buttons
 * ask the server to change a setting; what is shown is always what the server last confirmed (or a just-clicked value
 * for a second).
 * <p>
 * Step 47 (spec 5.3): clicking a filter entry opens it inside the drawer (in both views; in Simple view the drawer
 * appears just for the entry, without the amounts): how it matches (Item / +Data / Tag / Mod), a tag list with search and pages, and the
 * entry's own amounts, each with a reset key. The entry's slot gets a white ring. Back, Done, Esc or E return to the
 * settings. This replaced the separate FilterEntryScreen.
 */
public final class PullSideScreen extends AbstractContainerScreen<PullSideMenu> {
    /** Step 69: the parked upgrade's pause badge (ARGB). */
    private static final int PARKED_BADGE = 0xFF1E1E22;
    private static final int PARKED_BADGE_BARS = 0xFFDCDCE1;


    private static final int MAIN_WIDTH = 176;
    private static final int DRAWER_GAP = 4;
    private static final int DRAWER_WIDTH = 150;
    private static final int DRAWER_PAD = 7;
    /** Drawer content runs from x 7 to x 143 (spec 5.2). */
    private static final int DRAWER_RIGHT = 143;
    /** Number fields are at least this wide; a column of fields shares one width (spec 4). */
    private static final int FIELD_WIDTH = 60;
    private static final int ROW_HEIGHT = 13;
    private static final int PITCH = 18;
    private static final int[] PRIORITY_LEVELS = {-10, 0, 10};
    /** Typing targets at or above this are a filter entry's own number (ENTRY_EDIT + BATCH/KEEP/FILL). */
    private static final int ENTRY_EDIT = 100;
    /** Entry amounts: the field ends at x 129, the reset key sits at x 130 (spec 5.3). */
    private static final int ENTRY_FIELD_RIGHT = 129;
    private static final int TAG_ROWS = 3;
    private static final int SEARCH_WIDTH = 76;
    private static final MatchMode[] MODE_ORDER = {MatchMode.ITEM, MatchMode.DATA, MatchMode.TAG, MatchMode.MOD};
    private static final int[] ENTRY_SETTINGS = {
            PipeDataBlockEntity.SETTING_BATCH, PipeDataBlockEntity.SETTING_KEEP, PipeDataBlockEntity.SETTING_FILL};
    private static final String[] ENTRY_LABELS = {
            "screen.daisychain.drawer.batch", "screen.daisychain.drawer.keep", "screen.daisychain.drawer.fill"};

    private final BlockPos pos;
    private final Direction side;
    /** True when this screen is for an insert side. */
    private final boolean insert;
    /** Set once the view has been picked (first init); later inits (resize, Advanced key) keep the menu's view. */
    private boolean viewChosen;
    /** Where the main panel is on screen (the menu origin topPos is this minus the view's panel offset). */
    private int panelX;
    private int panelY;
    private int mainHeight;
    private int filterRows;
    private boolean advanced;
    private NumberButton priorityButton;
    private NumberButton maxStockButton;
    /** Paused state the Max stock button was last labelled for (null = not yet). */
    private Boolean stockPausedShown;
    /** Drawer choice rows (step 46): Send to, Send back (pull) and Redstone (insert, step 40). */
    private final FlowKey[] sendToKeys = new FlowKey[3];
    private final FlowKey[] sendBackKeys = new FlowKey[2];
    private final FlowKey[] insertRedstoneKeys = new FlowKey[PipeDataBlockEntity.INSERT_REDSTONE_MODES];
    private FlowKey clearFilterKey;
    private int clearLabelWidth;
    private int confirmLabelWidth;
    /** Value text of each drawer number field, set by refreshLabels. */
    private Component batchText = Component.empty();
    private Component keepText = Component.empty();
    private Component fillText = Component.empty();
    private Component priorityText = Component.empty();
    private Component stockText = Component.empty();
    private boolean keepWarn;
    private boolean fillWarn;
    private boolean stockWarn;
    private NumberButton batchButton;
    private NumberButton keepButton;
    private NumberButton fillButton;
    private FlowKey filterModeKey;
    private FlowKey sideKey;
    private FlowKey advancedKey;
    private final FlowKey[] pageKeys = new FlowKey[2];
    /** Type-a-number box, shown over a number button while editing (Ctrl/Cmd+click the button). */
    private EditBox numberEditor;
    private int editingSetting = -1;
    private NumberButton editingButton;
    /**
     * Set by a Ctrl/Cmd+click on a number button; the box opens once that click has finished, because Minecraft moves
     * keyboard focus to the clicked button after its click handler returns (which stole focus from the box before).
     */
    private int pendingEditSetting = -1;
    private NumberButton pendingEditButton;
    /** Paused state the Keep button was last labelled for (null = not yet). */
    private Boolean keepPausedShown;
    /** Paused state the Fill button was last labelled for (null = not yet). */
    private Boolean fillPausedShown;
    /**
     * Number settings just changed here but maybe not confirmed by the server yet: show and build on these for a
     * second, so fast clicking doesn't lose steps while packets are on their way. Indexed by setting id.
     */
    private final int[] localValue = new int[13];
    private final int[] localTicks = new int[13];
    /** Universal pipes (step 33a): the Items / Fluids / Energy tabs, the on/off key, and the resource they were built for. */
    private final FlowTab[] resourceTabs = new FlowTab[PipeKind.RESOURCE_COUNT];
    private FlowKey resourceToggle;
    private int shownResource = -1;
    /** Ticks left in which a second click on Clear filter really clears (0 = not armed). */
    private int clearArmedTicks;
    /**
     * Ticks in a row the pipe's data hasn't been found. Opening an insert side can create the pipe's block entity on
     * the server, and that block update may reach the client a moment after the screen opens; closing at once made the
     * screen vanish at random. The server's own check (stillValid) still closes it for real problems.
     */
    private int missingTicks;
    /** Simple view: something only the Advanced view shows is set (dot on the Advanced key). */
    private boolean hiddenInUse;
    private ItemStack neighborIcon = ItemStack.EMPTY;
    private Component neighborName = Component.empty();

    // ---- step 47: the filter entry open in the drawer
    /** The open entry (0-53), or -1 when the drawer shows the side's settings. */
    private int openEntry = -1;
    /** The drawer's height (always the main panel's since step 47b). */
    private int drawerHeight;
    private FlowKey entryBackKey;
    private final FlowKey[] matchKeys = new FlowKey[MODE_ORDER.length];
    private final TagRow[] tagRows = new TagRow[TAG_ROWS];
    private EditBox tagSearch;
    private String tagSearchText = "";
    private FlowKey tagPrevKey;
    private FlowKey tagNextKey;
    private final NumberButton[] entryFields = new NumberButton[3];
    private final FlowKey[] entryResetKeys = new FlowKey[3];
    private final Component[] entryText = {Component.empty(), Component.empty(), Component.empty()};
    private final int[] entryLocalValue = new int[3];
    private final int[] entryLocalTicks = new int[3];
    private List<String> entryOwnTags = List.of();
    private List<String> allTags = List.of();
    private List<String> shownTags = List.of();
    private int tagPage;
    /** True while the tag list is shown: the entry matches a tag, or Tag was clicked and one is being picked. */
    private boolean pickingTag;
    private Component entryStatus = Component.empty();
    private int entryStatusTicks;

    public PullSideScreen(PullSideMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.pos = menu.pos();
        this.side = menu.side();
        this.insert = menu.isInsert();
    }

    private int sideColor() {
        return insert ? FlowGui.INSERT : FlowGui.PULL;
    }

    private int sideInk() {
        return insert ? FlowGui.INSERT_INK : FlowGui.PULL_INK;
    }

    /** Step 47c: a chosen key (or the mode tag) is filled with this soft tint of the side color, text in sideInk. */
    private int sideTint() {
        return insert ? FlowGui.INSERT_TINT : FlowGui.PULL_TINT;
    }

    @Override
    protected void init() {
        if (!viewChosen) {
            viewChosen = true;
            PipeDataBlockEntity stored = data();
            int view = stored == null ? PipeDataBlockEntity.VIEW_DEFAULT : stored.viewMode();
            boolean simple = view == PipeDataBlockEntity.VIEW_SIMPLE
                    || (view == PipeDataBlockEntity.VIEW_DEFAULT && !DaisychainClientConfig.defaultAdvanced());
            menu.setView(simple);
            sendMenuButton(simple ? PullSideMenu.BUTTON_VIEW_SIMPLE : PullSideMenu.BUTTON_VIEW_ADVANCED);
        }
        advanced = !menu.isSimpleView();
        boolean hasFilter = menu.hasFilter();
        filterRows = !hasFilter ? 0 : advanced ? 3 : 1;
        mainHeight = filterRows * PITCH + 132;
        if (!hasFilter) {
            openEntry = -1;
        }
        boolean entryMode = openEntry >= 0;
        drawerHeight = mainHeight;
        int panelOffset = !hasFilter ? PullSideMenu.PANEL_OFFSET_NO_FILTER
                : advanced ? PullSideMenu.PANEL_OFFSET_ADVANCED : PullSideMenu.PANEL_OFFSET_SIMPLE;
        imageWidth = MAIN_WIDTH + (advanced || entryMode ? DRAWER_GAP + DRAWER_WIDTH : 0);
        imageHeight = mainHeight;
        super.init();
        if (!advanced && entryMode) {
            // Simple view with an entry open: the main panel stays where Simple puts it and the drawer opens to its
            // right, moved left only as far as the window needs.
            leftPos = Math.min((width - MAIN_WIDTH) / 2, width - imageWidth - 2);
        }
        leftPos = Math.max(leftPos, 64); // room for the tabs (and the universal pipe's resource tabs) on the left
        panelX = leftPos;
        panelY = topPos;
        topPos = panelY - panelOffset; // the menu's slot positions are relative to this
        hiddenInUse = false;

        // Tabs: this side (here) and all sides.
        addRenderableWidget(new FlowTab(panelX, panelY + 8, FlowGui.FACE, true,
                Component.translatable("screen.daisychain.tab.this_side"), () -> { }));
        addRenderableWidget(new FlowTab(panelX, panelY + 34, FlowGui.CUBE, false,
                Component.translatable("screen.daisychain.tab.all_sides"), this::openAllSides))
                .setTooltip(Tooltip.create(Component.translatable("screen.daisychain.tab.all_sides")));
        // Universal pipes (step 33a, restyled in step 49): one tab per resource (20 tall, 22 apart) below the two
        // tabs, with a red slash when that resource is off on this side; the on/off (power) key is in the control row.
        shownResource = menu.resource();
        java.util.Arrays.fill(resourceTabs, null);
        resourceToggle = null;
        if (menu.hasResourceTabs()) {
            String[][] icons = {FlowGui.ITEMS, FlowGui.FLUIDS, FlowGui.ENERGY};
            for (int r = 0; r < PipeKind.RESOURCE_COUNT; r++) {
                int resource = r;
                resourceTabs[r] = addRenderableWidget(new FlowTab(panelX, panelY + 64 + r * 22, 20, icons[r],
                        r == menu.resource(), resourceName(r), () -> showResource(resource),
                        () -> resourceOff(resource)));
            }
        }

        // Control row, on the slot columns: (0 upgrade slot) (3-4 pages) 6 filter mode, 7 redstone / priority, 8 Advanced.
        int rowY = controlRowY();
        int sideColor = sideColor();
        java.util.Arrays.fill(pageKeys, null);
        if (advanced && hasFilter) {
            for (int p = 0; p < 2; p++) {
                int page = p;
                pageKeys[p] = addRenderableWidget(FlowKey.text(column(3 + p), rowY, 18, 18,
                        () -> FlowGui.styled(Integer.toString(page + 1)), () -> menu.page() == page, sideTint(),
                        sideInk(), () -> showPage(page)).segmented());
            }
        }
        filterModeKey = addRenderableWidget(FlowKey.icon(column(6), rowY,
                () -> filterDenies() ? FlowGui.BAN : FlowGui.CHECK,
                () -> filterDenies() ? FlowGui.WARN : FlowGui.GREEN, () -> false, () -> false, 0,
                () -> cycle(filterSetting(), 2, 1), () -> cycle(filterSetting(), 2, -1)));
        filterModeKey.visible = hasFilter;
        if (menu.hasResourceTabs()) {
            // Next to the filter mode key; on panels without a filter (energy) it takes the filter key's column.
            int offSetting = insert ? PipeDataBlockEntity.SETTING_INSERT_OFF : PipeDataBlockEntity.SETTING_PULL_OFF;
            Runnable flip = () -> {
                PipeDataBlockEntity data = data();
                if (data != null) {
                    sendNumber(offSetting, numberValue(data, offSetting) == 0 ? 1 : 0);
                }
            };
            int shown = menu.resource();
            resourceToggle = addRenderableWidget(FlowKey.icon(column(hasFilter ? 5 : 6), rowY, () -> FlowGui.POWER,
                    () -> resourceOff(shown) ? FlowGui.WARN : FlowGui.GREEN, () -> false, () -> false, 0, flip, flip));
        }
        if (insert) {
            sideKey = addRenderableWidget(FlowKey.icon(column(7), rowY, this::priorityIcon, () -> FlowGui.TEXT,
                    () -> false, () -> !isPriorityLevel(currentPriority()), sideColor,
                    () -> stepPriority(1), () -> stepPriority(-1)));
        } else {
            int redstoneCount = RedstoneMode.values().length;
            sideKey = addRenderableWidget(FlowKey.icon(column(7), rowY, this::redstoneIcon, () -> FlowGui.WARN,
                    () -> false, () -> false, 0,
                    () -> cycle(PipeDataBlockEntity.SETTING_REDSTONE, redstoneCount, 1),
                    () -> cycle(PipeDataBlockEntity.SETTING_REDSTONE, redstoneCount, -1)));
        }
        advancedKey = addRenderableWidget(FlowKey.icon(column(8), rowY, () -> FlowGui.SLIDERS, () -> FlowGui.DIM,
                () -> advanced, () -> !advanced && hiddenInUse, sideColor, this::toggleAdvanced, null));

        // Drawer (Advanced, spec 5.2): rows 13 tall, a dim label at x 7 (drawn in renderBg) and the controls
        // right-aligned to x 143.
        int dx = drawerX();
        int y = panelY;
        boolean amounts = menu.hasAmounts();
        boolean settingsDrawer = advanced && !entryMode;
        boolean pullRows = settingsDrawer && !insert;
        boolean insertRows = settingsDrawer && insert;
        String[] sendToLabels = {"near", "far", "robin"};
        Distribution[] sendToValues = {Distribution.NEAREST, Distribution.FARTHEST, Distribution.ROUND_ROBIN};
        choiceRow(sendToKeys, y + 5, i -> FlowGui.styled(Component.translatable("screen.daisychain.choice." + sendToLabels[i])),
                PipeDataBlockEntity.SETTING_DISTRIBUTION, i -> sendToValues[i].ordinal(),
                i -> Component.translatable("screen.daisychain.distribution",
                        Component.translatable("screen.daisychain.distribution." + sendToValues[i].getSerializedName())),
                pullRows);
        choiceRow(sendBackKeys, y + 22, i -> FlowGui.styled(Component.translatable(i == 0 ? "screen.daisychain.off"
                        : "screen.daisychain.on")), PipeDataBlockEntity.SETTING_SELF_FEED, i -> i,
                i -> Component.translatable("screen.daisychain.self_feed",
                        Component.translatable(i == 0 ? "screen.daisychain.off" : "screen.daisychain.on")),
                pullRows);
        String[] redstoneLabels = {"always", "on", "off"};
        // Insert drawer (step 47b): the choice row first, a divider, then the numbers, like the pull drawer.
        choiceRow(insertRedstoneKeys, y + 5, i -> FlowGui.styled(Component.translatable(
                        "screen.daisychain.choice.redstone_" + redstoneLabels[i])),
                PipeDataBlockEntity.SETTING_INSERT_REDSTONE, i -> i,
                i -> Component.translatable("screen.daisychain.redstone",
                        Component.translatable("screen.daisychain.redstone." + RedstoneMode.byId(i).getSerializedName())),
                insertRows);
        int fieldX = dx + DRAWER_RIGHT - FIELD_WIDTH;
        batchButton = addRenderableWidget(new NumberButton(fieldX, y + 49, FIELD_WIDTH, ROW_HEIGHT,
                (direction, big, jump) -> adjustNumber(PipeDataBlockEntity.SETTING_BATCH, direction, big, jump),
                () -> requestEditing(PipeDataBlockEntity.SETTING_BATCH, batchButton))
                .flat(() -> batchText, () -> FlowGui.TEXT, () -> false));
        keepButton = addRenderableWidget(new NumberButton(fieldX, y + 66, FIELD_WIDTH, ROW_HEIGHT,
                (direction, big, jump) -> adjustNumber(PipeDataBlockEntity.SETTING_KEEP, direction, big, jump),
                () -> requestEditing(PipeDataBlockEntity.SETTING_KEEP, keepButton))
                .flat(() -> keepText, () -> offColor(PipeDataBlockEntity.SETTING_KEEP), () -> keepWarn));
        fillButton = addRenderableWidget(new NumberButton(fieldX, y + 83, FIELD_WIDTH, ROW_HEIGHT,
                (direction, big, jump) -> adjustNumber(PipeDataBlockEntity.SETTING_FILL, direction, big, jump),
                () -> requestEditing(PipeDataBlockEntity.SETTING_FILL, fillButton))
                .flat(() -> fillText, () -> offColor(PipeDataBlockEntity.SETTING_FILL), () -> fillWarn));
        priorityButton = addRenderableWidget(new NumberButton(fieldX, y + 32, FIELD_WIDTH, ROW_HEIGHT,
                (direction, big, jump) -> adjustNumber(PipeDataBlockEntity.SETTING_PRIORITY, direction, big, jump),
                () -> requestEditing(PipeDataBlockEntity.SETTING_PRIORITY, priorityButton))
                .flat(() -> priorityText, () -> FlowGui.TEXT, () -> false));
        maxStockButton = addRenderableWidget(new NumberButton(fieldX, y + 49, FIELD_WIDTH, ROW_HEIGHT,
                (direction, big, jump) -> adjustNumber(PipeDataBlockEntity.SETTING_MAX_STOCK, direction, big, jump),
                () -> requestEditing(PipeDataBlockEntity.SETTING_MAX_STOCK, maxStockButton))
                .flat(() -> stockText, () -> offColor(PipeDataBlockEntity.SETTING_MAX_STOCK), () -> stockWarn));
        // Clear filter: bottom left, its bottom edge level with the hotbar's (spec). As wide as the label it shows
        // (refreshLabels resizes it when it switches to "Click again").
        Component clearLabel = FlowGui.styled(Component.translatable("screen.daisychain.clear_filter"));
        Component confirmLabel = FlowGui.styled(Component.translatable("screen.daisychain.clear_filter_confirm"));
        clearLabelWidth = FlowGui.inkWidth(font, clearLabel) + 8;
        confirmLabelWidth = FlowGui.inkWidth(font, confirmLabel) + 8;
        clearFilterKey = addRenderableWidget(FlowKey.text(dx + DRAWER_PAD, y + mainHeight - 20, clearLabelWidth, ROW_HEIGHT,
                () -> clearArmedTicks > 0 ? confirmLabel : clearLabel, () -> clearArmedTicks > 0, FlowGui.WARN_TINT,
                FlowGui.WARN_TEXT, this::clearFilter));
        stockPausedShown = null;
        batchButton.visible = pullRows && amounts;
        keepButton.visible = pullRows && amounts;
        fillButton.visible = pullRows && amounts;
        priorityButton.visible = insertRows;
        maxStockButton.visible = insertRows && amounts;
        clearFilterKey.visible = settingsDrawer && hasFilter;
        if (entryMode) {
            initEntry();
        } else {
            entryBackKey = null;
            tagSearch = null;
        }

        numberEditor = addRenderableWidget(new EditBox(font, fieldX, y, FIELD_WIDTH, 9, Component.empty()));
        numberEditor.setBordered(false);
        numberEditor.setMaxLength(64);
        // Only what a number or simple math needs: digits, + - * / ( ) and spaces.
        numberEditor.setFilter(text -> text.chars().allMatch(c -> "0123456789+-*/() ".indexOf(c) >= 0));
        numberEditor.visible = false;
        numberEditor.setResponder(text -> numberEditor.setTextColor(0xE0E0E0));
        editingSetting = -1;
        editingButton = null;
        pendingEditSetting = -1;
        pendingEditButton = null;
        keepPausedShown = null;
        fillPausedShown = null;
        refreshLabels();
        refreshEntry();
    }

    /** Screen x of the drawer panel's left edge. */
    private int drawerX() {
        return panelX + MAIN_WIDTH + DRAWER_GAP;
    }

    /**
     * One row of choice keys (spec 4, text buttons): each as wide as its text + 8, 1 px apart and right-aligned to the
     * drawer's x 143. The chosen one is filled with the side color. Clicking a key sets that value.
     */
    private void choiceRow(FlowKey[] keys, int y, java.util.function.IntFunction<Component> label, int setting,
                           java.util.function.IntUnaryOperator value, java.util.function.IntFunction<Component> tooltip,
                           boolean visible) {
        int right = drawerX() + DRAWER_RIGHT;
        int x = right - (keys.length - 1); // 1 px gaps between the keys (spec 4, text button)
        for (int i = 0; i < keys.length; i++) {
            x -= FlowGui.inkWidth(font, label.apply(i)) + 8;
        }
        for (int i = 0; i < keys.length; i++) {
            int index = i;
            Component text = label.apply(i);
            int width = FlowGui.inkWidth(font, text) + 8;
            int v = value.applyAsInt(i);
            keys[i] = addRenderableWidget(FlowKey.text(x, y, width, ROW_HEIGHT, () -> text,
                    () -> currentSetting(setting) == v, sideTint(), sideInk(), () -> selectSetting(setting, v)));
            keys[i].setTooltip(Tooltip.create(tooltip.apply(index)));
            keys[i].visible = visible;
            x += width + 1;
        }
    }

    private int currentSetting(int setting) {
        PipeDataBlockEntity data = data();
        return data == null ? -1 : numberValue(data, setting);
    }

    private void selectSetting(int setting, int value) {
        PipeDataBlockEntity data = data();
        if (data != null && numberValue(data, setting) != value) {
            sendNumber(setting, value);
        }
    }

    /** "Off" (0) is drawn dim, a set amount in the normal text color. */
    private int offColor(int setting) {
        return currentSetting(setting) == 0 ? FlowGui.DIM : FlowGui.TEXT;
    }

    /**
     * A drawer field's value: thousands separators and the unit (mB, FE); longer than 9 characters switches to the
     * short form ("2.1G FE", spec 4). Returns the text in the GUI font.
     */
    private Component fieldAmount(int value) {
        String number = String.format(java.util.Locale.ROOT, "%,d", value);
        if (number.length() > 9) {
            number = shortNumber(value);
        }
        String unit = switch (menu.resourceKind()) {
            case FLUID -> " mB";
            case ENERGY -> " FE";
            default -> "";
        };
        return FlowGui.styled(number + unit);
    }

    /** The full value for a tooltip, when the field shows the short form. */
    private boolean isShortened(int value) {
        return String.format(java.util.Locale.ROOT, "%,d", value).length() > 9;
    }

    /**
     * Gives a column of fields one shared width (at least 60, more if a value needs it), right-aligned to screen x
     * right (the drawer's x 143, or 129 for a filter entry's amounts). Step 47b: a field never grows past the end of
     * the longest label in its column + 6 px (labelKeys), so values can't cover their labels.
     */
    private void layoutFields(int right, String[] labelKeys, Component[] texts, NumberButton... fields) {
        int width = FIELD_WIDTH;
        for (Component text : texts) {
            width = Math.max(width, FlowGui.inkWidth(font, text) + 10);
        }
        int labelEnd = 0;
        for (String key : labelKeys) {
            labelEnd = Math.max(labelEnd, FlowGui.inkWidth(font, Component.translatable(key)));
        }
        width = Math.min(width, right - (drawerX() + DRAWER_PAD + labelEnd + 6));
        int x = right - width;
        for (NumberButton field : fields) {
            if (field != null && field != editingButton) {
                field.setWidth(width);
                field.setX(x);
            }
        }
    }

    /** Field tooltip: the paused warning if any, else (with hints on) what the setting does and the click controls. */
    @Nullable
    private Tooltip fieldTooltip(@Nullable Component paused, String helpKeyBase, boolean priority, @Nullable Component full) {
        MutableComponent text = Component.empty();
        if (full != null) {
            text.append(full);
        }
        if (paused != null) {
            if (full != null) {
                text.append("\n");
            }
            text.append(paused.copy().withStyle(ChatFormatting.RED));
            return Tooltip.create(text);
        }
        if (DaisychainClientConfig.showHints()) {
            if (full != null) {
                text.append("\n");
            }
            String modifier = Minecraft.ON_OSX ? "Cmd" : "Ctrl";
            text.append(Component.translatable(helpKey(helpKeyBase)))
                    .append("\n\n")
                    .append(Component.translatable(priority ? "screen.daisychain.priority_number_help"
                            : helpKey("screen.daisychain.number_help"), modifier).withStyle(ChatFormatting.DARK_GRAY));
            return Tooltip.create(text);
        }
        return full == null ? null : Tooltip.create(text);
    }

    /** Screen x of slot column c in the main panel. */
    private int column(int c) {
        return panelX + 7 + c * PITCH;
    }

    /** Screen y of the control row: below the filter rows (or right below the header when there is no filter). */
    private int controlRowY() {
        return panelY + 25 + filterRows * PITCH;
    }

    private void sendMenuButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    /** The Advanced key: switches the view, stores it on the pipe for everyone, and lays the screen out again. */
    /**
     * The Advanced key (and, step 47g, the entry drawer's Advanced key in Simple view): switches the view, stores it on
     * the pipe for everyone, and lays the screen out again. An open filter entry stays open, so going to Advanced shows
     * its amounts right away (on the filter page that holds it).
     */
    private void toggleAdvanced() {
        finishEditingFirst();
        boolean simple = !menu.isSimpleView();
        menu.setView(simple);
        sendMenuButton(simple ? PullSideMenu.BUTTON_STORE_SIMPLE : PullSideMenu.BUTTON_STORE_ADVANCED);
        if (!simple && openEntry >= 0) {
            showPage(openEntry / PullSideMenu.PAGE_SIZE);
        } else if (simple && openEntry >= PullSideMenu.SIMPLE_SIZE) {
            openEntry = -1; // Simple view only shows entries 1-9
        }
        rebuildWidgets();
    }

    private void showPage(int page) {
        if (menu.page() != page) {
            menu.setPage(page);
            sendMenuButton(PullSideMenu.BUTTON_PAGE + page);
        }
    }

    private int filterSetting() {
        return insert ? PipeDataBlockEntity.SETTING_INSERT_DENY : PipeDataBlockEntity.SETTING_FILTER_DENY;
    }

    private boolean filterDenies() {
        PipeDataBlockEntity data = data();
        return data != null && data.getSetting(side, filterSetting(), menu.resource()) == 1;
    }

    private String[] redstoneIcon() {
        PipeDataBlockEntity data = data();
        RedstoneMode mode = data == null ? RedstoneMode.ALWAYS : data.redstoneMode(side);
        return switch (mode) {
            case ON_WITH_SIGNAL -> FlowGui.RS_ON;
            case OFF_WITH_SIGNAL -> FlowGui.RS_OFF;
            case PULSE -> FlowGui.RS_PULSE;
            default -> FlowGui.RS_ALWAYS;
        };
    }

    private int currentPriority() {
        PipeDataBlockEntity data = data();
        return data == null ? 0 : numberValue(data, PipeDataBlockEntity.SETTING_PRIORITY);
    }

    private static boolean isPriorityLevel(int value) {
        return value == -10 || value == 0 || value == 10;
    }

    /** High / Normal / Low; any other number shows Normal with a dot (spec). */
    private String[] priorityIcon() {
        int value = currentPriority();
        return value == 10 ? FlowGui.PR_HIGH : value == -10 ? FlowGui.PR_LOW : FlowGui.PR_NORM;
    }

    /** Low -> Normal -> High -> Low (right-click goes back). A number set in Advanced counts as Normal here. */
    private void stepPriority(int step) {
        int value = currentPriority();
        int index = value == -10 ? 0 : value == 10 ? 2 : 1;
        sendNumber(PipeDataBlockEntity.SETTING_PRIORITY, PRIORITY_LEVELS[Math.floorMod(index + step, 3)]);
    }

    /** Closes this menu on the server and opens the All sides layout, which can come back to this side by its tab. */
    private void openAllSides() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        // Close the menu by hand instead of player.closeContainer(): that goes through "no screen" for a moment, which
        // grabs the mouse and snaps the cursor to the middle of the window. Here the new screen replaces this one
        // directly, so the cursor stays where it is. (Same steps as LocalPlayer.clientSideCloseContainer otherwise.)
        minecraft.player.connection.send(new ServerboundContainerClosePacket(menu.containerId));
        minecraft.player.containerMenu = minecraft.player.inventoryMenu;
        minecraft.setScreen(new PipeSidesScreen(pos, side));
    }

    @Nullable
    private PipeDataBlockEntity data() {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return null;
        }
        BlockState state = minecraft.level.getBlockState(pos);
        boolean rightKind = insert ? FlowPipeBlock.isInsertSide(minecraft.level, pos, state, side)
                : FlowPipeBlock.isPullSide(minecraft.level, pos, state, side);
        if (!rightKind || minecraft.player.distanceToSqr(Vec3.atCenterOf(pos)) > 8.0 * 8.0) {
            return null;
        }
        return FlowPipeBlock.getData(minecraft.level, pos, state);
    }

    private void cycle(int setting, int valueCount, int step) {
        PipeDataBlockEntity data = data();
        if (data == null) {
            return;
        }
        int next = Math.floorMod(data.getSetting(side, setting, menu.resource()) + step, valueCount);
        PacketDistributor.sendToServer(new UpdatePullSettingPayload(pos,
                (byte) side.get3DDataValue(), (byte) setting, next, (byte) menu.resource()));
    }

    /** The value to show and build on: a just-clicked local value for a second, else the server's. */
    private int numberValue(PipeDataBlockEntity data, int setting) {
        return localTicks[setting] > 0 ? localValue[setting] : data.getSetting(side, setting, menu.resource());
    }

    /** For the filter entry page: the side's value of a number setting (SETTING_BATCH/KEEP/FILL), or 0 if gone. */
    int sideNumber(int setting) {
        PipeDataBlockEntity data = data();
        return data == null ? 0 : data.getSetting(side, setting, menu.resource());
    }

    /** For the filter entry page: the most a batch can be on this side right now (items, or mB on fluid pipes). */
    int sideTierAmount() {
        PipeDataBlockEntity data = data();
        return data == null ? menu.resourceKind().maxBatch() : tierAmount(data);
    }

    /** For the filter entry page: per-entry numbers only apply in Allow mode. */
    boolean sideDenies() {
        PipeDataBlockEntity data = data();
        return data != null && (insert ? data.insertFilterDenies(side, menu.resource()) : data.filterDenies(side, menu.resource()));
    }

    /** The upgrade's amount per transfer for this side (items, or mB on fluid pipes): the most a batch can be. */
    private int tierAmount(PipeDataBlockEntity data) {
        PipeKind kind = menu.resourceKind();
        // Step 84: an energy pipe's speed comes from its tier; its own tier is the most any route from it carries.
        int level = data.getBlockState().getBlock() instanceof EnergyPipeBlock energy ? energy.tier()
                : data.upgradeLevel(side);
        return UpgradeSpeeds.available() ? UpgradeSpeeds.amount(kind, level) : kind.maxBatch();
    }

    /** A number as shown on this pipe: "250 mB" on fluid pipes, plain on item pipes. */
    Component amount(int value) {
        return switch (menu.resourceKind()) {
            case FLUID -> Component.translatable("screen.daisychain.amount_mb", value);
            case ENERGY -> Component.translatable("screen.daisychain.amount_fe",
                    String.format(java.util.Locale.ROOT, "%,d", value));
            default -> Component.literal(Integer.toString(value));
        };
    }

    /** 2,500,000 → "2.5M", 16,000,000 → "16M", 2,147,483,647 → "2.1G" (k / M / G, one decimal when it matters). */
    static String shortNumber(int value) {
        if (value < 10_000) {
            return String.format(java.util.Locale.ROOT, "%,d", value);
        }
        String[] units = {"k", "M", "G"};
        double scaled = value;
        int unit = -1;
        while (scaled >= 1000 && unit < units.length - 1) {
            scaled /= 1000;
            unit++;
        }
        String number = scaled >= 100 || scaled == Math.floor(scaled)
                ? Long.toString((long) Math.floor(scaled))
                : String.format(java.util.Locale.ROOT, "%.1f", Math.floor(scaled * 10) / 10);
        if (number.endsWith(".0")) {
            number = number.substring(0, number.length() - 2);
        }
        return number + units[unit];
    }

    /** Off for 0, else the amount (with mB on fluid pipes). */
    Component offOrAmount(int value) {
        return value == 0 ? Component.translatable("screen.daisychain.off") : amount(value);
    }

    /** A help text key, with "_fluid" added on fluid pipes (the fluid text talks about mB). */
    String helpKey(String key) {
        return key + menu.resourceKind().langSuffix();
    }

    /**
     * Batch: 0 means "Max" (follows the upgrade). Going above the upgrade's amount becomes Max; going below 1 stops at 1.
     * Alt+left = Max, Alt+right = 1. Keep in source and Fill destination to: 0 = Off; Alt+left = highest, Alt+right = Off.
     */
    private void adjustNumber(int setting, int direction, boolean big, boolean jump) {
        PipeDataBlockEntity data = data();
        if (data == null) {
            return;
        }
        int current = numberValue(data, setting);
        PipeKind kind = menu.resourceKind();
        int step = big ? kind.bigStep() : kind.smallStep();
        int next;
        if (setting == PipeDataBlockEntity.SETTING_PRIORITY) {
            // Priority: Shift = 10 at a time; Alt jumps to the highest / lowest.
            if (jump) {
                next = direction > 0 ? PipeDataBlockEntity.MAX_PRIORITY : PipeDataBlockEntity.MIN_PRIORITY;
            } else {
                next = Math.max(PipeDataBlockEntity.MIN_PRIORITY,
                        Math.min(PipeDataBlockEntity.MAX_PRIORITY, current + direction * (big ? 10 : 1)));
            }
        } else if (setting == PipeDataBlockEntity.SETTING_BATCH) {
            int max = tierAmount(data);
            int effective = current == 0 ? max : Math.min(current, max);
            if (jump) {
                next = direction > 0 ? 0 : 1;
            } else {
                long wanted = (long) effective + (long) direction * step;
                next = wanted >= max ? 0 : (int) Math.max(Math.min(kind.smallStep(), max), wanted);
            }
        } else {
            if (jump) {
                next = direction > 0 ? kind.maxStock() : 0;
            } else {
                next = (int) Math.max(0L, Math.min(kind.maxStock(), (long) current + (long) direction * step));
            }
        }
        if (next == current) {
            return;
        }
        sendNumber(setting, next);
    }

    private void sendNumber(int setting, int value) {
        localValue[setting] = value;
        localTicks[setting] = 20;
        PacketDistributor.sendToServer(new UpdatePullSettingPayload(pos,
                (byte) side.get3DDataValue(), (byte) setting, value, (byte) menu.resource()));
        refreshLabels();
    }

    // ---------------------------------------------------------------- typing a number

    private void requestEditing(int setting, NumberButton button) {
        pendingEditSetting = setting;
        pendingEditButton = button;
    }

    /** Shows the type-a-number box over the button, filled with the current number and all selected. */
    private void startEditing(int setting, NumberButton button) {
        if (button == null) {
            return;
        }
        int value;
        if (setting >= ENTRY_EDIT) {
            value = entryEditValue(setting - ENTRY_EDIT);
        } else {
            PipeDataBlockEntity data = data();
            if (data == null) {
                return;
            }
            value = numberValue(data, setting);
            if (setting == PipeDataBlockEntity.SETTING_BATCH) {
                int max = tierAmount(data);
                value = value == 0 ? max : Math.min(value, max);
            }
        }
        if (editingSetting >= 0) {
            finishEditing(false);
        }
        editingSetting = setting;
        editingButton = button;
        button.active = false; // the box sits on the field; the field only draws its frame meanwhile
        button.setEditing(true);
        numberEditor.setX(button.getX() + 4);
        numberEditor.setY(button.getY() + 3);
        numberEditor.setWidth(button.getWidth() - 8);
        numberEditor.setValue(Integer.toString(value));
        numberEditor.setTextColor(0xE0E0E0);
        numberEditor.moveCursorToEnd(false);
        numberEditor.setHighlightPos(0);
        numberEditor.visible = true;
        setFocused(numberEditor);
        numberEditor.setFocused(true);
    }

    /**
     * Enter (or clicking elsewhere) applies the typed number; Esc cancels. A typed value that isn't a number keeps
     * the box open with red text. Batch: 0 or anything at/above the upgrade's amount means Max.
     */
    private void finishEditing(boolean apply) {
        if (editingSetting < 0) {
            return;
        }
        if (apply) {
            java.util.OptionalLong typed = NumberExpression.evaluate(numberEditor.getValue());
            PipeDataBlockEntity data = data();
            if (typed.isEmpty()) {
                numberEditor.setTextColor(0xFF5555);
                return;
            }
            if (editingSetting >= ENTRY_EDIT) {
                applyTypedEntry(editingSetting - ENTRY_EDIT, typed.getAsLong());
            } else if (data != null) {
                long value = typed.getAsLong();
                int next;
                if (editingSetting == PipeDataBlockEntity.SETTING_PRIORITY) {
                    next = (int) Math.max(PipeDataBlockEntity.MIN_PRIORITY,
                            Math.min(PipeDataBlockEntity.MAX_PRIORITY, value));
                } else if (editingSetting == PipeDataBlockEntity.SETTING_BATCH) {
                    int max = tierAmount(data);
                    next = value <= 0 || value >= max ? 0 : (int) value;
                } else {
                    next = (int) Math.max(0, Math.min(menu.resourceKind().maxStock(), value));
                }
                if (next != numberValue(data, editingSetting)) {
                    sendNumber(editingSetting, next);
                }
            }
        }
        numberEditor.visible = false;
        numberEditor.setFocused(false);
        if (editingButton != null) {
            editingButton.active = true;
            editingButton.setEditing(false);
        }
        editingSetting = -1;
        editingButton = null;
        setFocused(null);
    }

    /** While typing, keys go to the box (so E, Q and number keys don't close the screen or move items). */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingSetting >= 0) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                finishEditing(true);
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                finishEditing(false);
            } else {
                numberEditor.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        if (searchFocused()) {
            // Typing a tag: Enter uses what's typed, Esc leaves the box, everything else is text (E doesn't close).
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyTag(tagSearch.getValue().trim());
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                unfocusSearch();
            } else {
                tagSearch.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        if (openEntry >= 0 && minecraft != null && (keyCode == GLFW.GLFW_KEY_ESCAPE
                || minecraft.options.keyInventory.matches(keyCode, scanCode))) {
            closeEntry(); // Esc and E go back to the settings first, like the old detail page
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Typed characters go straight to the number box or the tag search while one is open. */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editingSetting >= 0) {
            numberEditor.charTyped(codePoint, modifiers);
            return true;
        }
        if (searchFocused()) {
            tagSearch.charTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private boolean searchFocused() {
        return tagSearch != null && tagSearch.visible && tagSearch.isFocused();
    }

    private void unfocusSearch() {
        if (tagSearch != null && tagSearch.isFocused()) {
            tagSearch.setFocused(false);
            setFocused(null);
        }
    }

    /**
     * First click arms the button for 3 seconds ("Click again to clear"); the second click clears.
     * Uses vanilla's menu-button packet; the server checks the menu is still valid before clearing.
     */
    private void clearFilter() {
        if (clearArmedTicks <= 0) {
            clearArmedTicks = 60;
            refreshLabels();
            return;
        }
        clearArmedTicks = 0;
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, PullSideMenu.BUTTON_CLEAR_FILTER);
        }
        refreshLabels();
    }

    /** Universal pipes: asks the server to show another resource; the screen rebuilds when the answer arrives. */
    private void showResource(int resource) {
        if (finishEditingFirst() && minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, PullSideMenu.BUTTON_SHOW_RESOURCE + resource);
        }
    }

    /** Closes the number box (applying a valid number) before switching tabs. Always true. */
    private boolean finishEditingFirst() {
        if (editingSetting >= 0) {
            finishEditing(true);
            if (editingSetting >= 0) {
                finishEditing(false);
            }
        }
        return true;
    }

    /** True if this resource is switched off on this side (following a just-clicked value for the shown one). */
    private boolean resourceOff(int resource) {
        PipeDataBlockEntity data = data();
        if (data == null) {
            return false;
        }
        if (resource == menu.resource()) {
            int offSetting = insert ? PipeDataBlockEntity.SETTING_INSERT_OFF : PipeDataBlockEntity.SETTING_PULL_OFF;
            return numberValue(data, offSetting) != 0;
        }
        return insert ? !data.acceptsResource(side, resource) : !data.pullsResource(side, resource);
    }

    private static Component resourceName(int resource) {
        return Component.translatable(switch (resource) {
            case PipeKind.FLUIDS -> "screen.daisychain.resource.fluids";
            case PipeKind.ENERGY_RES -> "screen.daisychain.resource.energy";
            default -> "screen.daisychain.resource.items";
        });
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.resource() != shownResource) {
            // The server switched the shown resource (a tab click): rebuild buttons, units and help texts for it. The
            // open entry belonged to the other resource's filter, so it closes.
            java.util.Arrays.fill(localTicks, 0);
            openEntry = -1;
            rebuildWidgets();
            return;
        }
        if (clearArmedTicks > 0) {
            clearArmedTicks--;
        }
        for (int i = 0; i < localTicks.length; i++) {
            if (localTicks[i] > 0) {
                localTicks[i]--;
            }
        }
        // Close if the pipe was broken, the side stopped pulling, or the player walked away (after a short grace
        // period, so a block update that is still on its way doesn't close the screen).
        if (data() == null) {
            if (++missingTicks > 40) {
                onClose();
            }
            return;
        }
        missingTicks = 0;
        refreshLabels();
        tickEntry();
    }

    /** Two-line tooltip: name and state, then a dim how-to line (spec). */
    private static Tooltip keyTooltip(Component state, @Nullable Component hint) {
        MutableComponent text = state.copy();
        if (hint != null) {
            text.append("\n").append(hint.copy().withStyle(ChatFormatting.DARK_GRAY));
        }
        return Tooltip.create(text);
    }

    private void refreshLabels() {
        PipeDataBlockEntity data = data();
        if (data == null) {
            return;
        }
        int r = menu.resource();
        if (resourceToggle != null) {
            boolean off = resourceOff(r);
            Component state = Component.translatable(off ? "screen.daisychain.off_short" : "screen.daisychain.on_short");
            resourceToggle.setTooltip(keyTooltip(Component.translatable(insert ? "screen.daisychain.resource_accept"
                    : "screen.daisychain.resource_pull", resourceName(r), state), DaisychainClientConfig.showHints()
                    ? Component.translatable(insert ? "screen.daisychain.resource_accept_help"
                    : "screen.daisychain.resource_pull_help") : Component.translatable("screen.daisychain.key_hint_toggle")));
            for (int i = 0; i < PipeKind.RESOURCE_COUNT; i++) {
                resourceTabs[i].setTooltip(Tooltip.create(Component.translatable(resourceOff(i)
                        ? "screen.daisychain.resource_tab_off" : "screen.daisychain.resource_tab", resourceName(i))));
            }
        }
        // What the pipe touches on this side (header).
        if (minecraft != null && minecraft.level != null) {
            BlockState neighbor = minecraft.level.getBlockState(pos.relative(side));
            neighborIcon = neighbor.isAir() ? ItemStack.EMPTY : new ItemStack(neighbor.getBlock().asItem());
            neighborName = neighbor.isAir() ? Component.translatable("screen.daisychain.nothing_attached")
                    : neighbor.getBlock().getName();
        }
        // Control row tooltips.
        boolean denies = insert ? data.insertFilterDenies(side, r) : data.filterDenies(side, r);
        filterModeKey.setTooltip(keyTooltip(Component.translatable("screen.daisychain.filter_mode",
                        Component.translatable(denies ? "screen.daisychain.filter_mode.deny" : "screen.daisychain.filter_mode.allow")),
                Component.translatable("screen.daisychain.key_hint_toggle")));
        if (insert) {
            sideKey.setTooltip(keyTooltip(Component.translatable("screen.daisychain.priority",
                            numberValue(data, PipeDataBlockEntity.SETTING_PRIORITY)),
                    Component.translatable("screen.daisychain.priority_key_hint")));
        } else {
            sideKey.setTooltip(keyTooltip(Component.translatable("screen.daisychain.redstone",
                            Component.translatable("screen.daisychain.redstone." + data.redstoneMode(side).getSerializedName())),
                    Component.translatable("screen.daisychain.key_hint_cycle")));
        }
        // Simple view: a dot on the Advanced key when something only Advanced shows is in use (spec).
        hiddenInUse = !advanced && advancedSettingsInUse(data, r);
        advancedKey.setTooltip(keyTooltip(Component.translatable(advanced ? "screen.daisychain.view_advanced"
                        : "screen.daisychain.view_simple"),
                Component.translatable(hiddenInUse ? "screen.daisychain.advanced_in_use" : "screen.daisychain.view_hint")));
        for (int p = 0; p < 2; p++) {
            if (pageKeys[p] != null) {
                pageKeys[p].setTooltip(Tooltip.create(Component.translatable("screen.daisychain.filter_page", p + 1)));
            }
        }

        // Drawer (step 46): field values, warnings, widths and tooltips. The choice keys read their state live.
        String countLimit = UpgradeSpeeds.available() ? Integer.toString(DaisychainConfig.MAX_SLOTS_COUNTED.getAsInt()) : "?";
        int max = tierAmount(data);
        int batch = numberValue(data, PipeDataBlockEntity.SETTING_BATCH);
        batchText = batch == 0 || batch >= max
                ? FlowGui.styled(Component.translatable("screen.daisychain.field.max", maxFieldAmount(max)))
                : fieldAmount(batch);
        int keep = numberValue(data, PipeDataBlockEntity.SETTING_KEEP);
        keepWarn = keep > 0 && menu.isKeepPaused();
        keepText = keep == 0 ? FlowGui.styled(Component.translatable("screen.daisychain.off")) : fieldAmount(keep);
        int fill = numberValue(data, PipeDataBlockEntity.SETTING_FILL);
        fillWarn = fill > 0 && menu.isFillPaused();
        fillText = fill == 0 ? FlowGui.styled(Component.translatable("screen.daisychain.off")) : fieldAmount(fill);
        int priority = numberValue(data, PipeDataBlockEntity.SETTING_PRIORITY);
        priorityText = FlowGui.styled(Integer.toString(priority));
        int stock = numberValue(data, PipeDataBlockEntity.SETTING_MAX_STOCK);
        stockWarn = insert && stock > 0 && menu.isStockPaused();
        stockText = stock == 0 ? FlowGui.styled(Component.translatable("screen.daisychain.off")) : fieldAmount(stock);
        int fieldRight = drawerX() + DRAWER_RIGHT;
        if (insert) {
            layoutFields(fieldRight, new String[] {"screen.daisychain.drawer.priority", "screen.daisychain.drawer.max_stock"},
                    new Component[] {priorityText, stockText}, priorityButton, maxStockButton);
        } else {
            layoutFields(fieldRight, new String[] {"screen.daisychain.drawer.batch", "screen.daisychain.drawer.keep",
                    "screen.daisychain.drawer.fill"}, new Component[] {batchText, keepText, fillText},
                    batchButton, keepButton, fillButton);
        }
        batchButton.setTooltip(fieldTooltip(null, "screen.daisychain.batch_help", false,
                batch != 0 && isShortened(batch) ? amount(batch) : null));
        keepButton.setTooltip(fieldTooltip(keepWarn ? Component.translatable(helpKey("screen.daisychain.keep_paused"),
                countLimit) : null, "screen.daisychain.keep_help", false, isShortened(keep) ? amount(keep) : null));
        fillButton.setTooltip(fieldTooltip(fillWarn ? Component.translatable(helpKey("screen.daisychain.fill_paused"),
                countLimit) : null, "screen.daisychain.fill_help", false, isShortened(fill) ? amount(fill) : null));
        priorityButton.setTooltip(fieldTooltip(null, "screen.daisychain.priority_help", true, null));
        maxStockButton.setTooltip(fieldTooltip(stockWarn ? Component.translatable(
                helpKey("screen.daisychain.max_stock_paused"), countLimit) : null, "screen.daisychain.max_stock_help", false,
                isShortened(stock) ? amount(stock) : null));
        clearFilterKey.active = hasFilterEntries();
        if (!clearFilterKey.active) {
            clearArmedTicks = 0;
        }
        clearFilterKey.setWidth(clearArmedTicks > 0 ? confirmLabelWidth : clearLabelWidth);
    }

    /** The upgrade's amount inside "Max (…)": the short form with its unit ("64", "4,096", "64k mB", "2.1G FE"). */
    private Component maxFieldAmount(int value) {
        String unit = switch (menu.resourceKind()) {
            case FLUID -> " mB";
            case ENERGY -> " FE";
            default -> "";
        };
        return Component.literal(shortNumber(value) + unit);
    }

    /**
     * Anything set that only the Advanced view shows: filter entries 10-54, an entry with its own amounts, or a drawer
     * value off its default.
     */
    private boolean advancedSettingsInUse(PipeDataBlockEntity data, int resource) {
        if (menu.hasFilter()) {
            for (int entry = 0; entry < ItemFilter.SIZE; entry++) {
                ItemStack stack = menu.slots.get(1 + entry).getItem();
                if (!stack.isEmpty() && (entry >= PullSideMenu.SIMPLE_SIZE
                        || stack.has(ModDataComponents.FILTER_NUMBERS.get()))) {
                    return true; // entries 10-54, or an entry with its own amounts (step 47b)
                }
            }
        }
        if (insert) {
            return data.maxStock(side, resource) != 0
                    || data.insertRedstoneMode(side) != RedstoneMode.ALWAYS
                    || !isPriorityLevel(data.priority(side));
        }
        return data.distribution(side) != Distribution.NEAREST || data.allowsSelfFeed(side)
                || data.batch(side, resource) != 0 || data.keepInSource(side, resource) != 0
                || data.fillDestinationTo(side, resource) != 0;
    }

    /** Reads the synced filter slots (the client never has the filter itself). */
    private boolean hasFilterEntries() {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (PullSideMenu.isFilterSlot(i) && menu.slots.get(i).hasItem()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // The empty upgrade slot explains itself (a filled one shows the upgrade's own tooltip plus its rate).
        if (!menu.upgradeParked() && hoveredSlot != null && hoveredSlot.index == 0 && !hoveredSlot.hasItem()
                && menu.getCarried().isEmpty()) {
            graphics.renderComponentTooltip(font, List.of(Component.translatable("screen.daisychain.upgrade_label"),
                    upgradeRate(0).copy().withStyle(ChatFormatting.DARK_GRAY)), mouseX, mouseY);
        } else {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }

    /** "32 items / 5 t" for an upgrade level on this side's resource. */
    private Component upgradeRate(int tier) {
        if (!UpgradeSpeeds.available()) {
            return Component.empty();
        }
        PipeKind kind = menu.resourceKind();
        int rate = UpgradeSpeeds.amount(kind, tier);
        return Component.translatable("screen.daisychain.upgrade_rate" + kind.langSuffix(),
                kind == PipeKind.ENERGY ? shortNumber(rate) : Integer.toString(rate), UpgradeSpeeds.interval(kind, tier));
    }

    /**
     * Left-click a filter entry with an empty hand (or Ctrl/Cmd+click it) to open it in the drawer, in both views.
     * Shift+click is the only way to remove an entry (step 20b rule; step 46 made Simple view follow it too).
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (editingSetting >= 0 && !numberEditor.isMouseOver(mouseX, mouseY)) {
            finishEditing(true);
            if (editingSetting >= 0) {
                finishEditing(false); // not a valid number: give up rather than trap the player in the box
            }
        }
        if (searchFocused() && !tagSearch.isMouseOver(mouseX, mouseY)) {
            unfocusSearch();
        }
        if (button == 0 && !hasShiftDown() && hoveredSlot != null
                && PullSideMenu.isFilterSlot(hoveredSlot.index) && hoveredSlot.hasItem()
                && menu.getCarried().isEmpty()) {
            openEntry(PullSideMenu.filterSlotOf(hoveredSlot.index));
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (pendingEditSetting >= 0) {
            int setting = pendingEditSetting;
            NumberButton target = pendingEditButton;
            pendingEditSetting = -1;
            pendingEditButton = null;
            startEditing(setting, target);
            return true;
        }
        return handled;
    }

    /** Clicks on the panels, the drawer and the tabs aren't "outside" (outside drops the carried item). */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int mouseButton) {
        boolean inMain = mouseX >= panelX - 64 && mouseX < panelX + MAIN_WIDTH && mouseY >= panelY
                && mouseY < panelY + mainHeight;
        boolean inDrawer = (advanced || openEntry >= 0) && mouseX >= panelX + MAIN_WIDTH
                && mouseX < drawerX() + DRAWER_WIDTH && mouseY >= panelY && mouseY < panelY + drawerHeight;
        return !inMain && !inDrawer;
    }

    /** Filter entries also say how they match and how to edit them. */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        if (!menu.upgradeParked() && hoveredSlot != null && hoveredSlot.index == 0) {
            PipeDataBlockEntity data = data();
            if (data != null) {
                lines.add(upgradeRate(data.upgradeLevel(side)).copy().withStyle(ChatFormatting.GRAY));
            }
            return lines;
        }
        if (menu.upgradeParked() && hoveredSlot != null && hoveredSlot.index == 0) {
            // Step 84: on an energy pipe an upgrade never does anything (speed comes from the pipe's tier).
            lines.add(Component.translatable(insert ? "screen.daisychain.upgrade_parked"
                    : "screen.daisychain.upgrade_unused_energy").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        if (hoveredSlot != null && PullSideMenu.isFilterSlot(hoveredSlot.index)) {
            FilterMatch match = stack.get(ModDataComponents.FILTER_MATCH.get());
            MatchMode mode = match == null ? MatchMode.ITEM : match.matchMode();
            Component how = switch (mode) {
                case ITEM -> Component.translatable(menu.isFluid() ? "screen.daisychain.match.fluid"
                        : "screen.daisychain.match.item");
                case DATA -> Component.translatable(menu.isFluid() ? "screen.daisychain.match.fluid_data"
                        : "screen.daisychain.match.data");
                case TAG -> Component.literal("#" + match.value());
                case MOD -> Component.translatable("tooltip.daisychain.filter.mod", match.value());
            };
            lines.add(Component.translatable("tooltip.daisychain.filter.match", how).withStyle(ChatFormatting.AQUA));
            FilterNumbers numbers = stack.get(ModDataComponents.FILTER_NUMBERS.get());
            if (numbers != null) {
                if (numbers.batch() != ItemFilter.UNSET) {
                    lines.add(Component.translatable("screen.daisychain.batch", numbers.batch() == 0
                            ? Component.translatable("screen.daisychain.max") : amount(numbers.batch()))
                            .withStyle(ChatFormatting.AQUA));
                }
                if (numbers.keep() != ItemFilter.UNSET) {
                    lines.add(Component.translatable("screen.daisychain.keep", offOrAmount(numbers.keep()))
                            .withStyle(ChatFormatting.AQUA));
                }
                if (numbers.fill() != ItemFilter.UNSET) {
                    // On insert filters the FILL number is the entry's Max stock.
                    lines.add(Component.translatable(insert ? "screen.daisychain.max_stock" : "screen.daisychain.fill",
                            offOrAmount(numbers.fill())).withStyle(ChatFormatting.AQUA));
                }
            }
            lines.add(Component.translatable("tooltip.daisychain.filter.edit").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.translatable("tooltip.daisychain.filter.remove").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** Fluid filter entries are drawn as the fluid itself instead of the placeholder item. */
    @Override
    protected void renderSlotContents(GuiGraphics graphics, ItemStack stack, Slot slot, @Nullable String countString) {
        // Step 68: a parked upgrade (insert side) does nothing here, so it's drawn with a faded copy of its icon
        // (textures/gui/parked/: half the colour, darker; step 69) and a pause badge.
        if (menu.upgradeParked() && slot.index == 0 && stack.getItem() instanceof UpgradeItem upgrade) {
            int x = slot.x;
            int y = slot.y;
            graphics.blit(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID,
                    "textures/gui/parked/" + upgrade.tier().id() + "_upgrade.png"), x, y, 0, 0, 16, 16, 16, 16);
            // Step 69 (zerobit): a pause badge bottom right, so a parked upgrade can't be mistaken for an iron one.
            graphics.fill(x + 10, y + 9, x + 15, y + 16, PARKED_BADGE);
            graphics.fill(x + 9, y + 10, x + 16, y + 15, PARKED_BADGE);
            graphics.fill(x + 11, y + 11, x + 12, y + 14, PARKED_BADGE_BARS);
            graphics.fill(x + 13, y + 11, x + 14, y + 14, PARKED_BADGE_BARS);
            return;
        }
        if (FluidGhost.isGhost(stack)) {
            FluidRender.drawFluid(graphics, FluidGhost.contentOf(stack), slot.x, slot.y);
            return;
        }
        super.renderSlotContents(graphics, stack, slot, countString);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        FlowGui.panel(graphics, panelX, panelY, MAIN_WIDTH, mainHeight);
        if (openEntry >= 0) {
            FlowGui.panel(graphics, drawerX(), panelY, DRAWER_WIDTH, drawerHeight);
            drawEntry(graphics);
        } else if (advanced) {
            FlowGui.panel(graphics, drawerX(), panelY, DRAWER_WIDTH, mainHeight);
            drawDrawerLabels(graphics);
        }
        // Header (caps on y 8): mode tag, the block on this side, its name.
        Component tag = FlowGui.styled(Component.translatable(insert ? "screen.daisychain.mode_tag.insert"
                : "screen.daisychain.mode_tag.pull"));
        int tagWidth = FlowGui.inkWidth(font, tag) + 8;
        FlowGui.round(graphics, panelX + 7, panelY + 6, panelX + 7 + tagWidth, panelY + 17, sideTint(), 1);
        FlowGui.centered(graphics, font, tag, panelX + 7, tagWidth, panelY + 8, sideInk());
        int iconX = panelX + 7 + tagWidth + 5;
        if (!neighborIcon.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(iconX, panelY + 6, 0);
            graphics.pose().scale(11 / 16.0F, 11 / 16.0F, 1);
            graphics.renderFakeItem(neighborIcon, 0, 0);
            graphics.pose().popPose();
        }
        // Step 74: long block names are cut short with "..." so they stay inside the panel.
        FlowGui.text(graphics, font, fitted(neighborName, panelX + MAIN_WIDTH - 8 - (iconX + 15)), iconX + 15,
                panelY + 8, FlowGui.DIM);

        // Filter slots, tinted by the side's kind.
        int filterEdge = insert ? FlowGui.FILTER_EDGE_INSERT : FlowGui.FILTER_EDGE_PULL;
        if (filterRows > 0) {
            FlowGui.slotGrid(graphics, column(0), panelY + 21, 9, filterRows, filterEdge);
        }
        // Control row: the upgrade slot (pull sides), a faint ring while it's empty.
        int rowY = controlRowY();
        if (!menu.upgradeParked()) {
            FlowGui.slot(graphics, column(0), rowY, FlowGui.SLOT_EDGE);
            if (!menu.slots.get(0).hasItem()) {
                FlowGui.icon(graphics, column(0) + 5, rowY + 5, FlowGui.RING, FlowGui.FAINT);
            }
        } else if (menu.slots.get(0).hasItem()) {
            // Step 67: a parked upgrade (kept from when this side pulled). Dimmed in render(), above the item.
            FlowGui.slot(graphics, column(0), rowY, FlowGui.SLOT_EDGE);
        }
        // Divider, then the inventory and the hotbar.
        // No divider since step 47e: the inventory grid's 2 px edge already separates it from the control row.
        int inventoryY = rowY + 24;
        FlowGui.slotGrid(graphics, column(0), inventoryY, 9, 3, FlowGui.SLOT_EDGE);
        FlowGui.slotGrid(graphics, column(0), inventoryY + 58, 9, 1, FlowGui.SLOT_EDGE);
        drawEntrySelection(graphics);
        if (!menu.hasFilter() && advanced) {
            // Nothing else to show there: the drawer has the settings.
            return;
        }
    }

    /**
     * The drawer's dim row labels (cap-top = row + 3) and the divider between the choices and the numbers. Pull:
     * Send to 5, Send back 22, divider 42, Batch 49, Keep 66, Fill to 83. Insert (step 47b): Redstone 5, divider 25,
     * Priority 32, Max stock 49.
     */
    private void drawDrawerLabels(GuiGraphics graphics) {
        int x = drawerX() + DRAWER_PAD;
        int y = panelY;
        boolean amounts = menu.hasAmounts();
        if (insert) {
            drawerLabel(graphics, "screen.daisychain.drawer.redstone", x, y + 5);
            FlowGui.hline(graphics, x, y + 25, DRAWER_RIGHT - DRAWER_PAD, FlowGui.LINE);
            drawerLabel(graphics, "screen.daisychain.drawer.priority", x, y + 32);
            if (amounts) {
                drawerLabel(graphics, "screen.daisychain.drawer.max_stock", x, y + 49);
            }
            return;
        }
        drawerLabel(graphics, "screen.daisychain.drawer.send_to", x, y + 5);
        drawerLabel(graphics, "screen.daisychain.drawer.send_back", x, y + 22);
        if (amounts) {
            FlowGui.hline(graphics, x, y + 42, DRAWER_RIGHT - DRAWER_PAD, FlowGui.LINE);
            drawerLabel(graphics, "screen.daisychain.drawer.batch", x, y + 49);
            drawerLabel(graphics, "screen.daisychain.drawer.keep", x, y + 66);
            drawerLabel(graphics, "screen.daisychain.drawer.fill", x, y + 83);
        }
    }

    private void drawerLabel(GuiGraphics graphics, String key, int x, int rowY) {
        FlowGui.text(graphics, font, FlowGui.styled(Component.translatable(key)), x, rowY + 3, FlowGui.DIM);
    }

    /** No vanilla title or "Inventory" label (spec); just the markers on filter entries. */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawMatchMarkers(graphics);
    }

    /**
     * The open entry's slot: its own 2 px grid lines are drawn in the side color (step 47e; the earlier ring sat
     * outside the lines and cut into the neighboring slots). Drawn with the background, under the items.
     */
    private void drawEntrySelection(GuiGraphics graphics) {
        if (openEntry < 0) {
            return;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!PullSideMenu.isFilterSlot(i) || PullSideMenu.filterSlotOf(i) != openEntry || !slot.isActive()) {
                continue;
            }
            int x0 = leftPos + slot.x - 2;
            int y0 = topPos + slot.y - 2;
            int x1 = leftPos + slot.x + 18;
            int y1 = topPos + slot.y + 18;
            int color = sideColor();
            graphics.fill(x0, y0, x1, y0 + 2, color);
            graphics.fill(x0, y1 - 2, x1, y1, color);
            graphics.fill(x0, y0 + 2, x0 + 2, y1 - 2, color);
            graphics.fill(x1 - 2, y0 + 2, x1, y1 - 2, color);
            return;
        }
    }

    /**
     * Badges on filter entries (step 47f, replacing the D / # / @ letters and the blue "+"): a 7x7 chip in the side
     * tint with a 5x5 glyph. Top left = how it matches (D = item + data, # = tag, M = mod; plain item matches have
     * none); bottom right = the entry has its own amounts. Only on the slots the current view shows; drawn above the
     * item icons.
     */
    private void drawMatchMarkers(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300); // above the item icons
        for (int i = 0; i < menu.slots.size(); i++) {
            if (!PullSideMenu.isFilterSlot(i)) {
                continue;
            }
            Slot slot = menu.slots.get(i);
            if (!slot.isActive() || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            FilterMatch match = stack.get(ModDataComponents.FILTER_MATCH.get());
            if (match != null) {
                String[] glyph = switch (match.matchMode()) {
                    case DATA -> FlowGui.BADGE_DATA;
                    case TAG -> FlowGui.BADGE_TAG;
                    case MOD -> FlowGui.BADGE_MOD;
                    default -> null;
                };
                if (glyph != null) {
                    drawBadge(graphics, slot.x, slot.y, glyph);
                }
            }
            if (stack.has(ModDataComponents.FILTER_NUMBERS.get())) {
                drawBadge(graphics, slot.x + 9, slot.y + 9, FlowGui.BADGE_AMOUNTS);
            }
        }
        graphics.pose().popPose();
    }

    /** A 7x7 badge (corners rounded 1 px) in the side tint with a 5x5 glyph in the side's light ink. */
    private void drawBadge(GuiGraphics graphics, int x, int y, String[] glyph) {
        FlowGui.round(graphics, x, y, x + 7, y + 7, sideTint(), 1);
        FlowGui.icon(graphics, x + 1, y + 1, glyph, sideInk());
    }

    /** Opens an entry in the drawer (another one replaces it). */
    private void openEntry(int entry) {
        if (entry < 0 || entry == openEntry) {
            return;
        }
        finishEditingFirst();
        if (minecraft != null) {
            // The slot click is handled here, before any button, so play the button click ourselves (step 47d).
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
        openEntry = entry;
        tagSearchText = "";
        tagPage = 0;
        entryStatusTicks = 0;
        java.util.Arrays.fill(entryLocalTicks, 0);
        pickingTag = entryMatchMode() == MatchMode.TAG;
        rebuildWidgets();
    }

    /** Back, Done, Esc and E: the drawer shows the side's settings again (or goes away in Simple view). */
    private void closeEntry() {
        if (openEntry < 0) {
            return;
        }
        finishEditingFirst();
        openEntry = -1;
        rebuildWidgets();
    }

    /** Menu slot showing entry e (the Advanced page slot, which always holds it), or -1. */
    private int menuIndexOfEntry(int entry) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (PullSideMenu.isFilterSlot(i) && PullSideMenu.filterSlotOf(i) == entry) {
                return i;
            }
        }
        return -1;
    }

    /** The open entry as the server last synced it (with our display markers), or empty. */
    private ItemStack entryStack() {
        int index = openEntry < 0 ? -1 : menuIndexOfEntry(openEntry);
        return index < 0 ? ItemStack.EMPTY : menu.slots.get(index).getItem();
    }

    /** The entry without our display markers, i.e. the item exactly as it was listed. */
    private static ItemStack plain(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.remove(ModDataComponents.FILTER_MATCH.get());
        copy.remove(ModDataComponents.FILTER_NUMBERS.get());
        return copy;
    }

    private MatchMode entryMatchMode() {
        FilterMatch match = entryStack().get(ModDataComponents.FILTER_MATCH.get());
        return match == null ? MatchMode.ITEM : match.matchMode();
    }

    private String entryMatchValue() {
        FilterMatch match = entryStack().get(ModDataComponents.FILTER_MATCH.get());
        return match == null ? "" : match.value();
    }

    /** The match key shown as chosen: Tag while a tag is being picked, else the entry's mode. */
    private MatchMode shownMatch() {
        return pickingTag ? MatchMode.TAG : entryMatchMode();
    }

    /** Lang key part for a match key: fluid pipes say Fluid instead of Item. */
    private String modeKey(MatchMode mode) {
        if (menu.isFluid() && mode == MatchMode.ITEM) {
            return "fluid";
        }
        if (menu.isFluid() && mode == MatchMode.DATA) {
            return "fluid_data";
        }
        return mode.key();
    }

    /** Builds the entry's widgets (called from init while an entry is open). */
    private void initEntry() {
        int dx = drawerX();
        int y = panelY;
        ItemStack entry = plain(entryStack());
        // Tags: the entry's own (shown first) and every tag of its kind (searched by typing).
        List<String> own = new ArrayList<>();
        List<String> all = new ArrayList<>();
        SimpleFluidContent fluid = FluidGhost.contentOf(entry);
        if (fluid != null) {
            fluid.getFluidHolder().tags().forEach(tag -> own.add(tag.location().toString()));
            BuiltInRegistries.FLUID.getTagNames().forEach(tag -> all.add(tag.location().toString()));
        } else {
            entry.getTags().forEach(tag -> own.add(tag.location().toString()));
            BuiltInRegistries.ITEM.getTagNames().forEach(tag -> all.add(tag.location().toString()));
        }
        Collections.sort(own);
        Collections.sort(all);
        entryOwnTags = own;
        allTags = all;

        // Header: back key at (7,5); the item and its name are drawn in drawEntry.
        entryBackKey = addRenderableWidget(FlowKey.small(dx + DRAWER_PAD, y + 5, 13, 13, () -> FlowGui.BACK,
                () -> FlowGui.TEXT, this::closeEntry));
        entryBackKey.setTooltip(Tooltip.create(Component.translatable("screen.daisychain.entry.back")));
        // Match by: four keys filling x 7-143 at y 25, 1 px apart. Each is its text + 8, and the room left over is
        // shared out evenly (the design scripts' rule), so the row ends exactly at x 143.
        Component[] matchLabels = new Component[MODE_ORDER.length];
        int[] matchWidths = new int[MODE_ORDER.length];
        int used = MODE_ORDER.length - 1;
        for (int i = 0; i < MODE_ORDER.length; i++) {
            matchLabels[i] = FlowGui.styled(Component.translatable("screen.daisychain.entry.match." + modeKey(MODE_ORDER[i])));
            matchWidths[i] = FlowGui.inkWidth(font, matchLabels[i]) + 8;
            used += matchWidths[i];
        }
        int extra = Math.max(0, DRAWER_RIGHT - DRAWER_PAD - used);
        int keyX = dx + DRAWER_PAD;
        for (int i = 0; i < MODE_ORDER.length; i++) {
            MatchMode mode = MODE_ORDER[i];
            Component label = matchLabels[i];
            int keyWidth = matchWidths[i] + extra / MODE_ORDER.length + (i < extra % MODE_ORDER.length ? 1 : 0);
            matchKeys[i] = addRenderableWidget(FlowKey.text(keyX, y + 25, keyWidth, ROW_HEIGHT,
                    () -> label, () -> shownMatch() == mode, sideTint(), sideInk(), () -> chooseMode(mode)));
            keyX += keyWidth + 1;
        }
        // Tag list: three rows, then the search box, the page text and the arrows.
        for (int i = 0; i < TAG_ROWS; i++) {
            tagRows[i] = addRenderableWidget(new TagRow(dx + DRAWER_PAD, y + 42 + i * 14, i));
        }
        tagSearch = addRenderableWidget(new EditBox(font, dx + DRAWER_PAD + 4, y + 87, SEARCH_WIDTH - 8, 9,
                Component.translatable("screen.daisychain.entry.search")));
        tagSearch.setBordered(false);
        tagSearch.setMaxLength(256);
        tagSearch.setHint(FlowGui.styled(Component.translatable("screen.daisychain.entry.search"))
                .withStyle(style -> style.withColor(FlowGui.FAINT & 0xFFFFFF)));
        tagSearch.setValue(tagSearchText);
        tagSearch.setResponder(text -> {
            tagSearchText = text;
            tagPage = 0;
            filterTags();
            refreshEntry();
        });
        tagPrevKey = addRenderableWidget(FlowKey.small(dx + 116, y + 84, 13, 13, () -> FlowGui.ARROW_LEFT,
                () -> FlowGui.TEXT, () -> turnTagPage(-1)));
        tagPrevKey.setTooltip(Tooltip.create(Component.translatable("screen.daisychain.entry.previous_page")));
        tagNextKey = addRenderableWidget(FlowKey.small(dx + 130, y + 84, 13, 13, () -> FlowGui.ARROW_RIGHT,
                () -> FlowGui.TEXT, () -> turnTagPage(1)));
        tagNextKey.setTooltip(Tooltip.create(Component.translatable("screen.daisychain.entry.next_page")));
        filterTags();

        // The entry's own amounts at y 110 / 127 / 144 (insert entries: Max stock only, stored as FILL).
        java.util.Arrays.fill(entryFields, null);
        java.util.Arrays.fill(entryResetKeys, null);
        if (entryAmounts()) {
            int[] rows = insert ? new int[] {ItemFilter.FILL} : new int[] {ItemFilter.BATCH, ItemFilter.KEEP, ItemFilter.FILL};
            for (int r = 0; r < rows.length; r++) {
                int which = rows[r];
                int rowY = y + 110 + r * 17;
                entryFields[which] = addRenderableWidget(new NumberButton(dx + ENTRY_FIELD_RIGHT - FIELD_WIDTH, rowY,
                        FIELD_WIDTH, ROW_HEIGHT,
                        (direction, big, jump) -> adjustEntryNumber(which, direction, big, jump),
                        () -> requestEditing(ENTRY_EDIT + which, entryFields[which]))
                        .flat(() -> entryText[which], () -> entryColor(which), () -> false));
                entryResetKeys[which] = addRenderableWidget(FlowKey.small(dx + ENTRY_FIELD_RIGHT + 1, rowY, 13, 13,
                        () -> FlowGui.CROSS, () -> FlowGui.WARN_TEXT, () -> sendEntryNumber(which, ItemFilter.UNSET)));
                entryResetKeys[which].setTooltip(Tooltip.create(Component.translatable("screen.daisychain.entry_reset")));
            }
        }
        if (!advanced && menu.hasAmounts()) {
            // Simple view (step 47g): amounts live in Advanced; this key goes there with the entry still open. Its dot
            // says the entry already has amounts of its own.
            Component label = FlowGui.styled(Component.translatable("screen.daisychain.entry.open_advanced"));
            int keyWidth = FlowGui.inkWidth(font, label) + 12; // 4 px more so the dot has its own room (step 47h)
            FlowKey openAdvanced = addRenderableWidget(FlowKey.text(dx + DRAWER_RIGHT - keyWidth, y + 110, keyWidth,
                    ROW_HEIGHT, () -> label, () -> false, sideTint(), sideInk(), this::toggleAdvanced)
                    .withDot(this::entryHasOwnAmounts, sideColor()));
            openAdvanced.setTooltip(Tooltip.create(Component.translatable("screen.daisychain.entry.open_advanced_help")));
        }
        Component done = FlowGui.styled(CommonComponents.GUI_DONE);
        addRenderableWidget(FlowKey.text(dx + 99, y + drawerHeight - 20, 44, ROW_HEIGHT, () -> done, () -> true,
                FlowGui.ON, FlowGui.ON_INK, this::closeEntry));
    }

    /** Every tick while an entry is open: close it if it was removed, count down, refresh what's shown. */
    private void tickEntry() {
        if (openEntry < 0) {
            return;
        }
        if (entryStack().isEmpty()) {
            closeEntry(); // removed (Shift+click, Clear filter, another player)
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (entryLocalTicks[i] > 0) {
                entryLocalTicks[i]--;
            }
        }
        if (entryStatusTicks > 0) {
            entryStatusTicks--;
        }
        refreshEntry();
    }

    /** Updates the entry widgets' state, visibility and tooltips. Does nothing while no entry is open. */
    private void refreshEntry() {
        if (openEntry < 0 || entryBackKey == null) {
            return;
        }
        ItemStack entry = plain(entryStack());
        boolean hasData = ItemFilter.hasData(entry);
        matchKeys[1].active = hasData;
        for (int i = 0; i < MODE_ORDER.length; i++) {
            matchKeys[i].setTooltip(Tooltip.create(matchHelp(MODE_ORDER[i], entry, hasData)));
        }
        int pages = tagPages();
        tagPage = Math.min(tagPage, pages - 1);
        for (int i = 0; i < TAG_ROWS; i++) {
            String tag = tagRows[i].tag();
            tagRows[i].visible = pickingTag && tag != null;
            boolean cut = tag != null && font.width(FlowGui.styled("#" + tag)) > tagRows[i].getWidth() - 10;
            tagRows[i].setTooltip(cut ? Tooltip.create(Component.literal("#" + tag)) : null);
        }
        if (!pickingTag) {
            unfocusSearch();
        }
        tagSearch.visible = pickingTag;
        tagPrevKey.visible = pickingTag;
        tagNextKey.visible = pickingTag;
        tagPrevKey.active = tagPage > 0;
        tagNextKey.active = tagPage < pages - 1;
        refreshEntryNumbers();
    }

    /** What a match key does, for its tooltip. */
    private Component matchHelp(MatchMode mode, ItemStack entry, boolean hasData) {
        String kind = menu.isFluid() ? "fluid" : "item";
        return switch (mode) {
            case ITEM -> Component.translatable("screen.daisychain.matches." + kind);
            case DATA -> hasData ? Component.translatable("screen.daisychain.matches." + (menu.isFluid() ? "fluid_data" : "data"))
                    : Component.translatable("screen.daisychain.no_data");
            case TAG -> entryMatchMode() == MatchMode.TAG
                    ? Component.translatable("screen.daisychain.matches.tag", entryMatchValue())
                    : Component.translatable("screen.daisychain.entry.pick_tag");
            case MOD -> Component.translatable("screen.daisychain.matches.mod", ItemFilter.modOf(entry));
        };
    }

    /** One line (wrapped) saying what the entry matches right now, shown where the tag list goes. */
    private Component describeEntry() {
        MatchMode mode = entryMatchMode();
        return switch (mode) {
            case TAG -> Component.translatable("screen.daisychain.matches.tag", entryMatchValue());
            case MOD -> Component.translatable("screen.daisychain.matches.mod", entryMatchValue());
            default -> matchHelp(mode, ItemStack.EMPTY, true);
        };
    }

    private void chooseMode(MatchMode mode) {
        if (mode == MatchMode.TAG) {
            if (!pickingTag) {
                pickingTag = true;
                tagPage = 0;
                filterTags();
            }
            refreshEntry();
            return;
        }
        sendMatch(mode, "");
    }

    private void applyTag(String tag) {
        String id = tag.startsWith("#") ? tag.substring(1) : tag;
        if (Collections.binarySearch(allTags, id) < 0) {
            setEntryStatus("screen.daisychain.entry.unknown_tag");
            return;
        }
        unfocusSearch();
        sendMatch(MatchMode.TAG, id);
    }

    private void sendMatch(MatchMode mode, String value) {
        if (mode == entryMatchMode() && (mode != MatchMode.TAG || value.equals(entryMatchValue()))) {
            if (mode != MatchMode.TAG) {
                pickingTag = false; // e.g. Tag was clicked, then the current mode again: leave tag picking
            }
            refreshEntry();
            return; // already set
        }
        if (isDuplicate(mode, value)) {
            setEntryStatus("screen.daisychain.entry.duplicate");
            return;
        }
        entryStatusTicks = 0;
        if (mode != MatchMode.TAG) {
            pickingTag = false;
        }
        PacketDistributor.sendToServer(new SetFilterMatchPayload(menu.containerId,
                (byte) openEntry, (byte) mode.id(), value));
        refreshEntry();
    }

    /** Same rule as the server: another entry already matches exactly the same things. */
    private boolean isDuplicate(MatchMode mode, String value) {
        ItemStack mine = plain(entryStack());
        for (int i = 0; i < menu.slots.size(); i++) {
            if (!PullSideMenu.isFilterSlot(i) || PullSideMenu.filterSlotOf(i) == openEntry) {
                continue;
            }
            ItemStack other = menu.slots.get(i).getItem();
            if (other.isEmpty()) {
                continue;
            }
            FilterMatch match = other.get(ModDataComponents.FILTER_MATCH.get());
            MatchMode otherMode = match == null ? MatchMode.ITEM : match.matchMode();
            if (otherMode != mode) {
                continue;
            }
            boolean same = switch (mode) {
                case ITEM -> other.is(mine.getItem()) && sameFluidOrItem(other, mine);
                case DATA -> ItemStack.isSameItemSameComponents(plain(other), mine);
                case TAG -> match != null && match.value().equals(value);
                case MOD -> FluidGhost.isGhost(other) == FluidGhost.isGhost(mine)
                        && ItemFilter.modOf(other).equals(ItemFilter.modOf(mine));
            };
            if (same) {
                return true;
            }
        }
        return false;
    }

    /** Item entries: always true (same item). Fluid entries: the same fluid. */
    private static boolean sameFluidOrItem(ItemStack other, ItemStack mine) {
        SimpleFluidContent a = FluidGhost.contentOf(other);
        SimpleFluidContent b = FluidGhost.contentOf(mine);
        return a == null || b == null || a.getFluid() == b.getFluid();
    }

    private void setEntryStatus(String key) {
        entryStatus = FlowGui.styled(Component.translatable(key));
        entryStatusTicks = 60;
    }

    /** The tags to list: the entry's own while the search is empty, else every tag containing what's typed. */
    private void filterTags() {
        String text = tagSearchText.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            shownTags = entryOwnTags;
        } else {
            List<String> matching = new ArrayList<>();
            for (String tag : allTags) {
                if (tag.contains(text)) {
                    matching.add(tag);
                }
            }
            shownTags = matching;
        }
        tagPage = Math.min(tagPage, tagPages() - 1);
    }

    private int tagPages() {
        return Math.max(1, (shownTags.size() + TAG_ROWS - 1) / TAG_ROWS);
    }

    private void turnTagPage(int step) {
        tagPage = Math.max(0, Math.min(tagPages() - 1, tagPage + step));
        refreshEntry();
    }

    // ---- the entry's own numbers

    /** True when the open entry has any amount of its own (the server marks it with the filter_numbers component). */
    private boolean entryHasOwnAmounts() {
        return entryStack().has(ModDataComponents.FILTER_NUMBERS.get());
    }

    /** The entry's own number as last confirmed by the server, or ItemFilter.UNSET. */
    private int syncedEntryNumber(int which) {
        FilterNumbers numbers = entryStack().get(ModDataComponents.FILTER_NUMBERS.get());
        return numbers == null ? ItemFilter.UNSET : numbers.get(which);
    }

    /** What to show and build on: a just-clicked value for a second, else the server's. */
    private int entryNumber(int which) {
        return entryLocalTicks[which] > 0 ? entryLocalValue[which] : syncedEntryNumber(which);
    }

    /** The side's value, used while the entry has none of its own. Batch: 0 = Max. */
    private int entrySideValue(int which) {
        return sideNumber(insert ? PipeDataBlockEntity.SETTING_MAX_STOCK : ENTRY_SETTINGS[which]);
    }

    /** Faint = follows the side (or the side is in Blacklist mode, where entry amounts don't apply). */
    private int entryColor(int which) {
        return sideDenies() || entryNumber(which) == ItemFilter.UNSET ? FlowGui.FAINT : FlowGui.TEXT;
    }

    /** Same controls as the side's fields. Starting from the side's value when the entry has none of its own. */
    private void adjustEntryNumber(int which, int direction, boolean big, boolean jump) {
        int current = entryNumber(which);
        int base = current == ItemFilter.UNSET ? entrySideValue(which) : current;
        PipeKind kind = menu.resourceKind();
        int step = big ? kind.bigStep() : kind.smallStep();
        int next;
        if (which == ItemFilter.BATCH && !insert) {
            int max = sideTierAmount();
            int effective = base == 0 ? max : Math.min(base, max);
            if (jump) {
                next = direction > 0 ? 0 : 1;
            } else {
                long wanted = (long) effective + (long) direction * step;
                next = wanted >= max ? 0 : (int) Math.max(Math.min(kind.smallStep(), max), wanted);
            }
        } else if (jump) {
            next = direction > 0 ? kind.maxStock() : 0;
        } else {
            next = (int) Math.max(0L, Math.min(kind.maxStock(), (long) base + (long) direction * step));
        }
        if (next != current) {
            sendEntryNumber(which, next);
        }
    }

    private void sendEntryNumber(int which, int value) {
        if (openEntry < 0 || value == entryNumber(which)) {
            return;
        }
        entryLocalValue[which] = value;
        entryLocalTicks[which] = 20;
        PacketDistributor.sendToServer(new SetFilterNumberPayload(menu.containerId,
                (byte) openEntry, (byte) which, value));
        refreshEntryNumbers();
    }

    /** The number the typing box starts with. */
    private int entryEditValue(int which) {
        int value = entryNumber(which);
        if (value == ItemFilter.UNSET) {
            value = entrySideValue(which);
        }
        if (which == ItemFilter.BATCH && !insert) {
            int max = sideTierAmount();
            value = value == 0 ? max : Math.min(value, max);
        }
        return value;
    }

    /** A typed number for the entry. Batch: 0 or at/above the upgrade's amount = Max. */
    private void applyTypedEntry(int which, long value) {
        int next;
        if (which == ItemFilter.BATCH && !insert) {
            int max = sideTierAmount();
            next = value <= 0 || value >= max ? 0 : (int) value;
        } else {
            next = (int) Math.max(0, Math.min(menu.resourceKind().maxStock(), value));
        }
        sendEntryNumber(which, next);
    }

    /**
     * The entry's own amounts show in Advanced view only: Simple view hides every amount, and its shorter panel holds
     * just the matching part (step 47b). Amounts set in Advanced still apply, and the Advanced key shows its dot.
     */
    private boolean entryAmounts() {
        return advanced && menu.hasAmounts();
    }

    private void refreshEntryNumbers() {
        if (openEntry < 0 || !entryAmounts()) {
            return;
        }
        boolean deny = sideDenies();
        int max = sideTierAmount();
        for (int which = 0; which < 3; which++) {
            NumberButton field = entryFields[which];
            if (field == null) {
                continue;
            }
            int own = entryNumber(which);
            int value = own == ItemFilter.UNSET ? entrySideValue(which) : own;
            boolean batch = which == ItemFilter.BATCH && !insert;
            entryText[which] = batch
                    ? (value == 0 || value >= max
                            ? FlowGui.styled(Component.translatable("screen.daisychain.field.max", maxFieldAmount(max)))
                            : fieldAmount(value))
                    : value == 0 ? FlowGui.styled(Component.translatable("screen.daisychain.off")) : fieldAmount(value);
            if (field != editingButton) {
                field.active = !deny;
            }
            entryResetKeys[which].active = !deny && own != ItemFilter.UNSET;
            boolean shortened = (!batch || value != 0) && isShortened(value);
            field.setTooltip(fieldTooltip(deny ? Component.translatable("screen.daisychain.entry_allow_only") : null,
                    insert ? "screen.daisychain.entry_stock_help" : "screen.daisychain.entry_numbers_help", false,
                    shortened ? amount(value) : null));
        }
        layoutFields(drawerX() + ENTRY_FIELD_RIGHT, insert ? new String[] {"screen.daisychain.drawer.max_stock"}
                : ENTRY_LABELS, entryText, entryFields);
    }

    /** Text cut to fit maxWidth (with "..."), in the GUI font. */
    private Component fitted(Component text, int maxWidth) {
        Component styled = FlowGui.styled(text);
        if (font.width(styled) <= maxWidth) {
            return styled;
        }
        String plainText = text.getString();
        while (!plainText.isEmpty() && font.width(FlowGui.styled(plainText + "...")) > maxWidth) {
            plainText = plainText.substring(0, plainText.length() - 1);
        }
        return FlowGui.styled(plainText + "...");
    }

    /** The parts of the entry drawer that aren't widgets (drawn under them). */
    private void drawEntry(GuiGraphics graphics) {
        int dx = drawerX();
        int y = panelY;
        ItemStack entry = plain(entryStack());
        // Header: the entry 13x13 at (24,5), its name at x 42 (caps on y 8).
        graphics.pose().pushPose();
        graphics.pose().translate(dx + 24, y + 5, 0);
        graphics.pose().scale(13 / 16.0F, 13 / 16.0F, 1);
        if (FluidGhost.isGhost(entry)) {
            FluidRender.drawFluid(graphics, FluidGhost.contentOf(entry), 0, 0);
        } else {
            graphics.renderFakeItem(entry, 0, 0);
        }
        graphics.pose().popPose();
        FlowGui.text(graphics, font, fitted(entry.getHoverName(), DRAWER_RIGHT - 42), dx + 42, y + 8, FlowGui.TEXT);

        if (pickingTag) {
            // Search box frame (the text box sits inside it), "no tags", and the page text left of the arrows.
            int border = searchFocused() ? FlowGui.FIELD_HOVER : FlowGui.FIELD_BORDER;
            int sx = dx + DRAWER_PAD;
            FlowGui.round(graphics, sx, y + 84, sx + SEARCH_WIDTH, y + 97, border, 1);
            graphics.fill(sx + 1, y + 85, sx + SEARCH_WIDTH - 1, y + 96, FlowGui.SLOT);
            if (shownTags.isEmpty()) {
                FlowGui.text(graphics, font, FlowGui.styled(Component.translatable("screen.daisychain.no_tags")),
                        sx + 4, y + 45, FlowGui.FAINT);
            }
            Component pageText = FlowGui.styled((tagPage + 1) + "/" + tagPages());
            FlowGui.text(graphics, font, pageText, dx + 113 - FlowGui.inkWidth(font, pageText), y + 87, FlowGui.FAINT);
        } else {
            int lineY = y + 45;
            for (FormattedCharSequence line : font.split(FlowGui.styled(describeEntry()), DRAWER_RIGHT - DRAWER_PAD)) {
                graphics.drawString(font, line, dx + DRAWER_PAD, lineY + FlowGui.TEXT_SHIFT, FlowGui.DIM, false);
                lineY += 12;
            }
        }

        if (!advanced && menu.hasAmounts()) {
            // Simple view: a row saying where the amounts are (the key on its right opens Advanced).
            FlowGui.hline(graphics, dx + DRAWER_PAD, y + 103, DRAWER_RIGHT - DRAWER_PAD, FlowGui.LINE);
            boolean own = entryHasOwnAmounts();
            FlowGui.text(graphics, font, FlowGui.styled(Component.translatable(own ? "screen.daisychain.entry.amounts_set"
                    : "screen.daisychain.entry.amounts")), dx + DRAWER_PAD, y + 113, own ? FlowGui.TEXT : FlowGui.DIM);
        }
        if (entryAmounts()) {
            FlowGui.hline(graphics, dx + DRAWER_PAD, y + 103, DRAWER_RIGHT - DRAWER_PAD, FlowGui.LINE);
            if (insert) {
                drawerLabel(graphics, "screen.daisychain.drawer.max_stock", dx + DRAWER_PAD, y + 110);
            } else {
                for (int r = 0; r < 3; r++) {
                    drawerLabel(graphics, ENTRY_LABELS[r], dx + DRAWER_PAD, y + 110 + r * 17);
                }
            }
        }
        // Bottom left, beside Done: a short message after a refused change, or why the amounts are faint.
        if (entryStatusTicks > 0) {
            FlowGui.text(graphics, font, entryStatus, dx + DRAWER_PAD, y + drawerHeight - 17, FlowGui.WARN_TEXT);
        } else if (entryAmounts() && sideDenies()) {
            FlowGui.text(graphics, font, FlowGui.styled(Component.translatable("screen.daisychain.entry.whitelist_only")),
                    dx + DRAWER_PAD, y + drawerHeight - 17, FlowGui.DIM);
        }
    }

    /**
     * One row of the tag list: the chosen tag gets the key fill, a 1 px bar in the side color on its left and bright
     * text; the others are dim (spec 5.3 / design script).
     */
    private final class TagRow extends AbstractButton {
        private final int row;

        TagRow(int x, int y, int row) {
            super(x, y, DRAWER_RIGHT - DRAWER_PAD, ROW_HEIGHT, Component.empty());
            this.row = row;
        }

        /** The tag this row shows on the current page, or null. */
        @Nullable
        String tag() {
            int index = tagPage * TAG_ROWS + row;
            return index < shownTags.size() ? shownTags.get(index) : null;
        }

        @Override
        public void onPress() {
            String tag = tag();
            if (tag != null) {
                applyTag(tag);
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            String tag = tag();
            if (tag == null) {
                return;
            }
            int x = getX();
            int y = getY();
            boolean chosen = entryMatchMode() == MatchMode.TAG && tag.equals(entryMatchValue());
            if (chosen || isHovered()) {
                FlowGui.round(graphics, x, y, x + width, y + height, chosen ? FlowGui.KEY : FlowGui.KEY_HOVER, 1);
            }
            if (chosen) {
                graphics.fill(x, y + 1, x + 1, y + height - 1, sideColor());
            }
            FlowGui.text(graphics, PullSideScreen.this.font, fitted(Component.literal("#" + tag), width - 10),
                    x + 5, y + 3, chosen || isHovered() ? FlowGui.TEXT : FlowGui.DIM);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** The older light gray bevel panel (still used by the Pipe sides screen until step 48). */
    static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFC6C6C6);
        graphics.fill(x + 1, y + 1, x + width - 2, y + 3, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 3, y + height - 2, 0xFFFFFFFF);
        graphics.fill(x + 3, y + height - 3, x + width - 1, y + height - 1, 0xFF555555);
        graphics.fill(x + width - 3, y + 3, x + width - 1, y + height - 1, 0xFF555555);
    }
}
