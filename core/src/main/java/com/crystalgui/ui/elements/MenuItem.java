package com.crystalgui.ui.elements;

import com.crystalgui.ui.UIElement;
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
 *
 * <h3>Selection mark</h3>
 * <p>Every item carries a {@link #MARK_CLASS} pre-icon, unconditionally — the same "always in the tree,
 * opacity does the toggling" shape {@link Checkbox}'s own mark uses, driven by the standard {@code
 * :checked} pseudo-class ({@link #isChecked()} returns {@link #isSelected()}). It costs nothing when
 * unused: the base sheet gives it zero width outside a selectable context, so an ordinary context-menu
 * row is unaffected. {@link Dropdown} is what actually calls {@link #setSelected}, to mark which option
 * is the current value — see {@code dropdown .__menu__ menuitem .__mark__} in default.css for the
 * reserved gutter and checkmark shape.</p>
 */
public class MenuItem extends Button {

    /** On an item that owns a submenu, so a theme can lay it out differently — label left, arrow right. */
    public static final String HAS_SUBMENU_CLASS = "__has-submenu__";
    /** On the arrow itself. A theme can replace it wholesale via {@link Button#setPostIcon}. */
    public static final String SUBMENU_ARROW_CLASS = "__submenu-arrow__";
    /** On the selection checkmark. See the class javadoc's "Selection mark" section. */
    public static final String MARK_CLASS = "__mark__";

    /**
     * On a row whose {@code :checked} state is meant to be <b>visible</b> — a toggle in a context menu.
     *
     * <p>Opt-in rather than automatic, because the base sheet gives {@link #MARK_CLASS} zero width
     * outside a selectable context on purpose: reserving a checkmark gutter on every context-menu row
     * would indent labels for a mark that row can never show. A {@link Dropdown}'s menu is selectable
     * wholesale and gets the gutter for free; a lone toggle sitting among ordinary rows has to say so.</p>
     */
    public static final String CHECKABLE_CLASS = "__checkable__";

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

    private boolean selected = false;

    public MenuItem(String label) {
        super(label);
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);

        UIElement mark = new UIElement();
        mark.addClass(MARK_CLASS);
        mark.setHitTest(false);
        setPreIcon(mark);

        // PRESS-DRAG-RELEASE. Button already fires on a release whose press landed here; this adds the
        // release whose press landed on the MENU BAR TITLE that opened the menu, which is the gesture
        // every native menu bar has and the one isWasPressTarget() would otherwise refuse.
        //
        // Guarded on !isWasPressTarget() so an ordinary click cannot activate twice, and the arming is
        // one-shot so a later click on a menu that happens to still be open is unaffected. @see
        // Menu#armForRelease
        attachDefaultListener(onMouseUp, (element, event) -> {
            if (event.isWasPressTarget() || !isEnabled()) return;
            Menu owner = owningMenu();
            if (owner == null || !owner.isArmedForRelease()) return;
            owner.disarmForRelease();
            onPressed.emit();
        });
    }

    /** The menu this row belongs to, or null while it is unparented. */
    @Nullable
    private Menu owningMenu() {
        for (UIElement element = getParent(); element != null; element = element.getParent()) {
            if (element instanceof Menu menu) return menu;
        }
        return null;
    }

    /** On the trailing label showing this item's keystroke, so a theme can dim it. */
    public static final String ACCELERATOR_CLASS = "__accelerator__";

    @Nullable
    private UIText accelerator;

    /**
     * Shows the keystroke that runs this item, in the trailing slot.
     *
     * <p><b>The menu is where a keyboard shortcut is learned.</b> Every IDE puts the accelerator here for
     * that reason and no other — nobody reads a keymap file. Passing {@code null} clears it, so an item
     * whose command loses its binding stops advertising one.</p>
     *
     * <p>Rendered from {@code Keymap.acceleratorFor}, which resolves outward from the element the menu was
     * opened on. That is what makes it correct for a scoped binding: the graph's Delete and an
     * application-wide Delete are different chords and each item shows the one that would actually
     * fire.</p>
     */
    public MenuItem setAccelerator(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            if (accelerator != null) {
                accelerator.setText("");
                // NOT removed from the tree: an item's accelerator comes and goes with its binding, and
                // removing a POST-ICON re-runs Button's slot bookkeeping for a label that is about to come
                // back. An empty UIText measures zero.
            }
            return this;
        }
        if (accelerator == null) {
            accelerator = new UIText(text);
            accelerator.addClass(ACCELERATOR_CLASS);
            accelerator.setHitTest(false);
            setPostIcon(accelerator);
        } else {
            accelerator.setText(text);
        }
        return this;
    }

    void setSubmenu(@Nullable Menu submenu) {
        this.submenu = submenu;
    }

    /** Whether activating this opens a submenu rather than dismissing the menu. */
    public boolean hasSubmenu() {
        return submenu != null;
    }

    /** Whether this row represents the current value of whatever it belongs to — see "Selection mark"
     * above. Ignored/unused outside {@link Dropdown}. */
    public boolean isSelected() {
        return selected;
    }

    /**
     * Makes this row's checked state visible. @see #CHECKABLE_CLASS
     *
     * <p>Independent of {@link #setSelected}: one says the mark <em>can</em> show, the other whether it
     * currently does. Separating them is what lets a toggle reserve its gutter while switched off, so the
     * label does not shift sideways the first time it is checked.</p>
     */
    public MenuItem setCheckable(boolean value) {
        if (value) addClass(CHECKABLE_CLASS);
        else removeClass(CHECKABLE_CLASS);
        return this;
    }

    public MenuItem setSelected(boolean value) {
        if (this.selected == value) return this;
        this.selected = value;
        onStyleChanged();
        invalidateStyleMatch();
        return this;
    }

    @Override
    public boolean isChecked() {
        return selected;
    }
}
