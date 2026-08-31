package com.crystalgui.widget.overlay;

import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.input.FocusPolicy;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UISlot;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgui.style.StyleGroup;

/**
 * A menu — ARIA's {@code role="menu"}, and the widget behind both a dropdown list and a context menu.
 *
 * <pre>
 * Menu menu = new Menu();
 * parent.append(menu);                     // must be in the tree to be promoted
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

    public static final Name NAME = Name.of("menu");

    /**
     * A menu's items are described CHILDREN, not state -- each is a {@code MenuItem} with a contract of
     * its own. Its own mode is a {@code Popover} concern and is not inherited, because
     * {@link WidgetContracts#of(com.crystalgui.ui.UINode)} is an exact-class lookup.
     */
    public static final WidgetContract<Menu> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Menu.class, "menu")
                    .withDescribedChildren()
                    .build());


    public static final String ITEMS_PART = "items";

    /** A grouping rule between items. @see #addSeparator() */
    public static final String SEPARATOR_PART = "separator";

    /**
     * On a menu that contains at least one checkable row, so <b>every</b> row reserves the mark gutter.
     *
     * <h3>The gutter belongs to the menu, not the item</h3>
     * <p>Reserving it per-item indents that row against its neighbours, which reads as broken alignment
     * rather than as a checkmark. Every native menu reserves the column once for the whole menu — in
     * Windows' own context menu, {@code Refresh} and {@code Paste} line up with the rows that have icons
     * precisely because of this. Reserving it on <em>all</em> menus is the other wrong answer: an ordinary
     * context menu would carry a dead column for a mark none of its rows can ever show, which is why the
     * base sheet gives {@code __mark__} zero width by default.</p>
     *
     * @see #addCheckableItem(String)
     */
    public static final String HAS_CHECKABLE_CLASS = "__has-checkable__";

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

    private final UINode items;
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
        super(NAME);
        this.items = new UINode();
        
        this.items.set(Attribute.PART, ITEMS_PART);
        // THE ITEMS ARE CONTENT, NOT PARTS, so they go in a SLOT and stay light children.
        //
        // They were appended straight into `items`, which is inside this menu's shadow root -- so
        // every `menuitem` rule in every sheet is an outer rule that cannot reach them, and a menu
        // drew its rows with no padding, no hover bar and no focus bar. The same reasoning as
        // ScrollerView's viewport: a widget's own structure is a part, and what a CALLER puts in is
        // content. `addItem` appends to the menu; the slot renders them here, in order.
        UISlot itemSlot = new UISlot();
        // FULL WIDTH and a column, or the rows size to their own text inside a menu that is wider --
        // and the focus bar, which is the row's own background, stops short of the menu's edge. The
        // slot is a real box between the menu and its rows, so `align-items: stretch` on the menu
        // reaches the SLOT and stops there.
        StyleGroup.defaultPipeline(itemSlot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).flexDirection(FlexDirection.COLUMN));
        this.items.append(itemSlot);
        shadow().append(this.items);

        // CLICK_NOT_TABBABLE, not FOCUSABLE: this is a real focus target now (see onOpened below), but
        // a menu is still "one tab stop" per MenuItem's own doc — Tab has nothing to do inside an open
        // menu, so the container itself must stay out of the tab sequence exactly like its items do.
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isOpen() || itemList.isEmpty()) return;
            boolean handled = true;
            switch (event.getKeyCode()) {
                case CgKeyCodes.KEY_UP -> moveFocus(-1);
                case CgKeyCodes.KEY_DOWN -> moveFocus(1);
                case CgKeyCodes.KEY_HOME -> focusItem(0);
                case CgKeyCodes.KEY_END -> focusItem(itemList.size() - 1);
                // Right opens a submenu immediately — the delay exists to filter out an accidental mouse
                // sweep, and a deliberate keypress is never accidental. Left closes this menu and hands
                // focus back to the row that opened it, which Popover.hide()'s focus restore already does.
                //
                // CONSUMED ONLY IF IT OPENED SOMETHING. A Right press on a row with no submenu used to be
                // swallowed anyway, which left a menu bar with no way to hear it — and moving to the next
                // top-level menu is exactly what Right means there. The same asymmetry Left already had,
                // where a root menu returns without consuming because it has nothing to close back into.
                case CgKeyCodes.KEY_RIGHT -> handled = openFocusedSubmenu();
                case CgKeyCodes.KEY_LEFT -> {
                    if (parentPopover() == null) {
                        handled = false;   // a root menu has nothing to close back into
                    } else {
                        hide();
                    }
                }
                default -> handled = focusByTypedLetter(event);
            }
            // Only for a key we actually handled, so arrows keep working elsewhere.
            if (handled) event.stopPropagation();
        }, false, true);
    }

    /**
     * Refuses public children: content goes in as items.
     *
     * <p>Same rule as every other composite here. A menu whose children were arbitrary would have no way
     * to answer "what does Down move to", which is most of what a menu is.</p>
     */

    // ── Items ───────────────────────────────────────────────────────────────

    public MenuItem addItem(String label) {
        return addItemAt(new MenuItem(label), itemList.size());
    }

    public MenuItem addItem(MenuItem item) {
        return addItemAt(item, itemList.size());
    }

    /**
     * A row whose checked state is shown with a mark — a toggle, as opposed to a command.
     *
     * <p>Adding one switches the <b>whole menu</b> to a reserved mark gutter, so every row's label stays
     * on the same left edge whether or not it can be checked. See {@link #HAS_CHECKABLE_CLASS} for why
     * that is a menu-level decision rather than a per-item one.</p>
     *
     * <p>Use {@link MenuItem#setSelected} to tick it. The two are separate on purpose: this says the mark
     * <em>can</em> appear, that says whether it currently does — so a toggle reserves its space while
     * switched off and the label does not jump the first time it is ticked.</p>
     */
    public MenuItem addCheckableItem(String label) {
        MenuItem item = addItem(label);
        item.setCheckable(true);
        addClass(HAS_CHECKABLE_CLASS);
        return item;
    }

    /**
     * A horizontal rule grouping the items around it — the sections every native context menu uses.
     *
     * <h3>It is not an item, and that is the whole implementation</h3>
     * <p>A separator is kept out of {@link #itemList} entirely. Arrow keys, {@code Home}/{@code End} and
     * {@link #getItemCount()} all work in terms of that list, so a separator is skipped by every one of them
     * for free, with no "is this selectable?" check anywhere. That matches ARIA, where a
     * {@code separator} is not a {@code menuitem} and is never in the focus order.</p>
     *
     * <p>It is also {@code setHitTest(false)}, so the row cannot take hover or swallow a press aimed at
     * the item beneath it — a 1px strip that eats clicks is very hard to attribute to anything.</p>
     */
    public UINode addSeparator() {
        UINode separator = new UINode();
        separator.set(Attribute.PART, SEPARATOR_PART);
        separator.setHitTest(false);
        append(separator);
        return separator;
    }

    /**
     * Drops a trailing {@link #addSeparator} again, if the last thing added was one.
     *
     * <p>For a builder that has to emit a separator <em>before</em> knowing whether anything will follow
     * it — which is any builder splicing in a contributed section, since a section whose only entry is an
     * empty submenu adds nothing. Adding speculatively and retracting is the only order available: a
     * separator goes before its section, and whether the section exists is answered by building it.</p>
     *
     * <p>Safe to call when the last child is a real item; it removes nothing.</p>
     *
     * @return whether a separator was removed
     */
    public boolean removeLastSeparator() {
        List<UINode> children = children();
        if (children.isEmpty()) return false;
        UINode last = children.get(children.size() - 1);
        if (!last.hasClass(SEPARATOR_PART)) return false;
        remove(last);
        return true;
    }

    public MenuItem addItemAt(MenuItem item, int index) {
        int at = Math.max(0, Math.min(itemList.size(), index));
        // The item's index is NOT its child index once separators exist: a separator is a child
        // without being in `itemList`, so appending at `itemList.size()` would insert an item BEFORE
        // any trailing separator. Resolved against the element actually occupying that slot.
        //
        // A LIGHT child of the menu, rendered through the slot in `items`. They were put straight into
        // `items`, which is inside this menu's shadow root -- so every `menuitem` rule in every sheet
        // was an outer rule that could not reach them, and a row had no padding and no focus bar. The
        // items are CONTENT; `items` is the menu's own structure. Same split as ScrollerView's
        // viewport.
        int childIndex = at < itemList.size()
                ? indexOf(itemList.get(at))
                : children().size();
        itemList.add(at, item);
        insertAt(childIndex, item);
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
            UIDocument window = document();
            if (window != null) window.focus().requestPointerFocus(item);

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

        // The affordance every native menu has: a row that leads somewhere says so. A vector chevron
        // (overlay: shape("chevron-right") in default.css) rather than a sprite so it works with no
        // theme loaded — same reasoning the plain-text glyph this replaced was after, without a font
        // standing in for a triangle it does not actually contain. A theme that wants something else
        // still overrides via setPostIcon and __submenu-arrow__.
        UINode arrow = new UINode();
        arrow.addClass(MenuItem.SUBMENU_ARROW_CLASS);
        arrow.setHitTest(false);
        item.setPostIcon(arrow);
        return item;
    }

    // ── Submenu opening ─────────────────────────────────────────────────────

    /**
     * Opens the focused row's submenu now, ignoring the hover delay. Used by the Right arrow key.
     *
     * @return whether there was one to open
     */
    private boolean openFocusedSubmenu() {
        int index = focusedIndex();
        if (index < 0) return false;
        MenuItem item = itemList.get(index);
        Menu child = item.getSubmenu();
        if (child == null) return false;
        cancelPendingSubmenu();
        // INHERITED, so a drag that crosses into a submenu can still release on one of its rows. Set
        // before showFor, because showFor is what moves focus into the child.
        child.armedForRelease = armedForRelease;
        child.showFor(item, item);
        return true;
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
        UIDocument window = document();
        if (window != null) window.dismiss().lightDismiss(this);
    }

    private void startSubmenuTicker() {
        if (submenuTickerRunning) return;
        UIDocument window = document();
        if (window == null) return;
        submenuTickerRunning = true;
        window.animation().every(this, this::tickSubmenu);
    }

    /** Counts down the hover delay, then drops itself — the same shape Tooltip's placement ticker uses,
     * since there is deliberately no way to unregister a ticker. */
    private boolean tickSubmenu(float deltaSeconds) {
        {
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
        remove(item);
        return true;
    }

    /** Empties the menu — separators included, since a menu of nothing but rules is not "cleared". */
    public void clearItems() {
        for (MenuItem item : new ArrayList<>(itemList)) removeItem(item);
        // Separators are not in itemList, so the loop above cannot reach them. Rebuilding a menu without
        // this leaves the old rules stacked up with no items between them.
        for (UINode child : new ArrayList<>(children())) {
            if (child.hasClass(SEPARATOR_PART)) remove(child);
        }
    }

    /** The items in order. Unmodifiable — use {@link #addItem}/{@link #removeItem}. */
    public List<MenuItem> getItems() {
        return Collections.unmodifiableList(itemList);
    }

    public int getItemCount() {
        return itemList.size();
    }

    /** The row container, for styling. */
    public UINode itemsContainer() {
        return items;
    }

    // ── Keyboard ────────────────────────────────────────────────────────────

    /**
     * Focus lands on the menu itself, not on any item — deliberately <b>not</b> the ARIA-suggested
     * "first choice ready" behaviour.
     *
     * <p>Pre-highlighting item 0 the instant the menu opens reads as a selection the user never made:
     * a dropdown opened to check its current value shows some unrelated row lit up before the mouse or
     * keyboard has done anything. Landing focus on the menu itself keeps every row unlit — {@link
     * #moveFocus} already treats "nothing focused inside" ({@link #focusedIndex()} returning {@code -1})
     * as the starting point for the first Up/Down press, so arrow-key navigation is unaffected; only the
     * free pre-selection on open is gone. A mouse hover still moves focus onto a row immediately, via
     * {@link #addItemAt}'s {@code onMouseEnter} listener, and remains a purely pointer-driven focus (no
     * ring) — so mouse-hover and keyboard-Down produce the same "I asked for this" affordance and only
     * the passive act of opening does not.</p>
     *
     * <p>The menu must still take focus <em>somewhere</em>, though — keyboard events dispatch to whatever
     * is currently focused and walk from there, so with nothing focused inside this subtree at all,
     * arrow/Home/End would never reach {@link #events}'s {@code KeyboardEvent.Down} listener in the first
     * place. {@code menu:focus-visible { outline: 0; }} in the base sheet is what keeps this invisible —
     * see {@link com.crystalgui.ui.input.UIInputHandler#requestFocus} ringing by default.</p>
     */
    @Override
    protected void onOpened() {
        UIDocument window = document();
        if (window != null) window.focus().requestFocus(this);
    }

    /**
     * Type-to-select — the letter keys every native menu answers to.
     *
     * <h3>Activate when unique, cycle when not</h3>
     *
     * <p>Windows' rule, and both references follow it: if exactly one row begins with the typed letter it
     * is <b>chosen</b>, and if several the focus steps to the next of them so repeated presses cycle. That
     * asymmetry is what makes the common case one keystroke instead of two, and it is why this is not
     * simply "move focus to the first match".</p>
     *
     * <p><b>Disabled rows are skipped entirely</b> — not merely un-activatable. Counting them would let a
     * greyed row make a unique match look ambiguous, so a letter that plainly identifies one usable row
     * would stop choosing it.</p>
     *
     * <p>Bare letters only. A modified chord belongs to the keymap, and swallowing {@code Mod+S} here
     * because some row starts with "S" would make an open menu eat the application's shortcuts.</p>
     */
    private boolean focusByTypedLetter(KeyboardEvent event) {
        if (CgModifiers.hasCtrl(event.getModifiers()) || CgModifiers.hasAlt(event.getModifiers())
                || CgModifiers.hasSuper(event.getModifiers())) {
            return false;
        }
        char typed = Character.toLowerCase(event.getCharacter());
        if (!Character.isLetterOrDigit(typed)) return false;

        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < itemList.size(); i++) {
            MenuItem item = itemList.get(i);
            if (!item.isEnabled()) continue;
            String label = item.getText();
            if (!label.isEmpty() && Character.toLowerCase(label.charAt(0)) == typed) matches.add(i);
        }
        if (matches.isEmpty()) return false;

        if (matches.size() == 1) {
            int only = matches.get(0);
            focusItem(only);
            itemList.get(only).onPressed.emit();
            return true;
        }
        // Cycles from wherever focus is, so holding the letter walks the group and wraps.
        int current = focusedIndex();
        for (int index : matches) {
            if (index > current) {
                focusItem(index);
                return true;
            }
        }
        focusItem(matches.get(0));
        return true;
    }

    /**
     * Lets a release activate a row even though the press landed elsewhere — press-drag-release.
     *
     * <h3>Why it cannot just work</h3>
     *
     * <p>{@link MenuItem} inherits {@link com.crystalgui.ui.elements.Button}'s {@code isWasPressTarget()}
     * guard, which is exactly right for a button — releasing off a button you pressed must not activate it
     * — and exactly wrong for a menu opened by a press that is still held. Every native menu bar supports
     * press, drag onto an entry, release; without this the gesture leaves the menu open and chooses
     * nothing.</p>
     *
     * <p>Armed by whoever opened the menu from a live press, and disarmed on the first release, so it can
     * never turn an ordinary later click into a stray activation. Propagates into submenus as they open,
     * because a drag routinely crosses into one.</p>
     */
    public Menu armForRelease() {
        this.armedForRelease = true;
        return this;
    }

    /** @see #armForRelease */
    public boolean isArmedForRelease() {
        return armedForRelease;
    }

    /** One-shot: cleared by the first release, wherever it lands. @see #armForRelease */
    public void disarmForRelease() {
        this.armedForRelease = false;
    }

    private boolean armedForRelease;

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
        UIDocument window = document();
        if (window != null) window.focus().requestFocus(itemList.get(index));
    }

    private int focusedIndex() {
        UIDocument window = document();
        if (window == null) return -1;
        UINode focused = window.focus().focused();
        for (UINode el = focused; el != null; el = el.parent()) {
            int index = itemList.indexOf(el);
            if (index >= 0) return index;
        }
        return -1;
    }
}
