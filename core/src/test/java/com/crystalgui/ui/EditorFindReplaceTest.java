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
        editor = new TextEditor("one two one\nthree one\n");
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
