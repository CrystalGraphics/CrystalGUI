package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link TextEditor#setGutterOnRight} — that the gutter box <b>actually moves</b>.
 *
 * <h3>Why this test exists, specifically</h3>
 *
 * <p>The feature shipped broken twice, and both times every number was right. The arithmetic
 * ({@code gutterLeft}, {@code textOriginX}, {@code textViewportWidth}) computed correct values, the flag
 * was set, and the fold arrows and revert chevrons — which read those numbers — moved to the new side.
 * The <b>box did not</b>, so the gutter painted over the first several characters of every line while the
 * text had already stopped reserving room for it.</p>
 *
 * <p>The cause was the cascade, not the geometry: the placement was written at {@code DEFAULT} origin,
 * which sits <em>below</em> the user-agent sheet, and {@code ua/editor.css} styles {@code .__gutter__}.
 * The write was made and thrown away. So the assertion here is deliberately not about any computed value —
 * every one of those was already correct — but about <b>where the element ended up</b>, which is the only
 * question that was ever in doubt.</p>
 */
public class MirroredGutterTest {

    private static final float WIDTH = 600f;

    private static TextEditor laidOut(boolean mirrored) {
        TestPlatformService.install();
        UIElement root = new UIElement();
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheet.parse(
                ".probe-editor { width: " + (int) WIDTH + "px; height: 400px; }"));
        window.init(1200, 800);

        TextEditor editor = new TextEditor();
        editor.addClass("probe-editor");
        root.addChild(editor);

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) text.append("import java.util.Thing").append(i).append(";\n");
        editor.setText(text.toString());
        editor.setGutterOnRight(mirrored);

        for (int i = 0; i < 8; i++) window.updateWithoutPainting();
        return editor;
    }

    private static float gutterX(TextEditor editor) {
        return editor.gutterElement().getWindowX() - editor.getWindowX();
    }

    @Test
    public void byDefaultTheGutterIsOnTheLeft() {
        TextEditor editor = laidOut(false);

        assertEquals("the gutter starts at the content-box origin", 0f, gutterX(editor), 2f);
        assertTrue("and the text is pushed past it", editor.textOriginX() > editor.gutterWidth());
    }

    /** <b>The one that failed twice.</b> */
    @Test
    public void mirroredTheGutterBoxMovesToTheFarEdge() {
        TextEditor editor = laidOut(true);

        assertTrue("the gutter must be past the middle of the editor, not at the origin: " + gutterX(editor),
                gutterX(editor) > WIDTH / 2f);
        assertEquals("and it must sit where the widget said it would",
                editor.gutterLeft(), gutterX(editor), 2f);
    }

    /** And the text reclaims the space, rather than the two overlapping. */
    @Test
    public void mirroredTheTextStartsAtTheLeftEdgeAndStopsBeforeTheGutter() {
        TextEditor editor = laidOut(true);

        assertTrue("the text no longer skips a gutter that is not there: " + editor.textOriginX(),
                editor.textOriginX() < editor.gutterWidth());
        assertTrue("and its viewport stops short of the gutter",
                editor.textViewportLeft() + editor.textViewportWidth() <= editor.gutterLeft() + 2f);
    }

    /**
     * The revert chevrons get a column of their own inside the gutter.
     *
     * <p>Reserved rather than overlaid: a chevron drawn on top of the code is legible only where the line
     * happens to be short, and it covers the very text the reader is comparing.</p>
     */
    @Test
    public void offeringRevertChevronsWidensTheGutterToHoldThem() {
        TextEditor plain = laidOut(true);
        float without = plain.gutterWidth();
        assertEquals("nothing reserved while nothing offers any", 0f, plain.gutterChevronWidth(), 0.01f);

        plain.setDiffRevertHandler(index -> { });
        UIWindow window = plain.getAttachedWindow();
        for (int i = 0; i < 8; i++) window.updateWithoutPainting();

        assertTrue("a column is reserved", plain.gutterChevronWidth() > 0f);
        assertTrue("and the gutter grew by it: " + without + " -> " + plain.gutterWidth(),
                plain.gutterWidth() > without);
    }

    /**
     * The vertical bar mirrors with the gutter, to the OUTER edge.
     *
     * <p>Left on the right it would sit between the two panes' gutters — in the one place a side-by-side
     * view has no room, and on the side the reader is comparing across.</p>
     */
    @Test
    public void mirroredTheVerticalBarMovesToTheOuterEdge() {
        TextEditor plain = laidOut(false);
        float onTheRight = plain.verticalScroller().getWindowX() - plain.getWindowX();
        assertTrue("ordinarily the bar is at the far right: " + onTheRight, onTheRight > WIDTH / 2f);

        TextEditor mirrored = laidOut(true);
        float onTheLeft = mirrored.verticalScroller().getWindowX() - mirrored.getWindowX();
        assertTrue("mirrored it is at the far left: " + onTheLeft, onTheLeft < WIDTH / 4f);
    }

    /** Toggling back restores the ordinary layout, so the flag is not one-way. */
    @Test
    public void turningItOffPutsTheGutterBack() {
        TextEditor editor = laidOut(true);
        assertTrue(gutterX(editor) > WIDTH / 2f);

        editor.setGutterOnRight(false);
        UIWindow window = editor.getAttachedWindow();
        for (int i = 0; i < 8; i++) window.updateWithoutPainting();

        assertEquals("back to the origin", 0f, gutterX(editor), 2f);
    }
}
