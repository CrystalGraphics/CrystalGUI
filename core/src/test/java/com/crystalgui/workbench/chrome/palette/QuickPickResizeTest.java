package com.crystalgui.workbench.chrome.palette;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.core.collection.pick.QuickPickEntry;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * A {@link QuickPick} the user has resized must still contain its own rows.
 *
 * <h3>Why this is a test and not a glance</h3>
 *
 * <p>The failure is invisible in every direction but one. Resize the popup <b>tall while it is empty</b>,
 * then type: the rows run past the bottom edge and paint over whatever is behind, and they are still
 * hit-testable down there. Every other order of events looks fine — resize with rows already showing and
 * the box is big enough, so nothing spills.</p>
 *
 * <p>The cause is a default rather than a bug in the resize: <b>this engine sets {@code flex-shrink} to
 * {@code 0}</b>, so a parent whose height is fixed does <em>not</em> compress its children. They keep
 * their content size and overflow it. Sizing a list to its content inside a box the user has fixed is
 * therefore always wrong, and the fix is the fill idiom — {@code height: 0; flex-grow: 1}.</p>
 */
public class QuickPickResizeTest extends UiDocumentTestBase {

    /** Far more rows than the popup's own cap, so content sizing and filling cannot coincide. */
    private static final int ROWS = 40;

    /** Deliberately tall, and taller than the rows need — the shape the report came in. */
    private static final float USER_HEIGHT = 300f;

    private QuickPick pick;

    @Before
    public void setUp() {
        UIElement root = new UIElement().layout(l -> l.width(900).height(700));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        pick = new QuickPick();
        pick.setTitle("Go to File");
        pick.setSource(new Rows());
        root.append(pick);
        pick.open(document);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) {
            frame();
        }
    }

    /** A source that always answers {@link #ROWS} rows, whatever is typed. */
    private static final class Rows implements QuickPickSource {
        @Override
        public void fetch(com.crystalgui.core.search.SearchQuery query, ResultSink sink) {
            for (int i = 0; i < ROWS; i++) {
                if (!sink.accept(QuickPickEntry.plain(QuickPickItem.of("id" + i, "Row " + i)))) return;
            }
        }
    }

    /** Takes the height the way a drag on the resize handle does — INLINE, plus the user-sized latch. */
    private void userResizesTo(float height) {
        StyleGroup.inlinePipeline(pick.getStyle().getLayoutGroup(), l -> l.height(height));
        // CAST, because `markUserSized` is package-private on UIElement and QuickPick is in another
        // package -- so it is not a member of QuickPick at all and cannot be called through one. This test
        // shares UIElement's package, which is the only reason it can reach it.
        ((UIElement) pick).markUserSized(false, true);
        settle();
    }

    private static float bottomOf(UIElement element) {
        return element.box().y() + element.box().height();
    }

    /**
     * <b>Rows stay inside the popup after a resize.</b>
     *
     * <p>Asserted geometrically rather than on the style, because the style is not the claim — a popup
     * whose list carries the right declarations and still overflows has failed. This is what "it breaks"
     * looked like: the list's box ended two hundred pixels below the popup's.</p>
     */
    @Test
    public void aResizedPopupStillContainsItsRows() {
        userResizesTo(USER_HEIGHT);
        pick.refresh();
        settle();

        float listBottom = bottomOf(pick.resultList());
        float popupBottom = bottomOf(pick);
        assertTrue("the list runs " + (listBottom - popupBottom) + "px past the popup it is inside",
                listBottom <= popupBottom + 0.5f);
    }

    /**
     * <b>...and it fills the height rather than leaving it blank.</b>
     *
     * <p>The other half. Clamping the list to its content would also stop the overflow and would waste
     * every pixel the user just dragged for — the popup would be tall and mostly empty, which is a
     * different bug wearing the same fix.</p>
     */
    @Test
    public void aResizedPopupUsesTheHeightItWasGiven() {
        userResizesTo(USER_HEIGHT);
        pick.refresh();
        settle();

        float listHeight = pick.resultList().box().height();
        // The list gets whatever is left after the header and the field -- generously bounded here, since
        // the exact chrome height is the sheet's business and this test is not about it.
        assertTrue("the list took only " + listHeight + "px of a " + USER_HEIGHT + "px popup",
                listHeight > USER_HEIGHT * 0.6f);
    }

    /**
     * <b>An untouched popup still sizes to its content.</b>
     *
     * <p>The regression the fix could easily cause: making the list always fill would give a popup with
     * two results the full height of one with forty, which is the behaviour VS Code's palette shrinking
     * as a query narrows exists to avoid.</p>
     */
    @Test
    public void anUntouchedPopupStillShrinksToItsRows() {
        pick.refresh();
        settle();

        float popupHeight = pick.box().height();
        assertTrue("an unresized popup grew to fill its document: " + popupHeight,
                popupHeight < 400f);
    }
}
