package com.crystalgui.widget.texteditor.part;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.Selection;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.text.wrap.ProjectedLines;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.texteditor.TextEditor;
import dev.vfyjxf.taffy.style.TaffyPosition;


/**
 * One element per caret, plus the blink.
 *
 * <p>Ported in shape from {@code browser/viewParts/viewCursors/} (MIT), which likewise owns both the
 * cursors' placement and their blink clock.</p>
 *
 * <p>The carets are ordinary internal children in document coordinates rather than something painted in
 * {@code paintSelf}. That is what makes them scroll for free — a scroll is a pose translate applied to
 * children at paint time, so anything painted by the view itself would have to subtract the scroll offset
 * by hand — and it is what lets a theme colour them, since the engine's rule is that no widget writes a
 * colour in Java.</p>
 */
public final class ViewCursorsPart extends EditorViewPart {

    /** Pooled — a multi-caret edit that shrinks back to one must not churn elements every keystroke. */
    private final DecorationPool carets;

    /**
     * Blink period in seconds — one full off-and-on cycle. Chromium's is 1.06s; the exact number is less
     * important than being slow enough not to distract and fast enough to read as a caret.
     */
    private float blinkPeriodSeconds = 1.06f;
    private float blinkClock;
    private boolean shown = true;

    public ViewCursorsPart(TextEditor editor) {
        super(editor);
        this.carets = new DecorationPool(editor::linesLayer, TextEditor.CARET_CLASS, false);
    }

    /** Seconds per full blink cycle; {@code 0} keeps the caret solid. */
    public void setBlinkSeconds(float seconds) {
        this.blinkPeriodSeconds = Math.max(0f, seconds);
        restartBlink();
    }

    /**
     * Advances the blink and shows or hides the carets.
     *
     * <p>Hidden outright when the editor is not focused: a caret in an unfocused editor claims a text
     * cursor that no keystroke would reach. And the phase is <b>reset by any edit or caret move</b>
     * ({@link #restartBlink()}), so the caret is always solid at the instant you type — one that happened
     * to be in its off phase would otherwise vanish exactly when it is being looked for.</p>
     */
    public void advanceBlink(float deltaSeconds) {
        boolean focused = editor.isFocused();
        boolean wanted;
        if (!focused) {
            wanted = false;
        } else if (blinkPeriodSeconds <= 0f) {
            wanted = true;
        } else {
            blinkClock = (blinkClock + deltaSeconds) % blinkPeriodSeconds;
            wanted = blinkClock < blinkPeriodSeconds / 2f;
        }
        if (wanted == shown) return;
        shown = wanted;
        final float opacity = wanted ? 1f : 0f;
        for (UINode caret : carets.all()) {
            StyleGroup.inlinePipeline(caret.getStyle().getGeneralGroup(), g -> g.opacity(opacity));
        }
    }

    /** Makes the caret solid again and restarts the cycle. Called from every edit and every caret move. */
    public void restartBlink() {
        blinkClock = 0f;
        if (shown) return;
        shown = true;
        for (UINode caret : carets.all()) {
            StyleGroup.inlinePipeline(caret.getStyle().getGeneralGroup(), g -> g.opacity(1f));
        }
    }

    @Override
    public void render(int firstViewLine, int lastViewLine) {
        carets.beginPass();
        if (!hasWindow(firstViewLine, lastViewLine)) {
            carets.endPass();
            return;
        }
        for (Selection selection : editor.selections().all()) place(selection);
        // Anything left over from a larger set of carets is collapsed rather than removed: these are
        // pooled, and a multi-caret edit that shrinks back to one would otherwise churn elements every
        // keystroke.
        carets.endPass();
    }

    /**
     * Places one caret.
     *
     * <p><b>The caret's right edge sits on the character boundary</b> — it does not start there, and it
     * does not straddle it. A boundary in a bitmap font is where the <em>next</em> glyph's ink begins: the
     * advance is ink plus trailing space and there is no left side bearing, so the whole of the clear gap
     * lies to the left of it. Drawing rightwards covers the next glyph's first ink column, and centring is
     * worse still — at uiScale 2 a 1px caret is two physical pixels and a Minecraft {@code i} is barely
     * wider than that, so a centred caret buries the letter.</p>
     */
    private void place(Selection selection) {
        float height = editor.lineHeight();
        final float ink = editor.textHeight();
        final float caretWidth = widthFor(selection);
        // LEFT affinity: a caret that arrived at a wrap point by moving forwards or by pressing End
        // belongs at the end of the line it came from, not blinking at the start of the next one. This is
        // the single most visible way a soft-wrap caret goes wrong, and the reason Affinity exists.
        ProjectedLines.ViewPosition view = editor.projections().toViewPosition(
                editor.buffer().document(), selection.head(), LineProjection.Affinity.LEFT);
        // THE SHIFT IS THE BAR'S, not every caret's. A line caret is drawn just left of the boundary for
        // the bitmap-font reason above; a block and an underline COVER the character at the caret, so
        // shifting them by their own width would put them over the character before it.
        final float boundaryShift = editor.getCaretStyle() == TextEditor.CaretStyle.LINE ? caretWidth : 0f;
        // DOCUMENT COORDINATES -- TextEditor.linesLayer() carries the horizontal offset.
        final float left = editor.codeLeftPad() + editor.xOfView(view.viewLine(), view.column())
                - boundaryShift;
        final float top = editor.topOfViewLine(view.viewLine()) + (height - ink) / 2f;

        final float caretHeight = editor.getCaretStyle() == TextEditor.CaretStyle.UNDERLINE
                ? Math.max(1f, editor.getStyle().getGeneralGroup().caretWidth()) : ink;
        final float caretTop = editor.getCaretStyle() == TextEditor.CaretStyle.UNDERLINE
                ? top + ink - caretHeight : top;

        UINode caret = carets.next();
        StyleGroup.inlinePipeline(caret.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(caretTop).width(caretWidth).height(caretHeight));
    }

    /**
     * How wide the caret is — the whole of what a caret STYLE amounts to here.
     *
     * <h3>A block is one character wide, and that character has to be measured</h3>
     *
     * <p>Not the space advance and not a fixed fraction of the font size: this editor draws proportional
     * fonts, so a block over {@code i} and a block over {@code W} are different widths and a single
     * number is wrong for one of them. {@code widthOf} is the same prefix measurement the caret's own x
     * comes from, which is what keeps the block sitting exactly on the glyph rather than beside it.</p>
     *
     * <p>At the end of a line there is no character to cover, so a block falls back to a line — a block
     * drawn over nothing is a rectangle floating past the last glyph.</p>
     */
    private float widthFor(Selection selection) {
        float line = Math.max(1f, editor.getStyle().getGeneralGroup().caretWidth());
        if (editor.getCaretStyle() == TextEditor.CaretStyle.LINE) return line;

        int caret = selection.head();
        int row = editor.buffer().offsetToPoint(caret).row();
        int column = caret - editor.buffer().document().lineStartOffset(row);
        String text = editor.buffer().line(row);
        if (column >= text.length()) return line;
        float advance = editor.widthOf(row, column + 1) - editor.widthOf(row, column);
        return advance > 0f ? advance : line;
    }

}
