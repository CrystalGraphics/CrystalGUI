package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.chrome.QuickPick;
import com.crystalgui.core.collection.pick.QuickPickEntry;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether a {@link QuickPick} remembers what was typed — {@code setRetainQuery}.
 *
 * <h3>The objection, and why selecting answers it</h3>
 *
 * <p>{@code open} used to always clear, and the stated reason was good: "a palette that opens showing
 * last time's filter with no visible indication it is filtered is worse than one that opens blank". The
 * answer is not to keep clearing but to <b>select</b> the restored text — the first keystroke replaces
 * it, so nothing is stickier than before, while Enter or an arrow reuses it. Both references do exactly
 * this, and the selection is the part that makes the difference, so it is the part asserted.</p>
 */
public class QuickPickQueryRetentionTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;

    @Before
    public void setUp() {
        root = new UIElement().layout(l -> l.width(900).height(700));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(900, 700);
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    /** A picker over three fixed rows, attached and opened. */
    private QuickPick pickerRetaining(boolean retain) {
        QuickPick pick = new QuickPick();
        pick.setRetainQuery(retain);
        pick.setSource(new Rows());
        root.addChild(pick);
        pick.open(window);
        settle();
        return pick;
    }

    private static final class Rows implements QuickPickSource {
        @Override
        public void fetch(com.crystalgui.core.search.SearchQuery query, ResultSink sink) {
            if (!sink.accept(QuickPickEntry.plain(QuickPickItem.of("a", "Alpha")))) return;
            sink.accept(QuickPickEntry.plain(QuickPickItem.of("b", "Beta")));
        }
    }

    private static void type(QuickPick pick, String text) {
        pick.searchField().setText(text);
    }

    // ── Retaining ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>A retained query survives a close and comes back selected.</b>
     *
     * <p>Both halves in one test on purpose: the text alone is the behaviour that was asked for, and the
     * selection is what keeps it from being a trap. Asserting only the text would pass against a version
     * that restores a stale filter with the caret at its end, which is the thing clearing was protecting
     * against in the first place.</p>
     */
    @Test
    public void aRetainedQueryComesBackSelected() {
        QuickPick pick = pickerRetaining(true);
        type(pick, "Alph");

        pick.hide();
        settle();
        pick.open(window);
        settle();

        assertEquals("the query was thrown away on close", "Alph", pick.searchField().getText());
        assertTrue("the restored query is not selected, so typing would append to it",
                pick.searchField().field().hasSelection());
        assertEquals(0, pick.searchField().field().getSelectionStart());
        assertEquals("Alph".length(), pick.searchField().field().getSelectionEnd());

        // AND TYPING REPLACES IT, which is the whole point and the only thing the user can see. The
        // assertions above describe a selection; this one describes what happens next, and a selection
        // that is present but not honoured by the insert looks exactly like no selection at all.
        pick.searchField().field().insertChar('B');
        assertEquals("typing appended to the retained query instead of replacing it",
                "B", pick.searchField().getText());
    }

    /**
     * <b>...and the list is rebuilt against it, not left from last time.</b>
     *
     * <p>A retained query with an empty list would be worse than either — the box says it is filtered and
     * shows nothing. {@code open} re-queries the source, so the rows are current even though the text is
     * not new.</p>
     */
    @Test
    public void aRetainedQueryStillRepopulatesTheList() {
        QuickPick pick = pickerRetaining(true);
        type(pick, "Alph");

        pick.hide();
        settle();
        pick.open(window);
        settle();

        assertFalse("reopening left the list empty under a restored query", pick.visibleEntries().isEmpty());
    }

    // ── Not retaining ───────────────────────────────────────────────────────────────────────────

    /**
     * <b>Without the flag it still opens blank.</b>
     *
     * <p>The default, and the command palette depends on it: repeating a search is ordinary in a way that
     * re-running the command you just ran is not. Retention had to be opt-in rather than a behaviour
     * change every existing caller inherited.</p>
     */
    @Test
    public void aPickerThatDoesNotRetainStillOpensBlank() {
        QuickPick pick = pickerRetaining(false);
        type(pick, "Alph");

        pick.hide();
        settle();
        pick.open(window);
        settle();

        assertEquals("a picker that never asked to retain kept its query anyway",
                "", pick.searchField().getText());
    }
}
