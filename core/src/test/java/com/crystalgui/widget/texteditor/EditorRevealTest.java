package com.crystalgui.widget.texteditor;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.text.TextPoint;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Jumping into a file that has just been opened — Ctrl+B, Go to File with a line, a Problems row.
 *
 * <h3>Centring degrades into "put it at the very top", silently</h3>
 *
 * <p>{@code revealCaretCentred} scrolls to {@code caretY - viewportHeight / 2}. Every caller that jumps
 * into another file runs the moment the read lands, which is <b>before that tab has been through a layout
 * pass</b> — so the height is zero, the halving is zero, and the destination lands hard against the top
 * edge with all of its context below it.</p>
 *
 * <p>Nothing about that looks broken from the outside: the caret really is on the right line and the file
 * really did open. It reads as the centring never having been written.</p>
 */
public class EditorRevealTest extends EditorTestBase {

    /** Long enough that the middle of the file is nowhere near either end's clamp. */
    private static String lines() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) text.append("line ").append(i).append('\n');
        return text.toString();
    }

    private static final TextPoint DEEP = new TextPoint(120, 0);

    /** An editor built exactly as {@link EditorTestBase#build} does, but NOT yet attached. */
    private TextEditor detached(String text) {
        TextEditor made = new TextEditor(text);
        made.layout(l -> l.width(300).height(120));
        made.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        return made;
    }

    private void attachAndSettle(TextEditor made) {
        UIElement root = new UIElement().layout(l -> l.width(300).height(200));
        root.append(made);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        input = document.input();
        settle();
    }

    /**
     * <b>A jump issued before layout ends up where a jump issued after layout would.</b>
     *
     * <p>Stated as an equivalence rather than as an arithmetic expectation on purpose: what "centred"
     * comes to in pixels depends on the line height, the top padding and whether a horizontal bar is
     * showing, and a test that recomputed that would be asserting its own copy of the formula. The
     * property that matters is that <em>when</em> the jump arrives cannot change where it lands.</p>
     */
    @Test
    public void aJumpBeforeLayoutLandsWhereALaterJumpWould() {
        // The reference: laid out first, jumped afterwards. This path always worked.
        TextEditor late = build(lines());
        late.revealAt(DEEP);
        settle();
        float expected = late.scrollTop();

        // The reported case: the jump arrives while the tab is still being opened.
        TextEditor early = detached(lines());
        early.revealAt(DEEP);
        attachAndSettle(early);

        assertTrue("the reference did not scroll at all, so this compares two zeroes", expected > 0f);
        assertEquals("a jump into a freshly opened file landed somewhere else entirely",
                expected, early.scrollTop(), 1.0f);
    }

    /**
     * <b>...and the reference really is centred, not merely somewhere.</b>
     *
     * <p>Guards the test above from passing because BOTH editors were wrong in the same way. A minimal
     * scroll to a line below the viewport puts it against the <em>bottom</em> edge — the smallest scroll
     * that shows it at all — and centring must go further than that.</p>
     *
     * <p>Only the lower bound is asserted. The upper one — "and not so far that the line is at the TOP",
     * which is the actual defect — needs the viewport height and the line height to state, and both are
     * package-private on purpose. Writing it here would mean copying {@code revealCaretCentred}'s
     * arithmetic into the test and asserting the formula against itself. The equivalence above is what
     * pins the defect; this pins that the thing it is equivalent TO is worth landing on.</p>
     */
    @Test
    public void centringGoesFurtherThanTheSmallestScrollThatWouldDo() {
        TextEditor centred = build(lines());
        centred.revealAt(DEEP);
        settle();
        float middle = centred.scrollTop();

        TextEditor minimal = build(lines());
        minimal.revealAt(DEEP);
        settle();
        // Back to the top, then the SMALLEST scroll that brings the caret's line into view again.
        minimal.scrollTo(minimal.scrollLeft(), 0f);
        settle();
        minimal.revealCaret();
        settle();

        assertTrue("centring scrolled no further than the minimum that shows the line, so the "
                        + "destination sits against an edge: centred=" + middle
                        + " minimal=" + minimal.scrollTop(),
                middle > minimal.scrollTop());
    }
}
