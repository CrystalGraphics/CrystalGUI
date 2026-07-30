package com.crystalgui.ui.elements;

import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.KeyboardEvent;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A menu — ARIA's {@code role="menu"}, and the widget behind both a dropdown list and a context menu.
 *
 * <pre>
 * Menu menu = new Menu();
 * parent.addChild(menu);                     // must be in the tree to be promoted
 * menu.addItem("Cut").attachListener(...);
 * menu.addItem("Copy");
 * menu.showFor(button, button);              // dropdown: anchored under an element
 * menu.showAt(x, y, null);                   // context menu: anchored to a point
 * </pre>
 *
 * <h3>Dropdown and context menu are the same widget</h3>
 * <p>Not two classes, because on the web they are not two mechanisms either — both are a popover, one
 * anchored to an element and one to the pointer. {@link Popover#showFor} and {@link Popover#showAt} are
 * the only difference. A right-click menu is this class plus a listener that reads the pointer position.</p>
 *
 * <h3>Keyboard</h3>
 * <p>The ARIA menu pattern: Up/Down move between items, Home/End jump to the ends, Escape closes and
 * returns focus to the invoker, Enter/Space activates (inherited from {@link Button}, via
 * {@code UIInputHandler}'s activation-key synthesis). The whole menu is <b>one tab stop</b> — see
 * {@link MenuItem}.</p>
 *
 * <p>Listeners are attached in the <b>bubble</b> phase, so the focused element is an item <em>child</em>
 * of this menu rather than the menu itself; a target-phase listener would never fire. Same shape
 * {@code TabView} and {@code SplitView} use.</p>
 */
public class Menu extends Popover {

    public static final String ITEMS_CLASS = "__items__";

    /** Fires with the activated item. Emitted before the menu closes, so a listener can inspect it. */
    public final Signal.Value<MenuItem> onItemActivated = new Signal.Value<>();

    /**
     * How long the pointer must rest on a submenu row before it opens, in seconds.
     *
     * <p>Not zero, and Windows is right about that: sweeping the mouse down a menu crosses every row on the
     * way to the one you want, so opening instantly makes submenus flash open and shut under the cursor. A
     * short rest reads as intent.</p>
     *
     * <p>{@code 0.4s} is Windows' own default — the {@code MenuShowDelay} registry value, which ships at
     * 400ms. Picked because it is a number with a reason behind it rather than a guess, and it is settable
     * per menu ({@link #setSubmenuDelay}) for anyone who wants it snappier or lazier.</p>
     */
    public static final float DEFAULT_SUBMENU_DELAY = 0.4f;

    private final UIElement items;
    private final List<MenuItem> itemList = new ArrayList<>();

    @Getter
    @Setter
    private float submenuDelay = DEFAULT_SUBMENU_DELAY;

    /** The row whose submenu is waiting to open, and how long is left. */
    @Nullable
    private MenuItem pendingSubmenuItem;
    private float pendingSubmenuRemaining;
    private boolean submenuTickerRunning;

    public Menu() {
        this.items = new UIElement();
        this.items.addClass(ITEMS_CLASS);
        addInternalChild(this.items);

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isOpen() || itemList.isEmpty()) return;
            switch (event.getKeyCode()) {
                case CgUiKeyCodes.KEY_UP -> moveFocus(-1);
                case CgUiKeyCodes.KEY_DOWN -> moveFocus(1);
                case CgUiKeyCodes.KEY_HOME -> focusItem(0);
                case CgUiKeyCodes.KEY_END -> focusItem(itemList.size() - 1);
                // Right opens a submenu immediately — the delay exists to filter out an accidental mouse
                // sweep, and a deliberate keypress is never accidental. Left closes this menu and hands
                // focus back to the row that opened it, which Popover.hide()'s focus restore already does.
                case CgUiKeyCodes.KEY_RIGHT -> openFocusedSubmenu();
                case CgUiKeyCodes.KEY_LEFT -> {
                    if (parentPopover() == null) return; // a root menu has nothing to close back into
                    hide();
                }
                default -> {
                    return;
                }
            }
            // Only reached for a key we actually handled, so arrows keep working elsewhere.
            event.stopPropagation();
        }, false, true);
    }

    /**
     * Refuses public children: content goes in as items.
     *
     * <p>Same rule as every other composite here. A menu whose children were arbitrary would have no way
     * to answer "what does Down move to", which is most of what a menu is.</p>
     */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── Items ───────────────────────────────────────────────────────────────

    public MenuItem addItem(String label) {
        return addItemAt(new MenuItem(label), itemList.size());
    }

    public MenuItem addItem(MenuItem item) {
        return addItemAt(item, itemList.size());
    }

    public MenuItem addItemAt(MenuItem item, int index) {
        int at = Math.max(0, Math.min(itemList.size(), index));
        itemList.add(at, item);
        items.addChildAt(item, at);
        // Activation closes the menu, which is what a menu is for — UNLESS the item owns a submenu, in
        // which case closing would dismiss the parent and strand the child. That was an earlier bug:
        // pressing a submenu item opened the child and shut the menu it belonged to in the same breath, so
        // the submenu appeared out of nowhere with no parent behind it.
        //
        // hideChain(), not hide(): the ARIA pattern says activating a menuitem closes THE MENU, and every
        // native menu collapses the whole chain when you pick a leaf. hide() alone left the parent standing
        // after choosing from a submenu — you picked something and were still staring at the menu you picked
        // it from. Escape is the operation that peels one level; choosing is not.
        //
        // onItemActivated is emitted first either way, so a listener sees the item while the menu is still
        // open — closing restores focus, and a listener wanting to open a follow-up popover has to run
        // before that happens.
        // Focus follows the pointer, which is what native menus and the ARIA pattern both do — and without
        // it two rows are highlighted at once: the one the keyboard left focused and the one under the
        // mouse. It also keeps the two input modes in step, so pressing Down after hovering continues from
        // where the pointer is rather than jumping back to wherever focus was stranded.
        //
        // requestPointerFocus, not requestFocus: the latter rings, and a focus ring trailing the mouse
        // through a menu is the noise :focus-visible exists to avoid.
        item.onMouseEnter.attachListener((el, event) -> {
            if (!isOpen()) return;
            UIWindow window = getAttachedWindow();
            if (window != null) window.getInputHandler().requestPointerFocus(item);

            if (item.hasSubmenu()) {
                scheduleSubmenu(item);
            } else {
                // Moving onto an ordinary row closes whatever submenu was showing. Without this, sweeping
                // down a menu leaves every submenu you passed through still open, stacked over each other.
                cancelPendingSubmenu();
                closeSubmenus();
            }
        }, false, false);

        item.attachListener(() -> {
            onItemActivated.emit(item);
            Menu child = item.getSubmenu();
            if (child != null) child.showFor(item, item);
            else hideChain();
        });
        return item;
    }

    /**
     * Adds an item that opens {@code submenu} instead of dismissing this menu — ARIA's
     * {@code aria-haspopup} relationship.
     *
     * <p>Wires the three things a submenu needs and that a caller should not have to remember: the item
     * does not close its parent, the child is anchored to the <b>item</b> (so it tracks the row rather than
     * the menu), and it prefers {@link AnchoredPlacement.Side#RIGHT} so it sits beside its parent rather
     * than on top of it — flipping to the left when there is no room, like any other anchored popup.</p>
     *
     * <p>{@code submenu} must already be in the tree; a popover has to be attached to be promoted. It is
     * <em>not</em> adopted as a child of this menu, because {@link #acceptsPublicChildren()} is false and
     * because nesting popovers in the DOM is not required — the parent/child relationship that matters for
     * dismissal is the invoker link, which this establishes.</p>
     */
    public MenuItem addSubmenu(String label, Menu submenu) {
        MenuItem item = addItem(label);
        item.setSubmenu(submenu);
        item.addClass(MenuItem.HAS_SUBMENU_CLASS);
        submenu.setPreferredSide(AnchoredPlacement.Side.RIGHT);

        // The affordance every native menu has: a row that leads somewhere says so. Plain text rather than a
        // sprite so it works with no theme loaded, in the `__post-icon__` slot Button already provides — a
        // theme that wants a real glyph replaces it with setPostIcon and styles __submenu-arrow__.
        UIText arrow = new UIText(">");
        arrow.addClass(MenuItem.SUBMENU_ARROW_CLASS);
        arrow.setHitTest(false);
        item.setPostIcon(arrow);
        return item;
    }

    // ── Submenu opening ─────────────────────────────────────────────────────

    /** Opens the focused row's submenu now, ignoring the hover delay. Used by the Right arrow key. */
    private void openFocusedSubmenu() {
        int index = focusedIndex();
        if (index < 0) return;
        MenuItem item = itemList.get(index);
        Menu child = item.getSubmenu();
        if (child != null) {
            cancelPendingSubmenu();
            child.showFor(item, item);
        }
    }

    private void scheduleSubmenu(MenuItem item) {
        Menu child = item.getSubmenu();
        if (child == null || child.isOpen()) {
            // Already showing: leave it alone rather than re-opening, which would re-run the focus steps and
            // yank focus back to the child's first row while the pointer is still on the parent.
            cancelPendingSubmenu();
            return;
        }
        pendingSubmenuItem = item;
        pendingSubmenuRemaining = submenuDelay;
        if (submenuDelay <= 0f) {
            openPendingSubmenu();
            return;
        }
        startSubmenuTicker();
    }

    private void cancelPendingSubmenu() {
        pendingSubmenuItem = null;
    }

    private void openPendingSubmenu() {
        MenuItem item = pendingSubmenuItem;
        pendingSubmenuItem = null;
        if (item == null || !isOpen()) return;
        Menu child = item.getSubmenu();
        // Opening light-dismisses everything above this menu, so a sibling's submenu closes on the way.
        if (child != null) child.showFor(item, item);
    }

    /** Closes any submenu of this menu — everything stacked above it, which is what that means. */
    private void closeSubmenus() {
        UIWindow window = getAttachedWindow();
        if (window != null) window.lightDismiss(this);
    }

    private void startSubmenuTicker() {
        if (submenuTickerRunning) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        submenuTickerRunning = true;
        window.registerTicker(new SubmenuTicker());
    }

    /** Counts down the hover delay, then drops itself — the same shape Tooltip's placement ticker uses,
     * since there is deliberately no way to unregister a ticker. */
    private final class SubmenuTicker implements UIFrameTicker {
        @Override
        public boolean tickFrame(float deltaSeconds) {
            if (pendingSubmenuItem == null || !isOpen()) {
                submenuTickerRunning = false;
                return false;
            }
            pendingSubmenuRemaining -= deltaSeconds;
            if (pendingSubmenuRemaining <= 0f) {
                openPendingSubmenu();
                submenuTickerRunning = false;
                return false;
            }
            return true;
        }
    }

    public boolean removeItem(MenuItem item) {
        if (!itemList.remove(item)) return false;
        items.removeChild(item);
        return true;
    }

    public void clearItems() {
        for (MenuItem item : new ArrayList<>(itemList)) removeItem(item);
    }

    /** The items in order. Unmodifiable — use {@link #addItem}/{@link #removeItem}. */
    public List<MenuItem> getItems() {
        return Collections.unmodifiableList(itemList);
    }

    public int getItemCount() {
        return itemList.size();
    }

    /** The row container, for styling. */
    public UIElement itemsContainer() {
        return items;
    }

    // ── Keyboard ────────────────────────────────────────────────────────────

    /** Focus lands on the first item, matching the ARIA pattern: opening a menu with the keyboard should
     * leave the first choice ready rather than making the user press Down to start. */
    @Override
    protected void onOpened() {
        focusItem(0);
    }

    private void moveFocus(int step) {
        int current = focusedIndex();
        int next = current < 0
                ? (step > 0 ? 0 : itemList.size() - 1)
                // Wraps, per the ARIA pattern: a menu is a ring, so Down off the end returns to the top.
                : (current + step + itemList.size()) % itemList.size();
        focusItem(next);
    }

    private void focusItem(int index) {
        if (index < 0 || index >= itemList.size()) return;
        UIWindow window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(itemList.get(index));
    }

    private int focusedIndex() {
        UIWindow window = getAttachedWindow();
        if (window == null) return -1;
        UIElement focused = window.getInputHandler().getFocusedElement();
        for (UIElement el = focused; el != null; el = el.getParent()) {
            int index = itemList.indexOf(el);
            if (index >= 0) return index;
        }
        return -1;
    }
}
