package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.Selection;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.text.wrap.ProjectedLines;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.List;

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
final class ViewCursorsPart extends EditorViewPart {

    /** Pooled — a multi-caret edit that shrinks back to one must not churn elements every keystroke. */
    private final List<UIElement> carets = new ArrayList<>();

    /**
     * Blink period in seconds — one full off-and-on cycle. Chromium's is 1.06s; the exact number is less
     * important than being slow enough not to distract and fast enough to read as a caret.
     */
    private float blinkPeriodSeconds = 1.06f;
    private float blinkClock;
    private boolean shown = true;

    ViewCursorsPart(TextEditor editor) {
        super(editor);
    }

    /** Seconds per full blink cycle; {@code 0} keeps the caret solid. */
    void setBlinkSeconds(float seconds) {
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
    void advanceBlink(float deltaSeconds) {
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
        for (UIElement caret : carets) {
            StyleGroup.importantPipeline(caret.getStyle().getGeneralGroup(), g -> g.opacity(opacity));
        }
    }

    /** Makes the caret solid again and restarts the cycle. Called from every edit and every caret move. */
    void restartBlink() {
        blinkClock = 0f;
        if (shown) return;
        shown = true;
        for (UIElement caret : carets) {
            StyleGroup.importantPipeline(caret.getStyle().getGeneralGroup(), g -> g.opacity(1f));
        }
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        if (lastViewLine < firstViewLine) return;   // nothing realised yet; updateWindow will call again
        int used = 0;
        for (Selection selection : editor.selections().all()) used = place(selection, used);
        // Anything left over from a larger set of carets is collapsed rather than removed: these are
        // pooled, and a multi-caret edit that shrinks back to one would otherwise churn elements every
        // keystroke.
        for (int i = used; i < carets.size(); i++) DecorationPool.hide(carets.get(i));
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
    private int place(Selection selection, int index) {
        float height = editor.lineHeight();
        final float ink = editor.textHeight();
        final float caretWidth = Math.max(1f, editor.getStyle().getGeneralGroup().caretWidth());
        // LEFT affinity: a caret that arrived at a wrap point by moving forwards or by pressing End
        // belongs at the end of the line it came from, not blinking at the start of the next one. This is
        // the single most visible way a soft-wrap caret goes wrong, and the reason Affinity exists.
        ProjectedLines.ViewPosition view = editor.projections().toViewPosition(
                editor.buffer().document(), selection.head(), LineProjection.Affinity.LEFT);
        final float left = editor.codeLeftPad() + editor.xOfView(view.viewLine(), view.column())
                - caretWidth - editor.getScrollLeft();
        final float top = editor.textOriginY() + view.viewLine() * height + (height - ink) / 2f
                - editor.getScrollTop();

        UIElement caret = caretAt(index);
        StyleGroup.importantPipeline(caret.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(top).width(caretWidth).height(ink));
        return index + 1;
    }

    private UIElement caretAt(int index) {
        while (carets.size() <= index) {
            UIElement caret = new UIElement();
            caret.addClass(TextEditor.CARET_CLASS);
            caret.setHitTest(false);
            caret.markAsInternal();
            editor.textViewport().addInternalChild(caret);
            carets.add(caret);
        }
        return carets.get(index);
    }
}
