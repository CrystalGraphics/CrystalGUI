package com.crystalgui.net.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.app.machine.ui.MachineStyles;
import com.crystalgui.style.Styleable;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A server's sheet reaching its own window and nothing else, on native {@code @scope}.
 *
 * <p>Driven with the <b>real</b> {@code machine.css} rather than a snippet, because the snippet
 * passed: while this was a textual rewrite, every test scoped a comment-free selector and checked the
 * string, and the sheet that ships is a third comments. A comment between two rules was read as part
 * of the next selector, split on the commas inside it, and the whole sheet was refused with
 * <i>Unparseable selector fragment near '.' in 'that.'</i> — <code>that.</code> being prose. The
 * window opened with no styling at all. Nothing textual is left to break that way; the fixture stays
 * pointed at the real sheet because a parse is still a parse.</p>
 */
public class ScopedSheetParseTest extends UiDocumentTestBase {

    /** The rule the whole scoping question turns on: {@code .machine-panel { width: 560px }}. */
    private static final float PANEL_WIDTH = 560f;

    private static UINode panel() {
        UINode node = new UINode();
        node.addClass(MachineStyles.PANEL_CLASS);
        return node;
    }

    @Test
    public void theShippedSheetParses() {
        StyleSheet sheet = StyleSheet.parse(MachineStyles.CSS);
        assertTrue("the shipped sheet has rules, or nothing below proves anything",
                sheet.getRules().size() > 0);
    }

    /**
     * The one the textual pass could not do.
     *
     * <p>A prefix is a DESCENDANT COMBINATOR and an element is not its own descendant, so every rule
     * aimed at the panel root itself silently stopped applying — the machine window opened as a sliver
     * with the rule carrying its width correct and unmatched. {@code @scope} means "this element or
     * below", so the scope root matches its own rules.</p>
     */
    @Test
    public void aScopedSheetReachesItsOwnRootAndNothingOutside() {
        UIDocument document = new UIDocument();
        document.styles().addStylesheet(StyleSheet.DEFAULT);

        UINode inScope = panel();
        UINode stranger = panel();
        document.append(inScope);
        document.append(stranger);

        document.styles().addStylesheet(StyleSheet.parse(MachineStyles.CSS), inScope);
        document.update(800f, 600f);

        assertEquals("the panel root takes its width from the server's own sheet",
                PANEL_WIDTH, inScope.box().width(), 0.5f);
        assertNotEquals("a panel outside the scope is untouched by it",
                PANEL_WIDTH, stranger.box().width(), 0.5f);
    }

    /**
     * Malformed CSS leaves a plain window, never a missing one.
     *
     * <p>And it gets there WITHOUT an exception: since 5.2 the parser drops a rule it cannot read,
     * warns with the selector text and keeps going, which is CSS's own rule on both engines. So
     * {@link ScopedSheets}'s catch is a guard rather than a path — the sheet installs, carries
     * nothing usable, and the panel is styled by the client's sheets alone. That is the behaviour
     * worth pinning, because it is the one a player sees; the exception is an implementation detail
     * that has already changed once.</p>
     */
    @Test
    public void malformedCssLeavesThePanelPlain() {
        UIDocument document = new UIDocument();
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        UINode root = panel();
        document.append(root);

        ScopedSheets sheets = new ScopedSheets(new ScopedSheets.Host() {
            @Override public void add(StyleSheet sheet, Styleable root) {
                document.styles().addStylesheet(sheet, root);
            }

            @Override public void remove(StyleSheet sheet, Styleable root) {
                document.styles().removeStylesheet(sheet, root);
            }
        });

        sheets.acquire("this is not css {{{", root);
        document.update(800f, 600f);

        assertNotEquals("garbage cannot have styled the panel",
                PANEL_WIDTH, root.box().width(), 0.5f);
    }

    /**
     * Two windows of one type share a PARSE and must not share a removal.
     *
     * <p>{@code removeStylesheet(sheet)} drops every installation of that sheet whatever it was
     * scoped to, so closing either window would unstyle the other — and only ever with two of them
     * open, which is why the fixture needs two. {@link ScopedSheets} keeps the roots per parse and
     * releases against the one that went.</p>
     */
    @Test
    public void closingOneWindowLeavesTheOtherStyled() {
        UIDocument document = new UIDocument();
        document.styles().addStylesheet(StyleSheet.DEFAULT);

        UINode first = panel();
        UINode second = panel();
        document.append(first);
        document.append(second);

        List<StyleSheet> added = new ArrayList<>();
        ScopedSheets sheets = new ScopedSheets(new ScopedSheets.Host() {
            @Override public void add(StyleSheet sheet, Styleable root) {
                added.add(sheet);
                document.styles().addStylesheet(sheet, root);
            }

            @Override public void remove(StyleSheet sheet, Styleable root) {
                document.styles().removeStylesheet(sheet, root);
            }
        });

        sheets.acquire(MachineStyles.CSS, first);
        sheets.acquire(MachineStyles.CSS, second);
        assertEquals("identical CSS is parsed once however many windows show it", 1, sheets.installed());
        assertEquals("...and installed once per window", 2, added.size());
        assertTrue("both installations are the same parse", added.get(0) == added.get(1));

        sheets.release(MachineStyles.CSS, first);
        document.update(800f, 600f);

        assertNotEquals("the closed window's panel is unstyled",
                PANEL_WIDTH, first.box().width(), 0.5f);
        assertEquals("the one still open keeps its styling",
                PANEL_WIDTH, second.box().width(), 0.5f);

        sheets.release(MachineStyles.CSS, second);
        assertEquals("the parse is dropped when the last window using it goes", 0, sheets.installed());
    }
}
