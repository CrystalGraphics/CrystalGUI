package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.editor.SearchReplaceBar;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.event.KeyboardEvent;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The editor's find & replace bar, over the headless engine in {@code text.search}.
 *
 * <p>What is asserted here is the <b>wiring</b> — that the bar drives the editor, that Exclude reaches
 * Replace All, that Escape gives up in the right order. The matching itself is {@code TextSearchTest}'s and
 * needs no window; this needs one only because a bar is a widget.</p>
 */
public class EditorFindReplaceTest extends UiTestBase {

    private UIWindow window;
    private TextEditor editor;
    private SearchReplaceBar bar;

    @Before
    public void setUp() {
        build("one two one\nthree one\n");
    }

    private void build(String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(400).height(200));
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 400);
        window.setUiScale(1f);
        settle();
        bar = editor.searchBar();
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private void type(String query) {
        bar.findField().setText(query);
        settle();
    }

    // ── The bar drives the editor ───────────────────────────────────────────────────────────────

    @Test
    public void typingSearchesTheDocument() {
        type("one");
        assertEquals(3, editor.matchCount());
    }

    /**
     * <b>Selecting a word and pressing Ctrl+F searches for that word.</b>
     *
     * <p>Which is why the selection is made first rather than after, and both references do it. Without
     * it the gesture is a lie: you highlight the thing you want, and the box opens empty.</p>
     */
    @Test
    public void openingSeedsTheQueryFromTheSelection() {
        editor.setSelection(4, 7);                 // "two"
        bar.open();
        settle();

        assertEquals("two", bar.findField().getText());
        assertEquals("and the seeded query is actually run", 1, editor.matchCount());
    }

    /**
     * And it lands on the occurrence it was seeded FROM, not on the first one on screen.
     *
     * <p>The anchor for a typed query is the top of the viewport — that is what stops typing scrolling the
     * document away. A <em>seeded</em> query already knows something better: the occurrence you
     * highlighted is the one you asked about. Anchored on the viewport instead, an earlier occurrence
     * higher up the screen wins and the highlight jumps off the word you picked.</p>
     */
    @Test
    public void aSeededQueryStaysOnTheOccurrenceItWasSeededFrom() {
        editor.setSelection(8, 11);                // the SECOND "one", with one above it
        bar.open();
        settle();

        assertEquals("one", bar.findField().getText());
        assertEquals(3, editor.matchCount());
        assertEquals("it jumped to the first match instead of keeping the one selected",
                8, editor.getSelectionStart());
    }

    /**
     * A multi-line selection is a <b>scope</b>, not a query — so it seeds nothing.
     *
     * <p>Both references read one as "search inside this" rather than as a literal to look for. Nothing
     * here implements that scope yet, and pasting the block in as a query would match nothing while
     * burying whatever was in the box.</p>
     */
    @Test
    public void aMultiLineSelectionDoesNotSeedTheQuery() {
        type("three");
        assertEquals(1, editor.matchCount());

        editor.setSelection(8, 13);                // "one\nt" -- across the newline
        bar.open();
        settle();

        assertEquals("the block replaced the query", "three", bar.findField().getText());
    }

    /** The toggles reach the scan, which is the whole point of passing a query rather than a string. */
    @Test
    public void theOptionsReachTheScan() {
        type("ONE");
        assertEquals("case-insensitive by default", 3, editor.matchCount());

        editor.find(SearchQuery.of("ONE", SearchQuery.Options.DEFAULT.withMatchCase(true)));
        assertEquals(0, editor.matchCount());
    }

    // ── Replace ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void replaceAllReplacesEveryMatch() {
        type("one");
        assertEquals(3, editor.replaceAll("X"));
        assertFalse(editor.getText().contains("one"));
    }

    /**
     * <b>Exclude takes a match out of Replace All and leaves it in the document.</b>
     *
     * <p>The behaviour Exclude exists for, end to end: the excluded span stays, is still counted, and is
     * struck through by the sheet rather than un-highlighted.</p>
     */
    @Test
    public void anExcludedMatchSurvivesReplaceAll() {
        type("one");
        editor.findNext();
        assertTrue(editor.toggleExcludeCurrentMatch());
        assertEquals("it is still one of the matches", 3, editor.matchCount());
        assertEquals(1, editor.searchResults().excludedRanges().size());

        assertEquals("Replace All should have skipped it", 2, editor.replaceAll("X"));
        assertTrue("the excluded text is still there", editor.getText().contains("one"));
    }

    /** Preserve case gives the replacement the shape of what it replaced. */
    @Test
    public void preserveCaseFollowsTheMatch() {
        editor.setText("ONE one One\n");
        settle();
        editor.setPreserveCase(true);
        type("one");
        assertEquals(3, editor.replaceAll("two"));
        assertTrue(editor.getText().contains("TWO"));
        assertTrue(editor.getText().contains("two"));
        assertTrue(editor.getText().contains("Two"));
    }

    // ── Keys ────────────────────────────────────────────────────────────────────────────────────

    private boolean escape() {
        KeyboardEvent.Down event = new KeyboardEvent.Down(
                bar.findField().field(), CgKeyCodes.KEY_ESCAPE, '\0', false, 0, 0L);
        window.getInputHandler().sendInputEvent(bar.findField().field(), event);
        settle();
        return event.isPropagationStopped();
    }

    /**
     * <b>Nothing the bar does to itself may move the document.</b>
     *
     * <p>Opening it, typing in it and escaping out of it are all things you do <em>while looking at</em>
     * a place in the file, and every one of them has been reported as jumping to line 1. Asserted across
     * the whole gesture rather than at one step, because the report was never about a single action —
     * it was that the view does not stay where it was put.</p>
     */
    @Test
    public void theBarNeverMovesTheDocumentUnderYou() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 400; i++) document.append("line ").append(i).append(" sample\n");
        build(document.toString());
        // AT A REAL UI SCALE. Every other fixture here runs at 1, where a value measured in surface
        // pixels and written back as a logical one is indistinguishable from a correct one.
        window.setUiScale(2f);
        for (int i = 0; i < 4; i++) settle();

        editor.setScrollImmediate(0f, 2000f);
        for (int i = 0; i < 4; i++) settle();
        float parked = editor.getScrollTop();
        assertTrue("fixture must be scrolled away from the top, was " + parked, parked > 100f);

        // ONE LINE of tolerance, not zero. firstVisibleOffset anchors on the top row, which is normally
        // scrolled partway off -- so revealing a match on it nudges by that fraction, once. A jump to the
        // top of the document is three hundred lines, and this still catches it.
        float slack = editor.lineHeight() + 1f;

        bar.open();
        settle();
        assertEquals("opening moved it", parked, editor.getScrollTop(), slack);

        type("sample");
        assertEquals("typing moved it", parked, editor.getScrollTop(), slack);

        assertTrue("the first escape should be consumed clearing the query", escape());
        assertTrue("the second should be consumed closing the bar", escape());
        for (int i = 0; i < 6; i++) settle();

        assertEquals("escaping moved it", parked, editor.getScrollTop(), slack);
    }

    /**
     * <b>Stepping to a match must leave it on screen.</b>
     *
     * <p>Asked through {@link TextEditor#offsetAt} rather than against the scroll numbers, because that
     * is the same conversion a click uses — so "on screen" here means the row can actually be pointed at,
     * not that some internal arithmetic agrees with itself.</p>
     */
    @Test
    public void steppingToAMatchLeavesItOnScreen() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            document.append("line ").append(i);
            if (i == 40 || i == 200 || i == 380) document.append(" classify");
            document.append('\n');
        }
        build(document.toString());

        bar.open();
        settle();
        type("classify");
        for (int i = 0; i < 6; i++) settle();
        assertEquals(3, editor.matchCount());

        for (int n = 0; n < 3; n++) {
            editor.findNext();
            for (int i = 0; i < 4; i++) settle();
            int row = rowOf(editor.getCaret());
            assertTrue("match " + (n + 1) + " landed on row " + row + ", which is off screen -- rows "
                    + rowOf(editor.offsetAt(20f, 1f)) + ".."
                    + rowOf(editor.offsetAt(20f, editor.getRuntimeCache().getHeight() - 1f))
                    + " are (scrollTop=" + editor.getScrollTop()
                    + ", padTop=" + editor.getTaffyLayout().padding().top + ")",
                    rowIsOnScreen(row));
        }
    }

    /**
     * <b>An off-screen match is centred; an on-screen one does not move the view at all.</b>
     *
     * <p>IntelliJ's {@code ScrollType.CENTER}, and the argument {@code revealCaretCentred} already makes:
     * stepping to a match is arriving somewhere new, so it wants the most context. Minimal scrolling
     * frames the destination hard against an edge with every surrounding line on one side, which is the
     * worst framing for the one line you were sent to look at.</p>
     *
     * <p>The second half is what stops it being annoying: a match already on screen must not move the
     * view, or every press of Enter lurches the file for no reason.</p>
     */
    @Test
    public void steppingCentresAMatchItHadToScrollTo() {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            document.append("line ").append(i);
            if (i == 200 || i == 201) document.append(" classify");
            document.append('\n');
        }
        build(document.toString());

        // DRIVEN THROUGH THE EDITOR, not the bar: typing a query deliberately does NOT centre, so going
        // through the box would exercise the other half of the split and hide this one.
        editor.find("classify", false);
        settle();
        assertEquals(2, editor.matchCount());

        editor.findNext();
        for (int i = 0; i < 4; i++) settle();
        assertEquals(200, rowOf(editor.getCaret()));

        // CENTRED: the match should sit near the middle of the box, not against either edge.
        float boxHeight = editor.getRuntimeCache().getHeight();
        int centreRow = rowOf(editor.offsetAt(20f, boxHeight / 2f));
        assertEquals("the match should have been centred, not brought flush to an edge",
                200, centreRow, 1);

        // AND THE SECOND MATCH IS ALREADY ON SCREEN, one line below -- so nothing may move.
        float parked = editor.getScrollTop();
        editor.findNext();
        for (int i = 0; i < 4; i++) settle();
        assertEquals("a match already on screen must not scroll the view",
                parked, editor.getScrollTop(), 0.5f);
        assertEquals(201, rowOf(editor.getCaret()));
    }

    private int rowOf(int offset) {
        return editor.getText().substring(0, offset).split("\n", -1).length - 1;
    }

    /** Whether any point in the editor's box resolves to {@code row} — i.e. it can be clicked. */
    private boolean rowIsOnScreen(int row) {
        var cache = editor.getRuntimeCache();
        float step = Math.max(1f, editor.lineHeight() / 3f);
        for (float y = cache.getY(); y < cache.getY() + cache.getHeight(); y += step) {
            if (rowOf(editor.offsetAt(cache.getX() + 20f, y)) == row) return true;
        }
        return false;
    }

    /**
     * <b>Escape clears, then closes, then gives up.</b>
     *
     * <p>Two steps belong to this bar and the third does not — a find bar that cleared its query and then
     * sat there has taken the key that exists to dismiss it. The same cascade the tree's bar learned from
     * the settings dialog.</p>
     */
    @Test
    public void escapeClearsThenClosesThenPassesThrough() {
        bar.open();
        type("one");

        assertTrue("the first Escape should clear the query", escape());
        assertEquals("", bar.findField().getText());
        assertTrue("and leave the bar open", bar.isOpen());

        assertTrue("the second should close it", escape());
        assertFalse(bar.isOpen());

        assertFalse("and the third belongs to whatever is outside", escape());
    }

    /** The chevron is the only affordance for the replace row, and it toggles. */
    @Test
    public void theChevronExpandsAndFoldsTheReplaceRow() {
        bar.open();
        settle();
        assertFalse("find opens with the replace row folded", bar.isReplaceShown());

        bar.openReplace();
        settle();
        assertTrue(bar.isReplaceShown());

        bar.setReplaceShown(false);
        settle();
        assertFalse(bar.isReplaceShown());
    }

    /**
     * <b>The matches follow the document, undo included.</b>
     *
     * <p>Offsets found against the old text describe the new one wrongly: the count went stale and the
     * highlights sat over whatever had moved into their place. Re-running from the buffer's own change
     * signal is what makes undo correct without the undo path knowing search exists.</p>
     */
    @Test
    public void theSearchFollowsEditsAndUndo() {
        type("one");
        assertEquals(3, editor.matchCount());

        editor.replaceAll("X");
        settle();
        assertEquals("the count should follow the edit", 0, editor.matchCount());

        assertEquals("the BAR should say so too", "0", bar.countText());

        editor.undoStack().undo();
        settle();
        assertEquals("and follow it back on undo", 3, editor.matchCount());
        // THE BAR, not just the editor. Undo comes from outside the bar, so nothing it does would have
        // re-read the count -- it went on reporting the numbers from before the undo.
        assertEquals("the bar did not follow the undo", "3", bar.countText());

        editor.undoStack().redo();
        settle();
        assertEquals(0, editor.matchCount());
    }


    /** Ctrl+F means find: it folds the replace row back rather than giving you the state you left. */
    @Test
    public void openingFindFoldsTheReplaceRow() {
        bar.openReplace();
        settle();
        assertTrue(bar.isReplaceShown());

        bar.open();
        settle();
        assertFalse("find reopened with the replace row still expanded", bar.isReplaceShown());
    }


    /**
     * <b>The bar's operations are reachable without its buttons.</b>
     *
     * <p>Which is what makes them bindable: the chords used to be listeners on this widget's own text
     * fields, so six shortcuts lived somewhere no keymap could see and nobody could rebind. Commands invoke
     * these, and the keymap binds the commands.</p>
     */
    @Test
    public void theOptionsAreReachableAsOperations() {
        type("ONE");
        assertEquals(3, editor.matchCount());

        bar.toggleMatchCase();
        settle();
        assertEquals("Match Case did not reach the scan", 0, editor.matchCount());

        bar.toggleMatchCase();
        settle();
        assertEquals(3, editor.matchCount());
    }

    /** Tab visits the two text fields before the options — an explicit ring, not DOM order. */
    @Test
    public void tabVisitsTheReplaceFieldBeforeTheOptions() {
        bar.openReplace();
        settle();
        window.getInputHandler().requestFocus(bar.findField().field());
        settle();

        tab();
        assertSame("Tab from the query should reach the replacement first",
                bar.replaceField().field(), window.getInputHandler().getFocusedElement());

        tab();
        assertSame("and only then the first option",
                bar.findField().options().getChildren().get(0),
                window.getInputHandler().getFocusedElement());
    }

    private void tab() {
        UIElement focused = window.getInputHandler().getFocusedElement();
        window.getInputHandler().sendInputEvent(focused,
                new KeyboardEvent.Down(focused, CgKeyCodes.KEY_TAB, '\0', false, 0, 0L));
        settle();
    }


    /**
     * <b>An Alt chord is not text.</b>
     *
     * <p>{@code TextField} refused Ctrl combos and not Alt ones, so Alt+W inserted a {@code w} <em>and</em>
     * consumed the event — and the keymap resolves after dispatch, only if nothing stopped it. Every Alt
     * shortcut in the application was therefore dead in exactly the place its own tooltip said to press
     * it.</p>
     */
    @Test
    public void anAltChordIsNotTypedIntoTheField() {
        window.getInputHandler().requestFocus(bar.findField().field());
        settle();
        bar.findField().setText("");
        settle();

        window.getInputHandler().sendInputEvent(bar.findField().field(),
                new KeyboardEvent.Down(bar.findField().field(), CgKeyCodes.KEY_W, 'w', false,
                        com.crystalgraphics.platform.input.CgModifiers.ALT, 0L));
        settle();

        assertEquals("Alt+W typed into the query instead of reaching the keymap",
                "", bar.findField().getText());
    }


    /**
     * <b>A tooltip names its accelerator, read from the keymap.</b>
     *
     * <p>Wrong twice: {@code Tooltip.attach} adds a listener pair rather than replacing one, so updating
     * the text left the first tooltip showing; and the refresh that reads the keymap was written and never
     * called, because a scripted edit into the ticker did not match and nothing asserted on it. A tooltip's
     * text is in no layout, no computed style and no screenshot unless the pointer is over it — this is the
     * only place the question can be asked.</p>
     */
    @Test
    public void tooltipsNameTheirAccelerator() {
        bar.openReplace();
        for (int i = 0; i < 6; i++) window.updateWithoutPainting();

        assertTrue("no tooltip named a chord: " + bar.tooltipTexts(),
                bar.tooltipTexts().stream().anyMatch(t -> t.startsWith("Match Case  ")));
        assertTrue(bar.tooltipTexts().stream().anyMatch(t -> t.startsWith("Words  ")));
        assertTrue("a command with no chord shows its name alone",
                bar.tooltipTexts().contains("Replace All"));
    }

}
