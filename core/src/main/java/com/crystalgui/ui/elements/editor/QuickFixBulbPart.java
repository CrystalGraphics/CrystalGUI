package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * A bulb in the gutter on the caret's row when there is something to offer there — IntelliJ's.
 *
 * <h3>Why it exists at all</h3>
 *
 * <p>Alt+Enter and the error stripe between them cover everything except the case that matters most: a
 * problem on the line you are already looking at. The stripe answers "where are the problems in this
 * file", and the key answers "show me the actions" — but only if you already suspect there are any. A
 * bulb is the only affordance that says <em>here, now</em>, and without one the whole feature is
 * discoverable by prior knowledge alone.</p>
 *
 * <h3>It is driven by the DIAGNOSTIC, not by the action list</h3>
 *
 * <p>The obvious rule is "show it when there are actions", and it is the wrong one: actions come from an
 * engine asynchronously, so a bulb keyed on them would fire a request per frame for the caret's row and
 * flicker on whatever came back. Keyed on whether a diagnostic covers the caret it is synchronous — the
 * tracked ranges are already in the buffer — and it costs nothing per frame.</p>
 *
 * <p>That is honest rather than approximate, and only because of what the merge guarantees: every
 * diagnostic offers at least the shape-derived actions, so a bulb over a problem can never promise a list
 * that turns out to be empty. If that ever stops being true, this rule has to change with it.</p>
 */
final class QuickFixBulbPart extends EditorViewPart {

    static final String BULB_CLASS = "__quick-fix-bulb__";

    private UIElement bulb;

    QuickFixBulbPart(TextEditor editor) {
        super(editor);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        if (!editor.isGutterVisible() || editor.diagnosticsAt(editor.getCaret()).isEmpty()) {
            hide();
            return;
        }
        int viewLine = editor.viewLineOf(editor.getCaret(),
                com.crystalgui.text.wrap.LineProjection.Affinity.LEFT);
        if (viewLine < firstViewLine || viewLine > lastViewLine) {
            // The caret is scrolled off. Hidden rather than clamped to an edge: a bulb pinned to the top
            // of the gutter would claim there is something to fix on a row that is merely visible.
            hide();
            return;
        }
        bulbElement().setDisplayed(true);

        float height = editor.lineHeight();
        // IN THE FOLD COLUMN, which is the gutter's own decoration lane -- the same box the fold arrows
        // live in, so the bulb lines up with them and needs no geometry of its own.
        final float top = editor.textOriginY() + viewLine * height - editor.getScrollTop();
        final float width = editor.gutterFoldWidth();
        StyleGroup.defaultPipeline(bulbElement().getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(top).width(width).height(height));
    }

    /**
     * <b>{@code display}, not a collapsed box</b> — and it has to be, which is a trap for the next
     * pooled decoration that gets a size from the sheet.
     *
     * <p>{@code DecorationPool.hide} writes {@code width: 0; height: 0} at <b>DEFAULT</b> origin, which
     * is how every other part here retires an element. It cannot work for this one: the bulb's size comes
     * from a {@code .__quick-fix-bulb__} rule at <b>STYLESHEET</b> origin, and the cascade ranks that
     * above DEFAULT — so the write was a no-op and the bulb stayed 12×12 for ever. Since the render path
     * returns early once hidden, its {@code top} also stopped being updated, and it sat frozen on the last
     * row it had been valid for: a bulb that follows you around the file, claiming a fix on whatever line
     * it happens to be next to.</p>
     *
     * <p>{@code setDisplayed} writes at IMPORTANT, which outranks the sheet, so this is the one hide that
     * survives having a styled size. The squiggle bands are unaffected because nothing in CSS gives them
     * one — their geometry is entirely Java's, which is why the pool's idiom works there.</p>
     */
    private void hide() {
        if (bulb != null) bulb.setDisplayed(false);
    }

    private UIElement bulbElement() {
        if (bulb == null) {
            bulb = new UIElement();
            bulb.addClass(BULB_CLASS);
            bulb.markAsInternal();
            // THE CARET'S ROW IS READ AT PRESS TIME, never captured: this element outlives every caret
            // position it is ever shown for, so a listener holding the row it was built for would open
            // the actions for wherever the caret happened to be the first time.
            bulb.onMouseDown.attachListener((element, event) -> {
                editor.showCodeActionsAt(editor.getCaret());
                event.stopPropagation();
            }, false, false);
            editor.foldColumn().addInternalChild(bulb);
        }
        return bulb;
    }
}
