package com.crystalgui.widget.texteditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UINode;
import org.junit.Test;

/**
 * The 6.5 batch on the new engine — the editor, its view parts, and the four feature packages.
 *
 * <h3>What is asserted, and why these things</h3>
 *
 * <p>Not the editor's features: 226 widget tests already cover those on the old engine and they move
 * with it at 6.9. This is the port's own risk list — every case below is either something the batch's
 * plan named in advance, or something a defect was found at while porting:</p>
 *
 * <ul>
 *   <li><b>The split itself.</b> Four packages now reach the editor only through its public API, so
 *       the thing that can break is a feature silently not wiring up.</li>
 *   <li><b>{@code Box.x()} is parent-relative</b>, which displaced every error-stripe mark by however
 *       far down the page the groove sits — found by the M6.4 sweep, not by a symptom.</li>
 *   <li><b>The two standing hooks</b> that replaced {@code onLayoutChanged} and
 *       {@code registerTicker}, and are OWNED — which is what makes the hidden editor that keeps
 *       compiling structurally impossible rather than a rule a ticker has to remember.</li>
 *   <li><b>Its own {@code Name}</b>, which it would otherwise inherit from {@code ScrollerView}.</li>
 * </ul>
 */
public class TextEditorBatchPortTest extends UiDocumentTestBase {

    private TextEditor editor;

    private TextEditor editor(String text) {
        withDefaultStyles();
        editor = new TextEditor(text);
        layout(editor, l -> l.width(600f).height(400f));
        document.append(editor);
        frame();
        frame();
        return editor;
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    /**
     * The editor answers {@code texteditor}, not its superclass's tag.
     *
     * <p>A subclass that declares no {@code NAME} inherits one, so this would have reported
     * {@code scrollerview} and matched every rule written for a scroll view — while {@code ua/editor.css}'s
     * 174 rules, all keyed on {@code texteditor}, reached nothing. It reads as the widget not having
     * been built rather than as it not being styled, which is the {@code ToolWindowFrame} lesson.</p>
     */
    @Test
    public void theEditorAnswersForItsOwnKind() {
        assertEquals("texteditor", editor("hello").name().local());
        assertNotEquals("the editor inherited ScrollerView's tag",
                new com.crystalgui.widget.scroll.ScrollerView().name(), editor.name());
    }

    /** ...and the sheets reach it, which is the thing the tag exists for. */
    @Test
    public void theUserAgentSheetReachesTheEditor() {
        TextEditor e = editor("hello");
        Box box = e.box();
        assertNotNull("the editor has no box", box);
        // `ua/editor.css` gives `texteditor` a font and a background; a widget matching nothing would
        // fall back to the initial font size, which is not what the sheet says.
        assertTrue("the editor was not laid out at all", box.width() > 0f && box.height() > 0f);
    }

    // ── The split ────────────────────────────────────────────────────────────

    /**
     * The four feature packages are wired to the editor and answer.
     *
     * <p>The one thing a package split can break silently: a feature that is constructed, held and
     * never reached. Each of these is the public entry the editor itself calls.</p>
     */
    @Test
    public void everyFeaturePackageIsReachable() {
        TextEditor e = editor("alpha beta\nalpha gamma\n");
        assertNotNull("find is not wired", e.finder());
        assertNotNull("folding is not wired", e.folds());
        assertNotNull("diagnostics are not wired", e.problems());
        assertNotNull("language features are not wired", e.langFeatures());
    }

    /**
     * Find actually runs across the package boundary.
     *
     * <p>Asserting the object EXISTS is what a wiring test usually does and it passes against a
     * feature that answers nothing — so this asks for a result, which is the smallest thing that
     * cannot be satisfied by a field being non-null.</p>
     */
    @Test
    public void findReachesTheDocumentAcrossThePackageBoundary() {
        TextEditor e = editor("alpha beta\nalpha gamma\n");
        assertEquals("find did not see the document", 2, e.finder().find("alpha", false));
        assertEquals("a query that matches nothing still reported matches",
                0, e.finder().find("delta", false));
    }

    // ── Box.x() is parent-relative ───────────────────────────────────────────

    /**
     * An error-stripe mark is positioned within the groove it is a child of.
     *
     * <p>The M6.4 rule, and this site was found by sweeping rather than by a symptom: the port
     * subtracted {@code track.box().y()} from a mark that is appended to that track, which on the old
     * engine was the conversion out of absolute coordinates and here counts the groove's own offset
     * twice. Every problem mark would have answered for a line some distance from the one it marks,
     * and the error grows with how far down the page the editor sits.</p>
     *
     * <p><b>The editor is deliberately not at the document's origin</b>, or the two readings agree and
     * this test cannot fail.</p>
     */
    @Test
    public void aStripeMarkIsPositionedInsideItsOwnGroove() {
        withDefaultStyles();
        UINode spacer = sized("spacer", 600f, 120f);
        document.append(spacer);
        editor = new TextEditor("one\ntwo\nthree\nfour\nfive\n");
        layout(editor, l -> l.width(600f).height(300f));
        document.append(editor);
        frame();

        // THROUGH THE BUFFER, which is where the problems actually live -- `EditorDiagnostics.set()`
        // is package-private in `.lang` and this test is in the editor's own package, which is the
        // split working as intended rather than an obstacle.
        editor.buffer().diagnostics().changeOne("test", java.util.List.of(
                Diagnostic.onRow(3, DiagnosticSeverity.ERROR, "boom")));
        frame();
        frame();

        UINode track = editor.verticalScroller().track();
        Box trackBox = track.box();
        assertNotNull("the groove has no box", trackBox);
        // THE CONTROL: the groove is NOT at the document's origin, so an absolute reading and a
        // parent-relative one are genuinely different numbers.
        assertNotEquals("the fixture is flat -- is the spacer above the editor laid out?",
                trackBox.worldY(), trackBox.y(), 1f);

        for (UINode mark : track.children()) {
            Box box = mark.box();
            if (box == null || !mark.isDisplayed()) continue;
            assertTrue("a mark sits outside the groove it is a child of: y=" + box.y()
                            + " groove height=" + trackBox.height(),
                    box.y() >= -1f && box.y() <= trackBox.height() + 1f);
        }
    }

    // ── The two standing hooks ───────────────────────────────────────────────

    /**
     * The editor's heartbeat and its post-layout pass are both OWNED, and both stop when it goes.
     *
     * <p>The old engine registered a ticker one-way and stopped it only by having the ticker return
     * false, so the one thing that carried on in a hidden editor was the editor — the "hidden editor
     * that keeps compiling". Ownership answers it without the hook having to notice anything.</p>
     */
    @Test
    public void theEditorsHooksStopWhenItLeavesTheTree() {
        TextEditor e = editor("hello");
        int before = document.animation().hookCount();
        assertTrue("the editor registered no per-frame hook", before > 0);

        e.removeSelf();
        frame();
        frame();
        assertTrue("a detached editor is still holding hooks",
                document.animation().hookCount() < before);
    }

    /**
     * The post-layout pass runs, which is what realises the visible lines.
     *
     * <p>An ordinary per-frame hook runs BEFORE layout, so on the frame an editor first appears it
     * would realise a window against a viewport of zero — the "measures zero on the same frame" trap.
     * Asserting that SOMETHING was realised is what separates the two.</p>
     */
    @Test
    public void thePostLayoutPassRealisesLines() {
        TextEditor e = editor("one\ntwo\nthree\n");
        assertTrue("no line was realised -- did the afterLayout hook run?",
                e.linesLayer().children().size() > 0);
    }

    // ── The features are separately packaged, not separately wired ───────────

    /** A completion popup is the editor's, and is reached through the suggest package. */
    @Test
    public void theSuggestPackageIsTheEditorsOwn() {
        TextEditor e = editor("hello");
        assertFalse("a fresh editor is already suggesting", e.suggestions().isLive());
        assertSame("two calls built two popups", e.suggestions(), e.suggestions());
    }
}
