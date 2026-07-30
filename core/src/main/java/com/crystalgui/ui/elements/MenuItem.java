package com.crystalgui.ui.elements;

import com.crystalgui.ui.input.FocusPolicy;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * One row in a {@link Menu} — ARIA's {@code role="menuitem"}.
 *
 * <p>Extends {@link Button} for the same reason {@link Tab} does: it is a labelled, activatable control
 * with icon slots, and it gets Space/Enter activation from {@code UIInputHandler} for free. The only
 * thing it changes is its focus policy.</p>
 *
 * <p><b>Always {@code CLICK_NOT_TABBABLE}</b> — a menu is an ARIA composite, so the whole menu is one tab
 * stop and the arrow keys move within it. Unlike {@link Tab} the stop does not <em>rove</em> here: an open
 * menu takes focus outright and Tab has nothing to do inside it, so every item is permanently outside the
 * tab sequence rather than taking turns being in it. Items stay clickable and stay reachable by
 * {@code requestFocus}, which is what {@code CLICK_NOT_TABBABLE} means.</p>
 */
public class MenuItem extends Button {

    /** On an item that owns a submenu, so a theme can lay it out differently — label left, arrow right. */
    public static final String HAS_SUBMENU_CLASS = "__has-submenu__";
    /** On the arrow itself. A theme can replace it wholesale via {@link Button#setPostIcon}. */
    public static final String SUBMENU_ARROW_CLASS = "__submenu-arrow__";

    /**
     * The menu this item opens, or {@code null} for an ordinary item — ARIA's {@code aria-haspopup}.
     *
     * <p>Package-private setter because the relationship is the parent menu's to establish
     * ({@link Menu#addSubmenu}), which is also where the placement and the don't-close-on-activate
     * behaviour get wired. An item that knew about a submenu the menu did not would close its parent on
     * activation and strand the child.</p>
     */
    @Getter
    @Nullable
    private Menu submenu;

    public MenuItem(String label) {
        super(label);
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
    }

    void setSubmenu(@Nullable Menu submenu) {
        this.submenu = submenu;
    }

    /** Whether activating this opens a submenu rather than dismissing the menu. */
    public boolean hasSubmenu() {
        return submenu != null;
    }
}
