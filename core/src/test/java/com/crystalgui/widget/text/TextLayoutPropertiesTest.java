package com.crystalgui.widget.text;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.property.visual.text.TextAlign;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.text.UIText;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The four text properties ported after comparing {@code UIText} against LDLib2's {@code TextElement}
 * — {@code text-align}, {@code white-space}, {@code text-overflow}, and finally consuming the
 * long-registered {@code text-shadow}.
 *
 * <p>All four are real CSS. LDLib's fifth feature, a scrolling marquee, was deliberately not ported:
 * {@code <marquee>} is obsolete and CSS never replaced it, because {@code text-overflow: ellipsis} is
 * the answer to the same problem and reads better in a dense UI.</p>
 *
 * <p>Alignment and shadow are <b>paint-time</b> effects — they move and duplicate glyphs without
 * touching geometry, so there is nothing in the layout tree to assert. What is testable is the part
 * that <em>does</em> reach layout: whether text wraps, and how wide it ends up. These tests therefore
 * pin wrapping and truncation, and pin alignment/shadow at the property level.</p>
 */
public class TextLayoutPropertiesTest extends UiDocumentTestBase {

    private static final String LONG = "wrap me onto several lines if you can manage it";

    private UINode root;

    private UIText build(java.util.function.Consumer<UIText> configure) {
        root = new UINode().layout(l -> l.width(400).height(400));
        UIText text = new UIText(LONG);
        text.layout(l -> l.maxWidth(80));
        configure.accept(text);
        root.append(text);

        document.append(root);
        settle();
        settle();
        return text;
    }

    private void settle() {
        frame();
    }

    // ── white-space ─────────────────────────────────────────────────────────

    /** The pre-existing behaviour, pinned so `nowrap` can be shown to differ from it. */
    @Test
    public void normalWhiteSpaceWrapsWithinMaxWidth() {
        UIText text = build(t -> { });

        assertTrue("should wrap inside its max-width", text.box().width() <= 80.5f);
        assertTrue("and be several lines tall, was " + text.box().height(),
                text.box().height() > 40f);
    }

    /**
     * `nowrap` overrides the wrap bound entirely — one line, however long.
     *
     * <p>Asserted on <b>height</b>, not width, and that distinction is the point: `max-width` still
     * caps the <em>box</em> at 80, so the text overflows it rather than widening it. That is exactly
     * CSS, and exactly why `text-overflow` exists — a nowrap line inside a bounded box is the only
     * situation an ellipsis can apply to.</p>
     */
    @Test
    public void nowrapProducesOneLineThatOverflowsTheBox() {
        UIText text = build(t -> t.generalStyle(g -> g.whiteSpace(WhiteSpace.NOWRAP)));

        assertTrue("must collapse to a single line, was " + text.box().height(),
                text.box().height() < 40f);
        assertTrue("the box stays capped by max-width; the text overflows it, was "
                        + text.box().width(),
                text.box().width() <= 80.5f);
    }

    /** `white-space` inherits, matching CSS — so a container can set it for a whole subtree. */
    @Test
    public void whiteSpaceInherits() {
        root = new UINode().layout(l -> l.width(400).height(400));
        root.generalStyle(g -> g.whiteSpace(WhiteSpace.NOWRAP));
        UIText child = new UIText(LONG);
        child.layout(l -> l.maxWidth(80));
        root.append(child);
        document.append(root);
        settle();
        settle();

        assertEquals(WhiteSpace.NOWRAP, child.getStyle().getGeneralGroup().whiteSpace());
        assertTrue("an inherited nowrap must actually stop the wrapping, height was "
                        + child.box().height(),
                child.box().height() < 40f);
    }

    // ── text-overflow ───────────────────────────────────────────────────────

    /**
     * Ellipsis is a paint-time truncation of a nowrap line, so it does not change the element's box —
     * asserting on width would be asserting on nothing. What it must do is pick a shorter string, which
     * is observable through the layout it produces being narrower than the untruncated one.
     */
    @Test
    public void ellipsisOnlyAppliesWhenTextDoesNotWrap() {
        UIText wrapping = build(t -> t.generalStyle(g -> g.textOverflow(TextOverflow.ELLIPSIS)));

        // Wrapped text has no horizontal overflow, so ellipsis has nothing to do and the box is
        // unchanged from the plain wrapping case.
        assertTrue(wrapping.box().width() <= 80.5f);
        assertTrue(wrapping.box().height() > 40f);
    }

    /**
     * The test that was missing, and whose absence let a broken ellipsis ship green: truncation must
     * actually <b>shorten the string</b>.
     *
     * <p>Everything else about ellipsising is invisible to a test — it changes no geometry, so the layout
     * tree looks identical whether it fired or not. {@link UIText#displayedText()} exists precisely so
     * this question has an answer, and the two bugs it now catches were both found on screen instead:
     * a `text-overflow` set on an ancestor (it does not inherit, so it never arrived), and an ellipsis
     * glyph the font could not draw (correctly shortened, then a blank advance in the gap — identical to
     * plain clipping).</p>
     */
    @Test
    public void ellipsisActuallyShortensTheString() {
        UIText text = build(t -> t.generalStyle(g -> g
                .whiteSpace(WhiteSpace.NOWRAP)
                .textOverflow(TextOverflow.ELLIPSIS)));

        String shown = text.displayedText();
        assertNotEquals("the whole point: the painted string is not the source string", LONG, shown);
        assertTrue("must end in an ellipsis, was '" + shown + "'",
                shown.endsWith("…") || shown.endsWith("..."));
        assertTrue("must be a prefix of the source plus that ellipsis, was '" + shown + "'",
                LONG.startsWith(shown.substring(0, shown.length() - (shown.endsWith("...") ? 3 : 1))));
    }

    /**
     * {@code "..."} when the font cannot draw U+2026, {@code "…"} when it can — WebKit/Blink's own
     * rule, and not hypothetical here: the bundled {@code MinecraftRegular.otf} has no U+2026, so without
     * the fallback a truncated label drew a blank advance and looked exactly like {@code clip}.
     */
    @Test
    public void theEllipsisFallsBackToThreePeriodsWhenTheFontLacksTheGlyph() {
        UIText text = build(t -> t.generalStyle(g -> g
                .whiteSpace(WhiteSpace.NOWRAP)
                .textOverflow(TextOverflow.ELLIPSIS)
                .fontFamily(java.util.List.of("crystalgui:ui/fonts/MinecraftRegular.otf"))));

        assertTrue("MinecraftRegular has no U+2026, so the three-period fallback must be used, was '"
                        + text.displayedText() + "'",
                text.displayedText().endsWith("..."));
    }

    /** Nothing is truncated when the text fits — a label that fits must never lose a character. */
    @Test
    public void ellipsisLeavesTextThatFitsCompletelyAlone() {
        root = new UINode().layout(l -> l.width(400).height(400));
        UIText text = new UIText("short");
        text.layout(l -> l.width(200));
        text.generalStyle(g -> g.whiteSpace(WhiteSpace.NOWRAP).textOverflow(TextOverflow.ELLIPSIS));
        root.append(text);
        document.append(root);
        settle();
        settle();

        assertEquals("short", text.displayedText());
    }

    @Test
    public void textOverflowDoesNotInherit() {
        root = new UINode().layout(l -> l.width(400).height(400));
        root.generalStyle(g -> g.textOverflow(TextOverflow.ELLIPSIS));
        UIText child = new UIText("x");
        root.append(child);
        document.append(root);
        settle();

        assertEquals("text-overflow is not an inherited property, per CSS UI 4",
                TextOverflow.CLIP, child.getStyle().getGeneralGroup().textOverflow());
    }

    // ── text-align ──────────────────────────────────────────────────────────

    /** Alignment is applied at paint time, so the assertion is on the resolved value and its
     * arithmetic — the leftover-space fraction that {@code paintOverlay} multiplies by. */
    @Test
    public void textAlignResolvesAndInherits() {
        root = new UINode().layout(l -> l.width(400).height(400));
        root.generalStyle(g -> g.textAlign(TextAlign.CENTER));
        UIText child = new UIText("x");
        root.append(child);
        document.append(root);
        settle();

        assertEquals("text-align inherits, per CSS Text 3",
                TextAlign.CENTER, child.getStyle().getGeneralGroup().textAlign());
    }

    @Test
    public void alignmentFractionsAreTheThirdsOfTheLeftover() {
        assertEquals(0f, TextAlign.LEFT.leadingFraction(), 0f);
        assertEquals(0.5f, TextAlign.CENTER.leadingFraction(), 0f);
        assertEquals(1f, TextAlign.RIGHT.leadingFraction(), 0f);
    }

    @Test
    public void textAlignDefaultsToLeft() {
        UIText text = build(t -> { });
        assertEquals(TextAlign.LEFT, text.getStyle().getGeneralGroup().textAlign());
    }

    // ── text-shadow ─────────────────────────────────────────────────────────

    /** Registered long before anything drew it — `AGENTS.md` called it out as a no-op. Now consumed. */
    @Test
    public void textShadowIsReadableAndInherits() {
        root = new UINode().layout(l -> l.width(400).height(400));
        root.generalStyle(g -> g.textShadow(true));
        UIText child = new UIText("x");
        root.append(child);
        document.append(root);
        settle();

        assertTrue("text-shadow inherits alongside the other text properties",
                child.getStyle().getGeneralGroup().textShadow());
    }

    @Test
    public void textShadowDefaultsOffSoNothingChangesForExistingTrees() {
        UIText text = build(t -> { });
        assertFalse(text.getStyle().getGeneralGroup().textShadow());
    }
}
