package com.crystalgui.app.screens;

import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.screen.CgUiScreen;
import com.crystalgui.screen.ScreenKind;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * <b>The player's inventory as a game screen</b>: the layout only, in our own style. It is the app the container,
 * item and entity work of the game screens is built on.
 *
 * <pre>{@code
 * InventoryScreen.declareKey();                 // once, at client start: "Inventory" in the game's controls, unbound
 * CgUiScreen.show(InventoryScreen.SCREEN);
 *
 * UIElement slot = screen.document().getElementById(InventoryScreen.slotId(36));   // the first hotbar slot
 * }</pre>
 *
 * <p>Every slot is an empty frame with the id {@link #slotId} of its index in vanilla's {@code InventoryMenu}, so a
 * container service fills it by that number: 0 result, 1-4 crafting, 5-8 armour (head first), 9-35 main, 36-44 hotbar,
 * 45 offhand. The player view is the {@link #PLAYER_VIEW} element.</p>
 */
public final class InventoryScreen {

    public static final String ID = "crystalgui:inventory";

    /** Class of every slot frame. */
    public static final String SLOT = "__inv-slot__";
    /** Class of the box the player's model is drawn into. */
    public static final String PLAYER_VIEW = "__inv-player-view__";

    private static final String PANEL = "__inv__";
    private static final String PLACED = "__inv-placed__";

    /**
     * The game's GUI scale the screen is drawn at: a vanilla GUI unit is this many screen pixels, the document's UI scale
     * being pinned to 1. The layout is vanilla's, in its units (panel 176 x 166, slots 18). The engine cannot read the
     * game's own setting yet.
     */
    static final float GUI_SCALE = 3f;

    /** The screen's styles, in the pixels of {@code unit} per vanilla GUI unit. */
    private static String sheet(float unit) {
        return """
                .__game-screen__ { justify-content: center; align-items: center; }
                .%1$s { width: %2$s; height: %3$s;
                    background-color: var(--surface-panel, #191A1C); outline: 1px var(--border-strong, #5A5D63); }
                .%4$s { position: absolute; }
                .%6$s { font-size: %7$spx; text-align: center; }
                .__inv-arrow-part__ { position: absolute; background-color: var(--border-strong, #5A5D63); }
                /* Vanilla's bevel, one unit: dark above and left, light below and right. Every slot-like box has it. */
                .%8$s, .%5$s, .__inv-button__ {
                    background-color: var(--surface-base, #26282B); border-width: %9$s;
                    border-top-color: var(--surface-recessed, #131416); border-left-color: var(--surface-recessed, #131416);
                    border-bottom-color: var(--border-strong, #5A5D63); border-right-color: var(--border-strong, #5A5D63); }
                .%5$s { background-color: var(--surface-recessed, #131416); }
                """.formatted(PANEL, px(unit, 176), px(unit, 166), PLACED, PLAYER_VIEW, "__inv-title__", 8 * unit + 2,
                SLOT, px(unit, 1));
    }

    /** What the screen is: built each time it is shown, on its own document. */
    public static final ScreenKind SCREEN = ScreenKind.of(ID, screen -> {
        float unit = GUI_SCALE;
        StringBuilder sheet = new StringBuilder(sheet(unit));
        UIElement panel = new UIElement().addClass(PANEL);
        for (int i = 0; i < 4; i++) panel.append(slot(unit, sheet, 5 + i, 8, 8 + 18 * i));      // armour, head first
        panel.append(placed(unit, sheet, new UIElement().addClass(PLAYER_VIEW), "player-view", 26, 8, 50, 72));
        panel.append(slot(unit, sheet, 45, 76, 62));                                             // offhand
        panel.append(slot(unit, sheet, 1, 98, 18), slot(unit, sheet, 2, 116, 18), slot(unit, sheet, 3, 98, 36), slot(unit, sheet, 4, 116, 36));
        panel.append(arrow(unit, sheet, 136, 30));
        panel.append(slot(unit, sheet, 0, 154, 28));                                             // crafting result
        panel.append(placed(unit, sheet, new UIElement().addClass("__inv-button__"), "recipe-book", 106, 62, 20, 18));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) panel.append(slot(unit, sheet, 9 + row * 9 + col, 8 + 18 * col, 84 + 18 * row));
        }
        for (int col = 0; col < 9; col++) panel.append(slot(unit, sheet, 36 + col, 8 + 18 * col, 142));
        // last, so it paints over the grid; 1 px above vanilla's line
        panel.append(placed(unit, sheet, new UIText("Crafting").addClass("__inv-title__"), "title", 86, 8 - 1f / unit, 60, 10));
        screen.document().styles().addStylesheet(StyleSheet.parse(sheet.toString()));
        screen.root().append(panel);
    }).closedBy(CgKeyCodes.KEY_E).uiScale(1f);

    /** The launcher's entry: shows the screen in place of the desktop. */
    public static final ApplicationKind KIND = ApplicationKind.of(ID, "Inventory")
            .keywords("inventory", "player", "container", "screen")
            .action(context -> CgUiScreen.show(SCREEN));

    private InventoryScreen() {
    }

    /** The element id of the slot at {@code index} of vanilla's {@code InventoryMenu}. */
    public static String slotId(int index) {
        return "slot-" + index;
    }

    /** Lists the inventory in the game's controls, unbound until the player binds it. Client start. */
    public static void declareKey() {
        CgKeyBindings.declare(ID, CgKeyCodes.KEY_NONE, "crystalgui:keys", () -> CgUiScreen.show(SCREEN));
    }

    /**
     * The crafting arrow, 16 x 10 units from ({@code x}, {@code y}): a 9-unit shaft and a 7-unit head, drawn in
     * one-unit rows since the style system has no triangle.
     */
    private static UIElement arrow(float unit, StringBuilder sheet, int x, int y) {
        UIElement arrow = placed(unit, sheet, new UIElement(), "arrow", x, y, 16, 10);
        arrow.append(part(unit, sheet, 0, 0, 3, 9, 4));
        int[] head = {2, 3, 4, 5, 7, 7, 5, 4, 3, 2};
        for (int row = 0; row < head.length; row++) arrow.append(part(unit, sheet, row + 1, 9, row, head[row], 1));
        return arrow;
    }

    private static UIElement part(float unit, StringBuilder sheet, int n, int x, int y, int w, int h) {
        return placed(unit, sheet, new UIElement().addClass("__inv-arrow-part__"), "arrow-" + n, x, y, w, h);
    }

    private static String px(float unit, float units) {
        return units * unit + "px";
    }

    /** An 18-unit slot frame at ({@code x}, {@code y}) units from the panel's corner. */
    private static UIElement slot(float unit, StringBuilder sheet, int index, int x, int y) {
        return placed(unit, sheet, new UIElement().addClass(SLOT), slotId(index), x, y, 18, 18);
    }

    /** Gives {@code element} its id and an absolute box, in vanilla units from the panel's corner. */
    private static UIElement placed(float unit, StringBuilder sheet, UIElement element, String id, float x, float y, float w, float h) {
        sheet.append('#').append(id).append(" { left: ").append(px(unit, x)).append("; top: ").append(px(unit, y))
                .append("; width: ").append(px(unit, w)).append("; height: ").append(px(unit, h)).append("; }");
        return element.addClass(PLACED).setId(id);
    }
}
