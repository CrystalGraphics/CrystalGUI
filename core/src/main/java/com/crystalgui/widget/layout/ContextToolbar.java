package com.crystalgui.widget.layout;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgKeyCodes;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Popover;

/**
 * <b>One toolbar row whose contents follow what is going on</b> — Photoshop's options bar, Inkscape's
 * tool controls, Blender's tool header, Godot's context toolbar.
 *
 * <p>A base page shows while nothing claims the row. Whatever has options of its own for the moment — a
 * tool, a modal gesture, an inline editor — claims it, and the newest live claim is what shows.</p>
 *
 * <pre>{@code
 * ContextToolbar bar = new ContextToolbar(documentToolbar);
 * column.append(bar, canvas);
 *
 * Disposable claim = bar.claim(transformOptions);   // shown in the toolbar's place
 * claim.dispose();                                  // and the toolbar is back
 * }</pre>
 *
 * <p>Claims stack, the way Blender's modal header text covers the tool header and gives it back:</p>
 *
 * <pre>{@code
 * Disposable tool = bar.claim(brushOptions);
 * Disposable drag = bar.claim(dragReadout);   // shows
 * drag.dispose();                             // brushOptions again
 * }</pre>
 *
 * <p>A surface does this for its tools in one line, and each tool's {@code options()} page then comes and
 * goes with the tool:</p>
 *
 * <pre>{@code
 * surface.modes().showOptionsIn(bar);
 * }</pre>
 *
 * <h3>Groups, and a page wider than the row</h3>
 *
 * <p>A page groups its items with {@link #separator()}, Photoshop's hairline and Qt's
 * {@code addSeparator}:</p>
 *
 * <pre>{@code
 * page.append(x, y, ContextToolbar.separator(), width, height);
 * }</pre>
 *
 * <p>What does not fit folds from the END behind a <b>»</b> at the row's end, and » opens it in a popover
 * under it — WinForms' {@code ToolStrip} overflow and Qt's toolbar extension, which both do the same. So a
 * page puts its least-used items last. A folded item is hidden, not clipped, so Tab cannot land on it;
 * what it holds goes with it into the popover and comes back when that closes. As in a menu, a separator
 * never ends the row and never opens the popover.</p>
 *
 * <h3>A field on the row is a stop, not a home</h3>
 *
 * <p>Enter in a page's text field lands it and Escape drops what was typed, and either hands the keyboard
 * back to whatever had it before — Photoshop's options bar, where Enter applies a field and the next Enter
 * is the tool's. A tool that also wants Enter therefore leaves a focused field alone.</p>
 *
 * <ul>
 *   <li><b>The row's height is the sheet's, never a page's.</b> {@code contexttoolbar} has a definite
 *       height and clips, so a page swapping in cannot move what is under the bar.</li>
 *   <li><b>A page is added on its first claim and kept</b>, hidden between claims, so its fields keep what
 *       they hold. Build a page once and claim the same element each time.</li>
 *   <li><b>Pages arrive through {@link #claim} and {@link #setBase}.</b> A child appended directly is not a
 *       page and is never hidden.</li>
 *   <li><b>A page hidden while it holds focus gives it back</b> to whatever had focus before it entered the
 *       bar. A hidden field left focused would take the keyboard with nothing on screen.</li>
 * </ul>
 */
public class ContextToolbar extends UIElement {

    public static final Name NAME = Name.of("contexttoolbar");

    /** On every page the bar holds. */
    public static final String PAGE_CLASS = "__context-page__";

    /** The » at the row's end, shown only while the page does not fit. */
    public static final String OVERFLOW_CLASS = "__context-overflow__";

    /** The popover » opens, holding what was folded. */
    public static final String OVERFLOW_PANEL_CLASS = "__context-overflow-panel__";

    /** A {@link #separator()}, and the hairline inside it. */
    public static final String SEPARATOR_CLASS = "__context-separator__";

    public static final String SEPARATOR_LINE_CLASS = "__context-separator-line__";

    @Nullable
    private UIElement base;

    /** Live claims, oldest first; the last one shows. */
    private final List<Claim> claims = new ArrayList<>();

    @Nullable
    private UIElement shown;

    /** What had focus before it entered the bar. @see #giveFocusBack */
    @Nullable
    private UIElement focusedOutside;

    private final Button overflow = new Button("»");

    private final Popover panel = new Popover();

    /** The shown page's items that are folded away, in page order. */
    private final List<UIElement> folded = new ArrayList<>();

    /** Where each item in the open panel came from, so it goes back to the same place. */
    private final List<Origin> lent = new ArrayList<>();

    /** Each item's width when it was last on the row — a folded item has no box to ask. */
    private final Map<UIElement, Float> widths = new IdentityHashMap<>();

    /** The space between two items on the row, as last measured. */
    private float gap;

    private boolean overflowing;

    /** For the registry: a bar with no base shows nothing until something claims it. */
    public ContextToolbar() {
        this(null);
    }

    public ContextToolbar(@Nullable UIElement base) {
        super(NAME);
        overflow.addClass(OVERFLOW_CLASS);
        overflow.setDisplayed(false);
        overflow.onPressed.connect(this::toggleOverflow);
        append(overflow);
        panel.addClass(OVERFLOW_PANEL_CLASS);
        panel.onClosed.connect(this::takeBackLent);
        // ON THE CAPTURE PHASE, so the field never sees the key: a field's own Enter would land it and
        // keep focus, and its Escape keeps focus too.
        events.getGroup(KeyboardEvent.Down.class).attachListener((element, event) -> fieldKey(event), true, false);
        panel.events.getGroup(KeyboardEvent.Down.class).attachListener((element, event) -> fieldKey(event), true, false);
        setBase(base);
        whileConnected(() -> document().focus().onDidChangeFocus.connect(this::focusMoved));
    }

    /**
     * A hairline between two groups of a page's items.
     *
     * <p>The spacing is inside its own width rather than in margins, so the room the bar folds against is
     * exactly the room it takes.</p>
     */
    public static UIElement separator() {
        UIElement separator = new UIElement().addClass(SEPARATOR_CLASS);
        separator.append(new UIElement().addClass(SEPARATOR_LINE_CLASS));
        return separator;
    }

    /** After layout, because what fits is read off the items' laid-out widths. */
    @Override
    protected void connected() {
        super.connected();
        document().animation().afterLayout(this, delta -> {
            fit();
            return true;
        });
    }

    /** What shows while nothing claims the bar — usually the editor's own toolbar. Replaces any previous. */
    public ContextToolbar setBase(@Nullable UIElement page) {
        if (page == base) return this;
        UIElement old = base;
        base = page;
        if (page != null) adopt(page);
        update();
        if (old != null && !isClaimed(old)) remove(old);
        return this;
    }

    @Nullable
    public UIElement base() {
        return base;
    }

    /** The page on show: the newest live claim's, else the base. */
    @Nullable
    public UIElement shown() {
        return shown;
    }

    /**
     * Shows {@code page} in place of whatever is showing, until the returned handle is disposed.
     *
     * @return releases the claim; disposing it twice is harmless
     */
    public Disposable claim(UIElement page) {
        Objects.requireNonNull(page, "page");
        adopt(page);
        Claim claim = new Claim(page);
        claims.add(claim);
        update();
        return claim;
    }

    /** Whether the shown page is wider than the row, so some of it is behind the ». */
    public boolean isOverflowing() {
        return overflowing;
    }

    /** The popover » opens. */
    public Popover overflowPanel() {
        return panel;
    }

    /** The » itself. */
    public Button overflowButton() {
        return overflow;
    }

    /** Opens what is folded away under the », or closes it again. Does nothing while everything fits. */
    public void toggleOverflow() {
        if (panel.isOpen()) {
            panel.hide();
            return;
        }
        for (UIElement item : folded) {
            // A SEPARATOR NEVER OPENS THE POPOVER, as one never opens a menu: it stays folded behind.
            if (lent.isEmpty() && isSeparator(item)) continue;
            UINode origin = item.parent();
            if (origin != null) lent.add(new Origin(item, origin, origin.indexOf(item)));
        }
        if (lent.isEmpty()) return;
        for (Origin each : lent) {
            each.parent.remove(each.item);
            each.item.setDisplayed(true);
            panel.append(each.item);
        }
        panel.showFor(overflow, overflow);
    }

    /** Puts every lent item back where it came from, still folded; the next {@link #fit} decides. */
    private void takeBackLent() {
        // IN THE ORDER THEY LEFT, which is page order, so each index is right when it is used.
        for (Origin each : lent) {
            panel.remove(each.item);
            each.item.setDisplayed(false);
            each.parent.insertAt(Math.min(each.index, each.parent.children().size()), each.item);
        }
        lent.clear();
    }

    /** Enter lands the focused field and Escape drops what was typed in it; either hands the keyboard back. */
    private void fieldKey(KeyboardEvent.Down event) {
        int key = event.getKeyCode();
        boolean enter = key == CgKeyCodes.KEY_RETURN || key == CgKeyCodes.KEY_NUMPADENTER;
        if (!enter && key != CgKeyCodes.KEY_ESCAPE) return;
        TextField field = focusedField();
        if (field == null) return;
        if (enter) field.commit();
        else field.setText(field.getValue());
        if (panel.isOpen()) panel.hide();
        giveFocusBack();
        event.stopPropagation();
    }

    /** The text field holding focus, when it is one of this bar's — on the row or lent to the popover. */
    @Nullable
    private TextField focusedField() {
        UIDocument window = document();
        UIElement focused = window == null ? null : window.focus().focused();
        if (!(focused instanceof TextField field)) return null;
        return UINode.isShadowIncludingInclusiveAncestor(this, field)
                || UINode.isShadowIncludingInclusiveAncestor(panel, field) ? field : null;
    }

    private void adopt(UIElement page) {
        if (page.parent() == this) return;
        page.addClass(PAGE_CLASS);
        page.setDisplayed(false);
        insertAt(indexOf(overflow), page);
    }

    private void update() {
        UIElement next = claims.isEmpty() ? base : claims.get(claims.size() - 1).page;
        if (next == shown) return;
        UIElement leaving = shown;
        // THE LEAVING PAGE GOES BACK WHOLE, so it is intact when it is shown again.
        if (panel.isOpen()) panel.hide();
        unfoldAll();
        shown = next;
        if (leaving != null && holdsFocus(leaving)) giveFocusBack();
        if (leaving != null) leaving.setDisplayed(false);
        if (next != null) next.setDisplayed(true);
    }

    /**
     * Folds from the end whatever the row has no room for, and unfolds what it now has room for.
     *
     * <p>Measured against the bar rather than the page, so the room the » takes does not feed back into
     * whether it is needed. Frozen while the panel is open: what is in it stays there until it closes.</p>
     */
    private void fit() {
        if (panel.isOpen()) return;
        UIElement page = shown;
        Box bar = box();
        Box pageBox = page == null ? null : page.box();
        if (bar == null || pageBox == null) return;

        List<UIElement> items = itemsOf(page);
        float room = bar.contentBoxWidth() - (pageBox.width() - pageBox.contentBoxWidth());
        boolean over = lineWidth(items, items.size()) > room + 0.5f;
        if (over != overflowing) {
            overflowing = over;
            overflow.setDisplayed(over);
        }
        // WHAT THE PAGE HAS NOW, which is the room less the » once it has a box. The pass that shows it
        // lays it out, and the next pass -- the same frame -- folds against its real width.
        float available = over ? pageBox.contentBoxWidth() : room;
        int keep = items.size();
        while (keep > 0 && lineWidth(items, keep) > available + 0.5f) keep--;
        // A SEPARATOR NEVER ENDS THE ROW, as one never ends a menu: it folds with what followed it.
        while (keep > 0 && keep < items.size() && isSeparator(items.get(keep - 1))) keep--;
        for (int i = 0; i < items.size(); i++) setFolded(items.get(i), i >= keep);
    }

    /** The page's items the bar decides about: those on show and those it folded, in page order. */
    private List<UIElement> itemsOf(UIElement page) {
        List<UIElement> items = new ArrayList<>();
        Box previous = null;
        float smallest = Float.MAX_VALUE;
        for (UIElement item : page.composedChildren()) {
            Box box = item.box();
            if (box != null) {
                widths.put(item, box.width());
                // THE SMALLEST GAP, because an auto margin -- a button pushed to the far end -- widens
                // the space in front of it and is no part of what the row needs.
                if (previous != null) smallest = Math.min(smallest, box.x() - previous.x() - previous.width());
                previous = box;
                items.add(item);
            } else if (folded.contains(item)) {
                items.add(item);
            }
        }
        if (smallest != Float.MAX_VALUE && smallest >= 0f) gap = smallest;
        return items;
    }

    /** The first {@code count} items side by side. */
    private float lineWidth(List<UIElement> items, int count) {
        float width = 0f;
        for (int i = 0; i < count; i++) width += widths.getOrDefault(items.get(i), 0f);
        return width + gap * Math.max(0, count - 1);
    }

    private void setFolded(UIElement item, boolean fold) {
        if (folded.contains(item) == fold) return;
        if (fold) folded.add(item);
        else folded.remove(item);
        item.setDisplayed(!fold);
        // AND IN PAGE ORDER, which is what the panel shows them in.
        if (fold) folded.sort((a, b) -> Integer.compare(indexIn(a), indexIn(b)));
    }

    private static int indexIn(UIElement item) {
        UINode parent = item.parent();
        return parent == null ? -1 : parent.indexOf(item);
    }

    private static boolean isSeparator(UIElement item) {
        return item.hasClass(SEPARATOR_CLASS);
    }

    private void unfoldAll() {
        for (UIElement item : folded) item.setDisplayed(true);
        folded.clear();
        widths.clear();
        if (overflowing) {
            overflowing = false;
            overflow.setDisplayed(false);
        }
    }

    private boolean holdsFocus(UIElement page) {
        UIDocument window = document();
        UIElement focused = window == null ? null : window.focus().focused();
        return focused != null && UINode.isShadowIncludingInclusiveAncestor(page, focused);
    }

    /** A return rather than a navigation, so no ring and no scroll. With nowhere to return to, focus clears. */
    private void giveFocusBack() {
        UIDocument window = document();
        if (window == null) return;
        UIElement back = focusedOutside;
        if (back != null && back.document() == window && window.focus().focusable(back)) {
            window.focus().requestPointerFocus(back);
        } else {
            window.focus().clear();
        }
    }

    private void focusMoved(@Nullable UIElement now) {
        if (now != null && !UINode.isShadowIncludingInclusiveAncestor(this, now)
                && !UINode.isShadowIncludingInclusiveAncestor(panel, now)) {
            focusedOutside = now;
        }
    }

    private boolean isClaimed(UIElement page) {
        for (Claim claim : claims) {
            if (claim.page == page) return true;
        }
        return false;
    }

    /** An item lent to the panel, and where it goes back to — a page's own parts included. */
    private record Origin(UIElement item, UINode parent, int index) {
    }

    private final class Claim implements Disposable {

        private final UIElement page;

        Claim(UIElement page) {
            this.page = page;
        }

        @Override
        public void dispose() {
            if (claims.remove(this)) update();
        }
    }
}
