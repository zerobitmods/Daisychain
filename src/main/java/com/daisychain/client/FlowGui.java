package com.daisychain.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Step 44: the Daisychain GUI look from the design spec (v8, "GUI v3"): flat panels, slots and keys drawn in code and
 * the 8x8 icons. Client only. All y values for text are the top of the capital letters, as in the spec.
 * <p>
 * Step 47c (chosen after comparing renders): Minecraft's own font instead of Chakra Petch (it sits on the
 * pixel grid like everything else, so text is crisp and even at every GUI scale), softer colors, chosen keys in a soft
 * tint of the side color with light text instead of a solid fill with dark text, and pixel-rounded corners (1 px on
 * keys, fields and slots, 3 px on panels).
 */
final class FlowGui {
    /**
     * Where the font draws its capitals relative to the y given to drawString. The spec asks for a one-time check in
     * game: change this until a capital drawn at y starts exactly at y. (Positive = draw lower.)
     */
    static final int TEXT_SHIFT = 0;

    // Tokens: design spec section 3, softened in step 47c (lower contrast, lighter panels).
    static final int EDGE = 0xFF0E0F12;
    static final int PANEL = 0xFF202229;
    static final int SLOT = 0xFF18191E;
    static final int SLOT_EDGE = 0xFF2D3038;
    static final int FILTER_EDGE_PULL = 0xFF3A3450;
    static final int FILTER_EDGE_INSERT = 0xFF22434B;
    static final int KEY = 0xFF2C2F37;
    static final int KEY_HOVER = 0xFF353841;
    static final int LINE = 0xFF2C2F37;
    static final int TEXT = 0xFFDCDFE5;
    static final int DIM = 0xFF8D929C;
    static final int FAINT = 0xFF5F646E;
    /** The ring around an open filter entry. */
    static final int WHITE = 0xFFDCDFE5;
    /** An "on" neutral key (the Advanced key, Done) and its icon or text. */
    static final int ON = 0xFF3D414B;
    static final int ON_INK = 0xFFDCDFE5;
    static final int WARN = 0xFFD46A62;
    static final int WARN_TEXT = 0xFFE8948C;
    /** An armed warning key (Clear filter, "Click again"): warn tinted into the panel. */
    static final int WARN_TINT = 0xFF4F3538;
    static final int FIELD_BORDER = 0xFF34373F;
    /** Not in the spec: a field's border while the mouse is over it (it's clickable). */
    static final int FIELD_HOVER = 0xFF4A4E58;
    static final int TAB_OFF = 0xFF17181D;
    static final int GREEN = 0xFF6CC07E;
    /** Side colors: the accent (bars, dots), the tint a chosen key is filled with, and the light text on that tint. */
    static final int PULL = 0xFFA48DF2;
    static final int PULL_TINT = 0xFF423E5D;
    static final int PULL_INK = 0xFFC9BCFF;
    static final int INSERT = 0xFF5CC4D2;
    static final int INSERT_TINT = 0xFF304C55;
    static final int INSERT_INK = 0xFFA6E6EE;
    /** Pipe sides screen (step 48): a square's border per side mode (spec section 1 colors, softened). */
    static final int MODE_PULL = PULL;
    static final int MODE_PULL_EMPTY = 0xFF5E5385;
    static final int MODE_INSERT = INSERT;
    static final int MODE_LINK = 0xFFC9CFD8;
    static final int MODE_OFF = WARN;
    static final int MODE_NONE = 0xFF62666F;

    // 8x8 (and a few 7/9 wide) icons from the spec's ui3.py.
    static final String[] CHECK = {"........", ".......#", "......##", "#....##.", "##..##..", ".####...", "..##....", "........"};
    /** Blacklist: an even circle with a top-left to bottom-right slash (step 47d; the spec's bitmap was lopsided). */
    static final String[] BAN = {"..####..", ".#....#.", "#.#....#", "#..#...#", "#...#..#", "#....#.#", ".#....#.", "..####.."};
    static final String[] RS_ALWAYS = {"........", "........", "........", "########", "........", "........", "........", "........"};
    static final String[] RS_ON = {"........", "...#####", "...#....", "...#....", "...#....", "####....", "........", "........"};
    static final String[] RS_OFF = {"........", "#####...", "....#...", "....#...", "....#...", "....####", "........", "........"};
    static final String[] RS_PULSE = {"........", "..###...", "..#.#...", "..#.#...", "..#.#...", "###.####", "........", "........"};
    static final String[] PR_HIGH = {"...##...", "..####..", ".######.", "...##...", "...##...", "...##...", "...##...", "........"};
    static final String[] PR_NORM = {"........", "........", ".######.", "........", ".######.", "........", "........", "........"};
    static final String[] PR_LOW = {"........", "...##...", "...##...", "...##...", "...##...", ".######.", "..####..", "...##..."};
    static final String[] SLIDERS = {".##.....", "########", ".##.....", "........", ".....##.", "########", ".....##.", "........"};
    static final String[] FACE = {"#######", "#.....#", "#.###.#", "#.###.#", "#.###.#", "#.....#", "#######"};
    static final String[] CUBE = {"..###....", "..#.#....", "#########", "#.#.#.#.#", "#########", "..#.#....", "..###...."};
    /** Step 47 (filter entry drawer): back arrow, page arrows, reset cross. */
    static final String[] BACK = {"..#....", ".#.....", "#######", ".#.....", "..#...."};
    static final String[] ARROW_LEFT = {"...#", "..#.", ".#..", "#...", ".#..", "..#.", "...#"};
    static final String[] ARROW_RIGHT = {"#...", ".#..", "..#.", "...#", "..#.", ".#..", "#..."};
    static final String[] CROSS = {"#...#", ".#.#.", "..#..", ".#.#.", "#...#"};
    /** Step 47f: 5x5 glyphs for the filter entry badges (data, tag, mod; own amounts). */
    static final String[] BADGE_DATA = {"####.", "#...#", "#...#", "#...#", "####."};
    static final String[] BADGE_TAG = {".#.#.", "#####", ".#.#.", "#####", ".#.#."};
    static final String[] BADGE_MOD = {"#...#", "##.##", "#.#.#", "#...#", "#...#"};
    static final String[] BADGE_AMOUNTS = {"..#..", "..#..", "#####", "..#..", "..#.."};
    /** Step 49: universal pipe resource tabs (item box, fluid drop, energy bolt) and the on/off (power) key. */
    static final String[] ITEMS = {"..####..", ".#....#.", "########", "#......#", "#.####.#", "#......#", "########", "........"};
    static final String[] FLUIDS = {"...##...", "...##...", "..####..", ".######.", ".######.", ".######.", "..####..", "........"};
    static final String[] ENERGY = {"....###.", "...###..", "..###...", ".######.", "...###..", "..###...", ".##.....", "........"};
    static final String[] POWER = {"...##...", ".#.##.#.", "#..##..#", "#..##..#", "#......#", "#......#", ".#....#.", "..####.."};
    static final String[] RING ={".######.", "#......#", "#.####.#", "#.#..#.#", "#.#..#.#", "#.####.#", "#......#", ".######."};

    private FlowGui() {
    }

    /** The text as the GUI draws it. Since step 47c that's Minecraft's own font, so this is a plain copy. */
    static MutableComponent styled(Component text) {
        return text.copy();
    }

    static MutableComponent styled(String text) {
        return Component.literal(text);
    }

    /**
     * The width of the letters themselves: Minecraft's font adds 1 px of spacing after every glyph, including the last,
     * so centering and right-aligning by font.width would sit half a pixel (or a pixel) off.
     */
    static int inkWidth(Font font, Component text) {
        return Math.max(0, font.width(text) - 1);
    }

    static int width(Font font, Component styledText) {
        return font.width(styledText);
    }

    /** Text with its capitals' top at capTop. */
    static void text(GuiGraphics graphics, Font font, Component styledText, int x, int capTop, int color) {
        graphics.drawString(font, styledText, x, capTop + TEXT_SHIFT, color, false);
    }

    /** Text centered in a box: x = boxX + (boxW - width) / 2, rounded. */
    static void centered(GuiGraphics graphics, Font font, Component styledText, int boxX, int boxW, int capTop, int color) {
        int x = boxX + Math.round((boxW - inkWidth(font, styledText)) / 2.0F);
        text(graphics, font, styledText, x, capTop, color);
    }

    /** A flat panel: 1 px edge, corners rounded 3 px (step 47c). */
    static void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        round(graphics, x, y, x + w, y + h, EDGE, 3);
        round(graphics, x + 1, y + 1, x + w - 1, y + h - 1, PANEL, 2);
    }

    /** A single slot: the same as a 1x1 slotGrid (2 px frame, 16x16 inside). */
    static void slot(GuiGraphics graphics, int x, int y, int edge) {
        slotGrid(graphics, x, y, 1, 1, edge);
    }

    /**
     * A block of slots on the 18 px pitch (x, y = the first slot's column origin, 1 px left of and above the item).
     * Step 47e: every line is 2 px, the outer edge included: the frame starts 1 px outside the column
     * origin, slots sit 2 px apart, and each inside is exactly 16x16 under the item, so items are centered and the
     * vanilla hover highlight fits. Outer corners rounded 1 px.
     */
    static void slotGrid(GuiGraphics graphics, int x, int y, int cols, int rows, int edge) {
        round(graphics, x - 1, y - 1, x + cols * 18 + 1, y + rows * 18 + 1, edge, 1);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int cx = x + c * 18 + 1;
                int cy = y + r * 18 + 1;
                graphics.fill(cx, cy, cx + 16, cy + 16, SLOT);
            }
        }
    }

    /** A filled rectangle (x0,y0)-(x1,y1) with pixel-rounded corners: r pixels cut off as a staircase. */
    static void round(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color, int r) {
        for (int i = 0; i <= r; i++) {
            int inset = r - i;
            graphics.fill(x0 + inset, y0 + i, x1 - inset, y1 - i, color);
        }
    }

    /** Like round, but only the left corners (tabs that join a panel on their right). */
    static void roundLeft(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color, int r) {
        for (int i = 0; i <= r; i++) {
            graphics.fill(x0 + r - i, y0 + i, x1, y1 - i, color);
        }
    }

    static void icon(GuiGraphics graphics, int x, int y, String[] rows, int color) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int start = -1;
            for (int c = 0; c <= row.length(); c++) {
                boolean on = c < row.length() && row.charAt(c) == '#';
                if (on && start < 0) {
                    start = c;
                } else if (!on && start >= 0) {
                    graphics.fill(x + start, y + r, x + c, y + r + 1, color);
                    start = -1;
                }
            }
        }
    }

    static void hline(GuiGraphics graphics, int x, int y, int length, int color) {
        graphics.fill(x, y, x + length, y + 1, color);
    }
}
