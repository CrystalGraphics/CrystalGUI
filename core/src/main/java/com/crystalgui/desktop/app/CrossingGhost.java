package com.crystalgui.desktop.app;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.dnd.DragGhost;

/**
 * The shell's drag ghost, drawn for a drag of a value out of an application's document. The source's own ghost lives in
 * its document and is cut off at that document's edge, so while the shell carries a drag it draws the ghost itself, the
 * same capsule as a tree's row ghost, under the pointer wherever that is. One per document; the shell's thread.
 */
final class CrossingGhost {

    /** A tree row's capsule: icon and label in one bordered box. */
    private static final String ROW_GHOST_CLASS = "__row-ghost__";

    private final DragGhost ghost = new DragGhost();
    private String shown = "";

    CrossingGhost() {
        ghost.addClass(ROW_GHOST_CLASS);
    }

    /**
     * Shows {@code text} with {@code icon} (null for none) with its corner at the surface pixel ({@code x}, {@code y}).
     * Idempotent per frame: asking again moves it.
     */
    void showAt(UIElement layer, String icon, String text, float x, float y, float uiScale) {
        if (ghost.getParent() != layer) layer.append(ghost);
        String key = icon + "|" + text;
        if (!key.equals(shown)) {
            shown = key;
            ghost.follow(null, icon, text);
        }
        Box origin = layer.box();
        float left = (x - (origin == null ? 0f : origin.worldX())) / uiScale;
        float top = (y - (origin == null ? 0f : origin.worldY())) / uiScale;
        StyleGroup.inlinePipeline(ghost.getStyle().getLayoutGroup(), l -> l.left(left).top(top));
        if (!ghost.isDisplayed()) ghost.setDisplayed(true);
    }

    void hide() {
        if (ghost.isDisplayed()) ghost.setDisplayed(false);
    }
}
