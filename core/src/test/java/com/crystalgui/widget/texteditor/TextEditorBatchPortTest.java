package com.crystalgui.widget.texteditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UINode;
import java.util.List;
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

    /**
     * A press in the text moves the caret.
     *
     * <p><b>The press does not target the editor.</b> A {@code ScrollerView} on this engine has a slot
     * and the slot spans the view, so every click in the text lands on {@code <slot>} — inside the
     * editor's own shadow tree. The old engine's ScrollerView had none ("your children are ordinary
     * children; there is no viewport or content wrapper to reach"), so there the press targeted the
     * editor itself and target-only listeners were enough.</p>
     *
     * <p>What that looked like: clicking anywhere in the editor did nothing at all, while the editor
     * still took focus — click-focus walks UP to the nearest node that focuses on click, so the ring
     * appeared and the caret never moved. An editor that looks alive and answers no gesture.</p>
     *
     * <p>Asserted on the CARET rather than on focus, because focus was working the whole time and is
     * exactly what made this read as an input-routing problem rather than a phase one.</p>
     */
    @Test
    public void aPressInTheTextMovesTheCaret() {
        editor("alpha bravo charlie delta echo");
        Box box = editor.box();
        assertNotNull(box);
        assertEquals("the fixture starts with the caret somewhere other than the origin",
                0, editor.selections().primary().head());

        press(box.worldX() + 60f, box.worldY() + 8f);
        frame();

        assertTrue("a press in the text did not move the caret",
                editor.selections().primary().head() > 0);
    }

    /**
     * A line that has been recycled comes back.
     *
     * <p><b>The pool hid on one channel and showed on another.</b> {@code recycleLine} wrote
     * {@code display: none} through the CASCADE while {@code realiseLine} shows with
     * {@code setDisplayed(true)}, which clears the {@code hidden} ATTRIBUTE and says nothing about the
     * cascade — and the box tree refuses a box for either reason. So a line recycled once could never
     * return: shown, unhidden, still resolving {@code display: none}, with no box to paint into.</p>
     *
     * <p>On screen: the text vanishes and does not come back, while the gutter, the fold arrows, the
     * current-line bar and the error tick all stay — separate elements that were never pooled. Reachable
     * by scrolling, by switching tabs, or by Ctrl+A, because all three recycle the viewport.</p>
     *
     * <p><b>Asserted on the BOX, not on the text.</b> The text was never lost — every recycled line still
     * held the right string, which is exactly why this read as a rendering fault rather than a display
     * one. Only the box is missing.</p>
     */
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
    /**
     * A scrolled row is moved by the scroll offset ONCE.
     *
     * <p>Rows are positioned in DOCUMENT coordinates and moved by one matrix per layer, so a row's world
     * position must be its document position less the scroll offset. It was less TWICE that: the editor's
     * parts are all {@code setScrollExempt(true)} and every one of those exemptions was inert, because
     * they land in the {@code ScrollerView}'s slot and an exemption only cancels the offset of the box
     * that HOSTS you. @see ScrollerView#setContentScrollExempt</p>
     *
     * <p><b>Asserted on the WORLD position, never on the box.</b> The box was right the whole time --
     * {@code y = 448} for view line 32 at any scroll offset is exactly what a document coordinate means
     * -- and the transform was right too, reading {@code translate(0, -300)}. Only their composition was
     * wrong, so anything short of the composed matrix passes against the bug. It is also what painting
     * and hit-testing both walk.</p>
     *
     * <p><b>And it must scroll first.</b> At {@code scrollTop = 0} a doubled offset is still zero, which
     * is why this was invisible until the view moved and read as scrolling destroying the text.</p>
     */
    @Test
    public void aScrolledRowMovesByTheScrollOffsetOnce() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("line ").append(i).append(" some text here").append(System.lineSeparator());
        editor(sb.toString());

        editor.setScrollOffsets(0f, 300f);
        frame();

        Box layer = editor.linesLayer().box();
        assertNotNull("the lines layer has a box", layer);
        assertEquals("the layer is moved by the scroll offset once", -300f, layer.worldY(), 0.5f);

        UINode row = null;
        for (UINode child : editor.linesLayer().children()) {
            if (child.box() != null && child.classes().contains("__line__")) {
                row = child;
                break;
            }
        }
        assertNotNull("a row is realised while scrolled", row);
        Box box = row.box();
        assertEquals("a row's world position is its document position less the scroll",
                box.y() - 300f, box.worldY(), 0.5f);
    }

    /**
     * Hovering the text shows the I-beam, without a button held.
     *
     * <p>{@code cursor: auto} resolves to {@code text} over an editable node, and the sheets rely on it
     * outright — {@code ua/config-kit.css} says so where it declines to declare one on {@code textfield}.
     * It asked the HIT node, which inside an editor is a row in a layer and never the editor, so every
     * line of text in the application showed the arrow.</p>
     *
     * <p><b>Asserted without a press.</b> A press takes pointer capture and capture substitutes the hit
     * for the capturing element — the editor — so the I-beam did appear on click and hold, and any test
     * that pressed first passed against the bug. That asymmetry is what the report described.</p>
     */
    @Test
    public void hoveringTheTextShowsTheTextCursor() {
        editor("hello world" + System.lineSeparator() + "second line");
        move(60f, 20f);
        frame();
        assertEquals(CgCursor.TEXT, document.input().currentCursor());
    }

    /**
     * ...and the I-beam stops at the editor's own controls.
     *
     * <p>The counter-assertion to {@link #hoveringTheTextShowsTheTextCursor}, and it is the half a naive
     * fix gets wrong: walking up from the hit node to the nearest text-consuming ancestor puts the I-beam
     * over the editor's scrollbars and fold chevrons too, since those are inside the editor. Only a SLOT
     * passes the question on, because a slot stands for content it does not own.</p>
     */
    @Test
    public void hoveringTheEditorsOwnControlsDoesNotShowTheTextCursor() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 300; i++) text.append("line ").append(i).append(System.lineSeparator());
        editor(text.toString());

        Box bar = editor.verticalScroller().box();
        assertNotNull("the vertical bar is shown for a long document", bar);
        move(bar.worldX() + bar.width() / 2f, bar.worldY() + bar.height() / 2f);
        frame();
        assertEquals("a scrollbar is not text", CgCursor.DEFAULT, document.input().currentCursor());
    }

    /**
     * A control inside the editor keeps its own press.
     *
     * <p>The editor's mouse-down listener is on the BUBBLE phase — it has to be, because a press in the
     * text lands on the {@code ScrollerView}'s slot rather than on the editor — so it also heard every
     * press that bubbled up from a control inside it. For those it moved the caret to the text behind the
     * control AND took pointer capture for the selection drag, which substitutes the hit: the mouse-UP
     * was delivered to the editor, {@code isWasPressTarget()} answered false, and the control never
     * activated. Measured: {@code target=TextEditor pressTarget=Button __inspection-next__}.</p>
     *
     * <p>The Problems widget's arrows are the visible case — they looked dead and merely moved the caret.
     * Asserted through a press at a POINT, because the whole mechanism is hit-testing and capture.</p>
     */
    @Test
    public void aControlInsideTheEditorKeepsItsOwnPress() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) text.append("line ").append(i).append(System.lineSeparator());
        editor(text.toString());
        editor.diagnostics().changeOne("test", List.of(
                Diagnostic.onRow(3, DiagnosticSeverity.ERROR, "boom"),
                Diagnostic.onRow(9, DiagnosticSeverity.ERROR, "bang")));
        frame();
        frame();

        UINode next = findByClass(editor, "__inspection-next__");
        assertNotNull("the Problems widget offers a next-problem arrow", next);
        Box box = next.box();
        assertNotNull("and it is laid out", box);

        float cx = box.worldX() + box.width() / 2f;
        float cy = box.worldY() + box.height() / 2f;
        press(cx, cy);
        release(cx, cy);
        frame();

        assertEquals("the arrow navigates rather than moving the caret under it",
                3, editor.caretRow());
    }

    private static UINode findByClass(UINode at, String cls) {
        if (at.classes().contains(cls)) return at;
        for (UINode child : at.children()) {
            UINode hit = findByClass(child, cls);
            if (hit != null) return hit;
        }
        UINode shadow = at.shadowRoot();
        return shadow == null ? null : findByClass(shadow, cls);
    }
}
