package com.crystalgui.app.shadergraph.blackboard;

import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.text.UIText;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A name too long for the panel must SCROLL, never spill and never wrap.
 *
 * <h3>Why this is a whole test class</h3>
 *
 * <p>The board is resizable down to 80px and a property may be named anything, so "the content is wider
 * than the panel" is the ordinary case rather than an edge one. Three separate things have to line up for
 * it to work, none of them visible from the others, and each failed on its own:</p>
 *
 * <ol>
 *   <li><b>The row must size to its content.</b> {@code align-items: stretch} — the flex default — pins
 *       every row to the body's width, and {@code getScrollWidth()} measures direct children only, on
 *       purpose. So a long name overflowed its capsule, painted out over the canvas behind, and
 *       contributed <em>nothing</em> to the scrollable width: there was no way to reach it at all.</li>
 *   <li><b>Every column of the row must contribute to that content width.</b> A flex item with
 *       {@code flex-shrink: 1} contributes zero to its row's min-content, and one with an explicit
 *       {@code width: 0} basis makes a {@code UIText} settle as "does not size itself" permanently. The
 *       type column had both, so the row grew to fit the capsule exactly and the type rendered as
 *       {@code Ve…} against the panel edge — clipped by the very scrollbar it should have been inside.</li>
 *   <li><b>Text that is not a row must not wrap.</b> The placeholder had no {@code nowrap} and broke over
 *       three lines at the minimum width.</li>
 * </ol>
 *
 * <p>Asserted against measured geometry rather than pixel constants, so a font change moves the numbers
 * without moving the claims.</p>
 */
public class BlackboardOverflowTest extends UiDocumentTestBase {

    /** Narrow enough that any real property name overflows it. */
    private static final int NARROW = 90;

    private GraphDocument graphDocument;
    private BlackboardPanel board;

    private void mount(int panelWidth) {
        graphDocument = new GraphDocument();
        board = new BlackboardPanel(graphDocument, "test", new UndoStack());
        UINode root = new UINode().layout(l -> l.width(600).height(400));
        root.append(board);
        document.append(root);
        // Without the user-agent sheet nothing here has geometry, and every assertion below would pass or
        // fail for reasons unrelated to what it claims.
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        board.layout(l -> l.width(panelWidth));
        settle();
    }

    private void settle() {
        for (int i = 0; i < 5; i++) frame();
    }

    /** Straight into the graphDocument, so no rename editor opens over the capsule and replaces it. */
    private void declare(String name) {
        graphDocument.addProperty(GraphProperty.of(name, "vec2", "(0,0)"), graphDocument.propertyCount());
        settle();
    }

    private UINode body() {
        return descendantWith(board, BlackboardPanel.BODY_CLASS);
    }

    private static UINode descendantWith(UINode in, String cssClass) {
        for (UINode child : in.children()) {
            if (child.hasClass(cssClass)) return child;
            UINode deeper = descendantWith(child, cssClass);
            if (deeper != null) return deeper;
        }
        return null;
    }

    // ── The row scrolls instead of spilling ─────────────────────────────────

    /**
     * <b>A long name makes the list horizontally scrollable.</b>
     *
     * <p>The claim is specifically about {@code maxScrollLeft}: the name being <em>drawn</em> somewhere is
     * not the point — it was drawn before this fix too, over the canvas behind the panel.</p>
     */
    @Test
    public void aNameWiderThanThePanelBecomesScrollable() {
        mount(NARROW);
        declare("bebeeeeeeeeeeeeeeeeee");

        UINode body = body();
        assertTrue("nothing overflowed, so this asserts nothing; scrollWidth=" + body.box().scrollWidth()
                        + " client=" + body.box().clientWidth(),
                body.box().scrollWidth() > body.box().clientWidth());
        assertTrue("the list must be reachable sideways; maxScrollLeft=" + body.box().maxScrollLeft(),
                body.box().maxScrollLeft() > 0f);
    }

    /** The control: a name that fits leaves the list unscrollable, so the rule is not "always scroll". */
    @Test
    public void aShortNameLeavesTheListUnscrollable() {
        mount(200);
        declare("Uv");
        assertEquals("a row that fits must not invent a scrollbar",
                0f, body().box().maxScrollLeft(), 0.01f);
    }

    /**
     * <b>The row is as wide as everything in it</b> — the capsule and the type column both.
     *
     * <p>Stated as "the row's right edge is past the type's" rather than as a width, because the failure
     * was the type sitting <em>outside</em> a row that had grown for the capsule alone.</p>
     */
    @Test
    public void theTypeColumnIsInsideTheScrollableWidth() {
        mount(NARROW);
        declare("bebeeeeeeeeeeeeeeeeee");

        PropertyPill pill = board.pills().get(0);
        UINode type = descendantWith(pill, PropertyPill.TYPE_CLASS);
        assertNotNull("no type column on the row", type);

        float typeRight = type.box().x() + type.box().width();
        float rowRight = pill.box().x() + pill.box().width();
        assertTrue("the type column must have a width of its own; got " + type.box().width(),
                type.box().width() > 0f);
        assertTrue("the type column hangs outside its row: " + typeRight + " > " + rowRight,
                typeRight <= rowRight + 0.5f);
        assertTrue("...and the row must therefore be inside the scrollable width",
                rowRight - body().box().x() <= body().box().scrollWidth() + 0.5f);
    }

    /** A row narrower than the list still fills it, or the right-aligned type column has nothing to align
     * against and the rename field collapses to nothing. */
    @Test
    public void aShortRowStillFillsTheList() {
        mount(200);
        declare("Uv");
        PropertyPill pill = board.pills().get(0);
        assertEquals("a row that fits must still span the list",
                body().box().clientWidth(), pill.box().width(), 12f);
    }

    /**
     * <b>And it is still right-aligned when there is room.</b>
     *
     * <p>The guard on the fix above: the type column is right-aligned by <em>taking the slack</em>, so
     * teaching it to measure itself would be a regression if that stopped it growing. Both facts have to
     * hold at once — a base size of its own, and growth on top of it.</p>
     */
    @Test
    public void theTypeColumnStillHugsTheRightEdgeWhenThereIsRoom() {
        mount(240);
        declare("Uv");

        PropertyPill pill = board.pills().get(0);
        UINode type = descendantWith(pill, PropertyPill.TYPE_CLASS);
        float typeRight = type.box().x() + type.box().width();
        float rowRight = pill.box().x() + pill.box().width();
        // Inside the row's own right padding (6px), and nowhere near the capsule it follows.
        assertEquals("the type column stopped taking the slack and is no longer right-aligned",
                rowRight - 6f, typeRight, 1f);
    }

    // ── ...and nothing wraps ────────────────────────────────────────────────

    /**
     * <b>The placeholder is one line at any width.</b>
     *
     * <p>Compared against its own height in a wide panel, so the claim is "the width did not change it"
     * rather than a pixel count that a font change would break.</p>
     */
    @Test
    public void thePlaceholderDoesNotWrapAtTheMinimumWidth() {
        mount(300);
        float wide = descendantWith(board, "__empty__").box().height();

        mount(NARROW);
        float narrow = descendantWith(board, "__empty__").box().height();

        assertEquals("\"" + BlackboardPanel.EMPTY_MESSAGE + "\" wrapped when the panel got narrow",
                wide, narrow, 0.5f);
    }

    /** The name never re-wraps either — it is one line and the list scrolls to it. */
    @Test
    public void aLongNameDoesNotWrap() {
        mount(300);
        declare("bebeeeeeeeeeeeeeeeeee");
        float wide = descendantWith(board.pills().get(0), PropertyPill.NAME_CLASS)
                .box().height();

        mount(NARROW);
        declare("bebeeeeeeeeeeeeeeeeee");
        UIText narrowName = (UIText) descendantWith(board.pills().get(0), PropertyPill.NAME_CLASS);

        assertEquals("the name wrapped instead of scrolling",
                wide, narrowName.box().height(), 0.5f);
    }
}
