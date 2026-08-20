package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.markup.MarkupParser;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.ui.elements.MarkupView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.text.TextRange;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link MarkupView} — the renderer half of the documentation stack.
 *
 * <h3>What these cover, and why only these</h3>
 *
 * <p><b>Rebuilding</b>, because the obvious build is wrong in a way nothing reports: blocks marked as
 * internal children survive {@code clearAllChildren()}, so the view would stack every document it had
 * ever been given and the only symptom is a popup that grows. And <b>where the inline bands land</b>,
 * which is the failure {@code UIText}'s note and {@code DocumentationPopupTest} both record — a range is
 * an offset into a string, so a band computed against the wrong string paints over whatever text moved
 * into those characters. Nothing throws either way.</p>
 *
 * <p>Deliberately not covered: what a paragraph looks like. Sizes, colours and spacing are the
 * stylesheet's, and asserting them here would break on any legitimate restyle.</p>
 */
public class MarkupViewTest extends UiTestBase {

    /**
     * <b>A second document replaces the first; it does not stack on top of it.</b>
     *
     * <p>The trap is {@code clearAllChildren()}, which skips internal children by design — so building
     * the blocks as internal (the instinct, since they are structure the widget owns) leaves every
     * previous document in the tree. The popup would simply get longer with each symbol hovered, which
     * reads as a layout bug rather than as a rebuild that never removed anything.</p>
     */
    @Test
    public void settingASecondDocumentReplacesTheFirstRatherThanAppendingIt() {
        MarkupView view = new MarkupView();
        view.setDocument(MarkupParser.parse("<p>one</p><p>two</p>"));
        int afterFirst = view.getChildren().size();
        assertTrue("the first document produced no blocks at all", afterFirst > 0);

        view.setDocument(MarkupParser.parse("<p>only this</p>"));

        assertEquals("blocks from the previous document survived the rebuild",
                1, view.getChildren().size());
        assertTrue("the surviving block is not the new one: <" + allText(view) + ">",
                allText(view).contains("only this"));
        assertTrue("text from the replaced document is still drawn: <" + allText(view) + ">",
                !allText(view).contains("one"));
    }

    /** An empty document leaves nothing behind — the caller's cue to hide the band. */
    @Test
    public void anEmptyDocumentClearsTheViewAndReportsItself() {
        MarkupView view = new MarkupView(MarkupParser.parse("<p>something</p>"));
        assertTrue("a document with a paragraph reported itself empty", !view.isEmpty());

        view.setDocument(MarkupParser.parse(""));

        assertTrue("an empty document did not report itself empty", view.isEmpty());
        assertEquals("an empty document left blocks behind", 0, view.getChildren().size());
    }

    /**
     * <b>An inline band covers exactly the characters it was written over.</b>
     *
     * <p>Offsets are counted as the run is assembled rather than searched for afterwards. A search would
     * be right here by luck and wrong for any word that repeats, which in prose is most of them — so the
     * fixture repeats one deliberately: {@code code} appears as ordinary prose <em>before</em> the
     * {@code <code>} element, and a search-based implementation marks the first occurrence.</p>
     */
    @Test
    public void anInlineBandCoversTheCharactersItWasWrittenOver() {
        MarkupView view = new MarkupView(
                MarkupParser.parse("<p>the code word is <code>null</code> here</p>"));

        UIText paragraph = firstText(view);
        assertNotNull("no text run was built for the paragraph", paragraph);
        String rendered = paragraph.getText();

        List<TextRange> code = paragraph.highlights().get(MarkupView.CODE_RANGE);
        assertEquals("expected exactly one code band, got " + code, 1, code.size());

        TextRange band = code.get(0);
        // THE BAND COVERS THE WORD; THE PADS SIT OUTSIDE IT. The two do different jobs and they used to
        // be conflated, with the pads inside the band on the reasoning that real advance is the only
        // padding layout honours. True, and only half of it: `HighlightStyle` permits horizontal
        // padding and it paints, but it inflates the rect WITHOUT moving a glyph, so everything it adds
        // is taken from the gap to the neighbouring word. With the pads inside the plate, raising the
        // padding pushed the plate outwards until a chip and the comma after it were touching.
        //
        // So: the PADDING is the plate's breathing room, measured from the glyphs; the PAD CHARACTER is
        // separation from the words either side, which layout honours and the plate cannot grow into.
        // A band covering the pads means the two have been conflated again.
        assertEquals("the band must cover the word alone: <" + rendered + "> band=" + band,
                "null", rendered.substring(band.start(), band.end()));
        // NON-BREAKING, or the leading pad wraps to the previous line -- and now that the plate does not
        // cover it, a break there would leave the chip's first glyph alone at the start of a line.
        assertTrue("the chip lost the pads that separate it from the prose: <" + rendered + ">",
                rendered.contains("\u00A0null\u00A0"));
        assertEquals("the leading pad must be non-breaking",
                '\u00A0', rendered.charAt(band.start() - 1));
        assertEquals("the trailing pad must be non-breaking", '\u00A0', rendered.charAt(band.end()));
    }

    /**
     * <b>A band is cleared on every block, not merely reassigned where there is something to say.</b>
     *
     * <p>The failure this prevents is not a stale style: a run left over from a previous document is a
     * set of offsets into a string that no longer exists, so it lands on whatever characters moved into
     * them. Here the second document is longer than the first, so a surviving band would sit over real
     * text and look entirely plausible.</p>
     */
    @Test
    public void aBandFromAPreviousDocumentDoesNotSurviveIntoTheNextOne() {
        MarkupView view = new MarkupView(MarkupParser.parse("<p><code>x</code> y</p>"));
        assertEquals("the fixture did not produce the band it is about to check for",
                1, firstText(view).highlights().get(MarkupView.CODE_RANGE).size());

        view.setDocument(MarkupParser.parse("<p>a longer paragraph with no code in it at all</p>"));

        List<TextRange> code = firstText(view).highlights().get(MarkupView.CODE_RANGE);
        assertTrue("a code band from the previous document is still live: " + code, code.isEmpty());
    }

    /**
     * <b>Both styles of a doubly-styled run are reported.</b>
     *
     * <p>{@code MarkupSpan.styles()} is a bitset precisely so {@code <b><code>x</code></b>} does not have
     * to choose, and an enum-shaped renderer would drop one of them at this seam instead. The cascade
     * composes the two rules; what is asserted here is that both bands are registered at all.</p>
     */
    @Test
    public void aRunThatIsBothBoldAndCodeRegistersInBothBands() {
        MarkupView view = new MarkupView(MarkupParser.parse("<p><b><code>id</code></b></p>"));
        UIText paragraph = firstText(view);

        assertEquals("the code band was dropped", 1,
                paragraph.highlights().get(MarkupView.CODE_RANGE).size());
        assertEquals("the strong band was dropped", 1,
                paragraph.highlights().get(MarkupView.STRONG_RANGE).size());
    }

    /**
     * <b>A {@code <pre>} sample becomes its own element and keeps its whitespace.</b>
     *
     * <p>It is a box rather than a band because it needs a background behind the whole block, including
     * the ends of short lines. The indentation is the assertion that the parser's "do not collapse inside
     * pre" decision survives to the element that draws it.</p>
     */
    @Test
    public void aPreSampleBecomesItsOwnBlockWithItsWhitespaceIntact() {
        MarkupView view = new MarkupView(
                MarkupParser.parse("<p>before</p><pre>if (x) {\n    y();\n}</pre>"));

        UIElement sample = null;
        for (UIElement child : view.getChildren()) {
            if (child.hasClass(MarkupView.CODE_BLOCK_CLASS)) sample = child;
        }
        assertNotNull("the <pre> did not become a code block element", sample);

        String text = allText(sample);
        assertTrue("the sample's indentation was collapsed: <" + text + ">", text.contains("    y();"));
        assertTrue("the sample lost its line breaks: <" + text + ">", text.contains("\n"));
    }

    /**
     * <b>A {@code <pre>} sample is lexed when a language has been named, and left alone otherwise.</b>
     *
     * <p>Asserted through the {@code __syntax__} class rather than through the bands, because that class
     * is the half that is easy to omit and impossible to see: a run carrying ranges without it resolves
     * every scheme rule to nothing and draws as plain text, which reads as the lexing not working rather
     * than as a missing selector. The band count then says the tokenizer was actually consulted.</p>
     *
     * <p>{@code KeywordTokenizer} is what answers here — {@code core}'s own engineless tier, since a test
     * in this module has no grammar module behind it. That is the point: the seam is
     * {@link com.crystalgui.text.syntax.SyntaxTokenizer}, and a consumer must not care which side of it
     * answered.</p>
     */
    @Test
    public void aCodeSampleIsLexedOnlyWhenALanguageIsNamed() {
        String doc = "<pre>class A { int x = 1; }</pre>";

        MarkupView plain = new MarkupView();
        plain.setDocument(MarkupParser.parse(doc));
        UIText untouched = firstText(plain);
        assertNotNull("the sample produced no text run", untouched);
        assertTrue("an unnamed language still marked the sample as code",
                !untouched.hasClass(UIText.SYNTAX_CLASS));

        MarkupView java = new MarkupView().setCodeLanguage(Language.JAVA);
        java.setDocument(MarkupParser.parse(doc));
        UIText lexed = firstText(java);
        assertNotNull("the sample produced no text run", lexed);
        assertTrue("the sample was not marked as syntax-coloured, so no scheme rule can reach it",
                lexed.hasClass(UIText.SYNTAX_CLASS));
        assertTrue("no bands were registered, so nothing was lexed",
                !lexed.highlights().entries().isEmpty());
    }

    /**
     * <b>The sample's text is untouched by lexing.</b>
     *
     * <p>Bands are offsets into the run's own string, so anything that rewrote the text to colour it
     * would be painting over the wrong characters. Worth stating because the inline chip <em>does</em>
     * pad its text, and a reader who knows that would reasonably expect this to as well.</p>
     */
    @Test
    public void lexingDoesNotAlterTheSampleText() {
        String sample = "class A { int x = 1; }";
        MarkupView view = new MarkupView().setCodeLanguage(Language.JAVA);
        view.setDocument(MarkupParser.parse("<pre>" + sample + "</pre>"));

        assertEquals("the sample was rewritten", sample, firstText(view).getText());
    }

    /**
     * <b>{@code UIText.offsetAt} answers a real character, and it is the foundation of both link
     * gestures.</b>
     *
     * <p>Nothing else in the engine calls it, so nothing else would notice if it answered {@code -1} for
     * every point — and both the press and the hover would then simply do nothing, which is
     * indistinguishable from the events not arriving. Worth pinning for that reason alone.</p>
     */
    @Test
    public void offsetAtAnswersACharacterInsideALaidOutRun() {
        // A STYLED fixture, because the answer is RUN-granular by design: an unstyled paragraph is ONE
        // shaped run, so every point in it is that run’s first character and a test over one would
        // pass against an implementation that always answered zero. A span boundary is a shaping-run
        // boundary, which is what makes “which link” exact even though “which letter” is not.
        MarkupView view = new MarkupView(MarkupParser.parse(
                "<p>go <a href=\"java:A\">Alpha</a> now</p>"));
        UIText run = inWindow(view);
        int alpha = run.getText().indexOf("Alpha");

        assertEquals("a point before the first glyph is the first character", 0, run.offsetAt(1f, 4f));
        assertTrue("the fixture lost its link", alpha > 0);
        // Far enough in to be past “go ” and inside the link’s own run.
        assertEquals("a point over the link did not resolve to it", alpha, run.offsetAt(14f, 4f));
        assertTrue("a point past the last glyph must answer nothing",
                run.offsetAt(run.getRuntimeCache().getWidth() - 2f, 4f) < 0);
        assertTrue("a point above the first line must answer nothing", run.offsetAt(4f, -20f) < 0);
    }

    /**
     * <b>Hovering a link marks that link and nothing else.</b>
     *
     * <p>A paragraph is ONE text element, so {@code :hover} on it is true anywhere in the sentence and
     * cannot say which link the pointer is over. The band is driven from the pointer instead, and this
     * pins the half that decides which range gets it.</p>
     */
    @Test
    public void hoveringALinkMarksThatLinkAlone() {
        MarkupView view = new MarkupView(MarkupParser.parse(
                "<p>see <a href=\"java:A\">Alpha</a> and <a href=\"java:B\">Beta</a></p>"));
        UIText run = inWindow(view);

        int alpha = run.getText().indexOf("Alpha");
        assertTrue("the fixture lost its link text", alpha >= 0);

        view.hoverAt(run, alpha + 1);
        assertEquals("the hovered link was not marked", 1,
                run.highlights().get(MarkupView.LINK_HOVER_RANGE).size());
        assertEquals("the mark is not on the link under the pointer", alpha,
                run.highlights().get(MarkupView.LINK_HOVER_RANGE).get(0).start());

        view.hoverAt(run, -1);
        assertTrue("the mark survived the pointer leaving",
                run.highlights().get(MarkupView.LINK_HOVER_RANGE).isEmpty());
    }

    /** Attaches a view to a settled window and answers its first run. */
    private UIText inWindow(MarkupView view) {
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.addChild(view);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 200);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
        UIText run = firstText(view);
        assertNotNull("no run was built", run);
        return run;
    }

    /*
     * DELIBERATELY NOT TESTED HERE: the pointer-to-band path end to end.
     *
     * It needs `localToWorld` on the runs, and that is populated during `drawSubtree` -- so in a headless
     * test every element reports the root transform alone and a point computed from one lands nowhere
     * near the element it came from. The fixture measured itself rather than the feature: the run laid
     * out at (-100,-50) while the "world" point came back as 2x the local one, and the hit test
     * answered with the ROOT.
     *
     * What is covered above is everything reachable without a paint: `offsetAt` resolving a point to the
     * right link, and `hoverAt` writing and clearing the band on the right range. What is not is whether
     * the window agrees that a run is under the pointer, which needs a real frame.
     */

    /**
     * <b>A pointer resolves to a character even when the run is not at the origin.</b>
     *
     * <p>The whole defect this pins is invisible at (0,0): {@code screenToLocal} answers the space the
     * element's BOX lives in, while {@code offsetAt} wants the element's own — so the two agree
     * exactly when the box origin is zero and diverge by the box origin everywhere else. Both link
     * gestures wrote the pair out longhand and both were wrong; every fixture built around one element
     * filling its window passed anyway.</p>
     *
     * <p>So the run here is deliberately pushed down and right. Reverting {@code offsetAtScreen} to
     * {@code offsetAt(local)} fails this on the second assertion.</p>
     */
    @Test
    public void aPointerResolvesToACharacterWhenTheRunIsAwayFromTheOrigin() {
        MarkupView view = new MarkupView(MarkupParser.parse("<p>alpha beta gamma</p>"));
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        UIElement spacer = new UIElement().layout(l -> l.width(400).height(120));
        root.addChild(spacer);
        root.addChild(view);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 300);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();

        UIText run = firstText(view);
        assertNotNull("no run was built", run);
        float boxY = run.getRuntimeCache().getY();
        assertTrue("the fixture did not push the run off the origin -- it cannot show the defect",
                boxY > 0f);

        // A point a little inside the run, converted out to where a pointer would have to be. The box
        // origin goes in because `localToWorld` maps the space the BOX lives in, not the element's own.
        var world = Transform2D.apply(run.getRuntimeCache().localToWorld.get(),
                run.getRuntimeCache().getX() + 2f, boxY + 2f);

        assertEquals("a pointer just inside the run must resolve to its first character",
                0, run.offsetAtScreen(world.x(), world.y()));

        var straight = run.screenToLocal(world.x(), world.y());
        assertTrue("feeding screenToLocal straight to offsetAt is the defect this guards --"
                        + " it must not agree with the corrected conversion",
                run.offsetAt(straight.x(), straight.y()) != 0);
    }

    /**
     * <b>Several {@code <dd>}s under one {@code <dt>} all reach the screen.</b>
     *
     * <p>HTML allows it and javadoc produces it: {@code @throws} with two exceptions is one Throws
     * heading over two values. The parser always kept both — the view built its value column, then
     * REPLACED it for each detail it met, so every value but the last was dropped after being correctly
     * parsed. Silent, and invisible in any fixture with one value per label.</p>
     *
     * <p>Asserted on the rendered text rather than on child counts, because a column that exists and is
     * empty would satisfy a count.</p>
     */
    @Test
    public void severalDetailsUnderOneTermAllRender() {
        MarkupView view = new MarkupView(MarkupParser.parse(
                "<dl><dt>Throws:</dt><dd>IOException</dd><dd>SQLException</dd></dl>"));

        List<String> rendered = new ArrayList<>();
        collectText(view, rendered);

        assertTrue("the label is missing: " + rendered, rendered.contains("Throws:"));
        assertTrue("the first value was dropped -- only the last survived: " + rendered,
                rendered.contains("IOException"));
        assertTrue("the second value is missing: " + rendered, rendered.contains("SQLException"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static UIText firstText(UIElement root) {
        if (root instanceof UIText) return (UIText) root;
        for (UIElement child : root.getChildren()) {
            UIText found = firstText(child);
            if (found != null) return found;
        }
        return null;
    }

    private static String allText(UIElement root) {
        List<String> parts = new ArrayList<>();
        collectText(root, parts);
        return String.join("", parts);
    }

    private static void collectText(UIElement root, List<String> into) {
        if (root instanceof UIText) into.add(((UIText) root).getText());
        for (UIElement child : root.getChildren()) collectText(child, into);
    }
}
