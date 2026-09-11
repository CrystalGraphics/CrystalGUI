package com.crystalgui.widget.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;

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

    @Nullable
    private UIElement base;

    /** Live claims, oldest first; the last one shows. */
    private final List<Claim> claims = new ArrayList<>();

    @Nullable
    private UIElement shown;

    /** What had focus before it entered the bar. @see #giveFocusBack */
    @Nullable
    private UIElement focusedOutside;

    /** For the registry: a bar with no base shows nothing until something claims it. */
    public ContextToolbar() {
        this(null);
    }

    public ContextToolbar(@Nullable UIElement base) {
        super(NAME);
        setBase(base);
        whileConnected(() -> document().focus().onDidChangeFocus.connect(this::focusMoved));
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

    private void adopt(UIElement page) {
        if (page.parent() == this) return;
        page.addClass(PAGE_CLASS);
        page.setDisplayed(false);
        append(page);
    }

    private void update() {
        UIElement next = claims.isEmpty() ? base : claims.get(claims.size() - 1).page;
        if (next == shown) return;
        UIElement leaving = shown;
        shown = next;
        if (leaving != null && holdsFocus(leaving)) giveFocusBack();
        if (leaving != null) leaving.setDisplayed(false);
        if (next != null) next.setDisplayed(true);
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
        if (now != null && !UINode.isShadowIncludingInclusiveAncestor(this, now)) focusedOutside = now;
    }

    private boolean isClaimed(UIElement page) {
        for (Claim claim : claims) {
            if (claim.page == page) return true;
        }
        return false;
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
