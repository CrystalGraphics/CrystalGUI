package com.crystalgui.widget.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.text.FontWeight;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.text.TextRange;
import org.junit.Test;

/**
 * <b>{@link UIText} answers the layout engine, and does not write to the cascade to do it</b> — the
 * four halves of the D15 merge that are silent when broken.
 *
 * <h3>Why these four</h3>
 *
 * <p>The merge deleted about four hundred lines whose whole job was to survive a layout engine that
 * would not ask a text leaf its size. What replaced them is one {@code measure} and one style hook,
 * and each of the four things below fails <em>without any observable around it changing</em>: the
 * cascade is correct, the computed style is correct, the paragraph is correct, and the box is the
 * wrong size or the glyphs are the wrong shape. That is the class of defect the old file's own
 * history is made of, so it is what a test is worth writing for.</p>
 *
 * <p>What is deliberately NOT here: pixel widths. Every assertion is relational — this is taller than
 * that, this moved when that moved — because a font's exact advances are the shaper's business, and a
 * test that pins them fails on a legitimate font change while teaching nobody anything.</p>
 */
public class UITextMeasureTest extends UiDocumentTestBase {

    /**
     * Cascade, then lay out — never {@code layoutOnly()}.
     *
     * <p>{@code UIDocument.layout} deliberately does not run the cascade ("run style first", says its
     * own javadoc), and a highlight's style comes <em>from</em> the cascade: skip it and every
     * {@code ::highlight()} resolves to empty, which is indistinguishable from a theme that has no
     * rule for that name.</p>
     */
    private void settle() {
        document.update(W, H);
    }

    private static UIText wrapping(String content) {
        UIText text = new UIText(content);
        StyleGroup.inlinePipeline(text.getStyle().getGeneralGroup(),
                g -> g.whiteSpace(WhiteSpace.NORMAL));
        return text;
    }

    // ── 1. It is ASKED, rather than pushing an answer back ───────────────────

    /**
     * A wrapping paragraph is measured at the width it was given, and reports the height that needs.
     *
     * <p>The positive control for the whole merge. The old engine could not be asked at all — the
     * flex-wrap path handed a leaf {@code NaN} for its own width — so it laid out, re-measured
     * against the box that had settled, and pushed a height back at {@code IMPORTANT} origin hoping
     * to converge. If {@link UIText#measure} is never reached, this box is one line tall and nothing
     * anywhere complains.</p>
     */
    @Test
    public void aWrappingParagraphIsMeasuredAtTheWidthItWasGiven() {
        UIText text = wrapping("the quick brown fox jumps over the lazy dog and keeps on going");
        layout(text, l -> l.width(80f));
        document.append(text);
        settle();

        assertTrue("it was asked at all", !text.measuredAt().isEmpty());
        assertTrue("and asked at the width it was given -- not NaN, not zero: " + text.measuredAt(),
                text.measuredAt().stream().anyMatch(w -> w > 0f && w <= 80f));
        assertTrue("a 62-character sentence in 80px is several lines", boxOf(text).height() > 20f);
    }

    /**
     * The same sentence in a wider box is shorter — the counter-assertion.
     *
     * <p>A {@code measure} that ignored its constraint and always answered one line would satisfy the
     * test above (the box would simply be short) and fails here.</p>
     */
    @Test
    public void aWiderBoxIsAShorterParagraph() {
        String sentence = "the quick brown fox jumps over the lazy dog and keeps on going";
        UIText narrow = wrapping(sentence);
        UIText wide = wrapping(sentence);
        layout(narrow, l -> l.width(80f));
        layout(wide, l -> l.width(400f));
        document.append(narrow);
        document.append(wide);
        settle();

        assertTrue("more room is fewer lines: "
                        + boxOf(narrow).height() + " vs " + boxOf(wide).height(),
                boxOf(narrow).height() > boxOf(wide).height());
    }

    // ── 2. max-width is folded IN, because the engine clamps the RESULT ──────

    /**
     * A {@code max-width} constrains the measure, not merely the box that comes out of it.
     *
     * <p>The layout engine clamps a measured size against {@code max-width} <em>after</em> asking —
     * which for an ordinary box is the same thing and for a text leaf is not. Measured unbounded, the
     * paragraph is one line however long; the clamp then narrows the BOX, and the glyphs run out of
     * it. A tooltip laid its whole sentence on one line with {@code max-width: 170px} and
     * {@code white-space: normal} both perfectly correct, and nothing in the cascade wrong.</p>
     */
    @Test
    public void aMaxWidthConstrainsTheMeasureAndNotOnlyTheBox() {
        UIText text = wrapping("a tooltip sentence long enough that it has to wrap somewhere");
        // No width: free to be as wide as it likes, bounded only by the cap.
        layout(text, l -> l.maxWidth(170f));
        document.append(text);
        settle();
        Box box = boxOf(text);

        assertTrue("the box respects the cap: " + box.width(), box.width() <= 171f);
        assertTrue("and the TEXT wrapped inside it rather than running out of it: " + box.height(),
                box.height() > 20f);
    }

    // ── 3. A font change re-measures, and nothing else would ask ─────────────

    /**
     * Changing {@code font-size} re-measures — the replacement for four static property listeners.
     *
     * <p>{@code font-size} is a VISUAL property: {@code BoxStyle} never writes it, so a change marks
     * no layout dirty and the box would keep whatever the previous face measured. Silent, and it
     * shows as a label clipped or floating in space long after the size that caused it.</p>
     */
    @Test
    public void aFontSizeChangeReMeasures() {
        UIText text = new UIText("measure me");
        document.append(text);
        settle();
        float small = boxOf(text).height();

        StyleGroup.inlinePipeline(text.getStyle().getGeneralGroup(), g -> g.fontSize(32f));
        settle();

        assertTrue("a bigger face is a taller box: " + small + " -> " + boxOf(text).height(),
                boxOf(text).height() > small);
    }

    /**
     * ...and so does {@code font-weight} — asserted on the RE-SHAPE, not on a width.
     *
     * <p>Weight is carried on the SPANS rather than on the resolved family: synthesis is per span,
     * and a bold label resolves the same {@code CgFontFamily} instance a regular one does, so the
     * paragraph's own "has the family changed" check answers no. A hook watching only
     * {@code font-size} therefore leaves a bold label shaped regular, permanently.</p>
     *
     * <p><b>The idle half is the counter-assertion and is the load-bearing one.</b> "It re-measured"
     * alone passes against a node that re-measures on every pass — which is the old engine's
     * behaviour, is what the memo exists to prevent, and would make this whole file slower than what
     * it replaced. Bracketing the change with two settles that must cost nothing is what separates
     * an invalidation from an absence of one.</p>
     *
     * <p>Deliberately NOT asserted: that the box got wider. Whether the shaper's synthetic bold
     * changes advances is its business, and at this text and this width it does not move a wrap
     * point — so a width assertion would fail while the mechanism under test worked perfectly.</p>
     */
    @Test
    public void aFontWeightChangeReShapesAndAnIdleSettleDoesNot() {
        UIText text = new UIText("measure me at two weights");
        document.append(text);
        settle();
        int afterFirst = text.measuredAt().size();

        settle();
        settle();
        assertEquals("nothing changed, so nothing may be re-measured",
                afterFirst, text.measuredAt().size());

        StyleGroup.inlinePipeline(text.getStyle().getGeneralGroup(),
                g -> g.fontWeight(FontWeight.BOLD));
        settle();

        assertTrue("a weight change has to re-measure: still " + text.measuredAt(),
                text.measuredAt().size() > afterFirst);
        assertEquals("...and the new face has to reach the shaper as a span",
                1, text.styleSpanCount());
    }

    // ── 4. Ordinary labels stay off the spanned shaper ───────────────────────

    /**
     * A plain label produces NO style spans, and that is load-bearing rather than an optimisation.
     *
     * <p>A span boundary is a shaping-run boundary, so styled text loses the kerning across it and
     * sits a fraction of a pixel differently. Routing every label through a one-span document shifts
     * every label in the engine — invisibly, and everywhere at once.</p>
     */
    @Test
    public void anOrdinaryLabelIsShapedWithNoSpans() {
        UIText text = new UIText("plain");
        document.append(text);
        settle();

        assertEquals("nothing needs a span, so nothing gets one", 0, text.styleSpanCount());
    }

    /** The counter-assertion: a registered, STYLED highlight does reach the shaper. */
    @Test
    public void aHighlightedRangeIsShapedWithSpans() {
        document.styles().addStylesheet(StyleSheet.parse("::highlight(match) { color: #FF0000 }"));
        UIText text = new UIText("find the match here");
        document.append(text);
        text.highlights().add("match", TextRange.of(9, 14));
        settle();

        assertTrue("a resolved highlight has to reach the shaper: " + text.styleSpanCount(),
                text.styleSpanCount() > 0);
    }

    /**
     * <b>Withdrawing a highlight clears the band, and the clear is what a pooled row depends on.</b>
     *
     * <p>An unhighlighted label shapes as ONE run starting at character 0, and the band pass reads
     * {@code perChar[run.sourceStart()]} — so a single leftover entry at index 0 paints a band across
     * the WHOLE string. Rows are pooled, so this is not a corner: every explorer row that had ever
     * shown a search match went on banding whatever filename landed on it next, at full width, for a
     * query matching one file — with the registered range empty, the resolved style correct, and the
     * counter still reading "1 of 1". {@link UIText#highlightBandCount} is the only observable that
     * can see it; asserting the range or the computed style passes against the bug.</p>
     */
    @Test
    public void withdrawingAHighlightClearsItsBand() {
        document.styles().addStylesheet(
                StyleSheet.parse("::highlight(match) { background-color: #FFCC00 }"));
        UIText text = new UIText("find the match here");
        document.append(text);
        text.highlights().add("match", TextRange.of(9, 14));
        settle();
        text.styleSpanCount();
        assertTrue("the positive control: it banded something first", text.highlightBandCount() > 0);

        text.highlights().clear();
        settle();
        text.styleSpanCount();

        assertEquals("nothing is highlighted, so nothing may be banded",
                0, text.highlightBandCount());
    }
}
