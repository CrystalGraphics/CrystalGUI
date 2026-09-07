package com.crystalgui.widget.texteditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.find.SearchReplaceBar;

/**
 * <b>The find bar covers the strip it asked for, rather than sitting below it.</b>
 *
 * <p>The bar floats so it stays out of the editor's layout sums, and {@code syncEditorInset} writes the
 * editor a {@code padding-top} of exactly the bar's height so the text starts underneath it. The two are
 * circular: an absolutely positioned child is laid out from its parent's CONTENT box here, so the padding
 * the bar asked for displaces the bar by its own height. The editor then opens with a band of empty
 * document between the tab strip and the bar — reported as exactly that.</p>
 *
 * <p><b>Asserted against the padding, not against the editor's own y.</b> {@code Box.y()} is
 * parent-relative, so the editor's y is its offset inside the dock and the bar's is its offset inside the
 * editor; comparing the two is meaningless and passes whenever both happen to be zero, which is what the
 * first version of this test did.</p>
 */
public class FindBarSitsAtTheEditorsTopTest extends EditorTestBase {

    private SearchReplaceBar openBar() {
        build("class Main {" + NL + "    void run() {" + NL + "    }" + NL + "}" + NL);
        editor.openFind();
        for (int i = 0; i < 6; i++) settle();
        SearchReplaceBar bar = editor.searchBar();
        assertNotNull("the bar has no box", bar.box());
        return bar;
    }

    /** The editor's reserved strip, in pixels. */
    private float reservedStrip() {
        var padding = editor.getStyle().getComputed(LayoutProperties.PADDING_TOP);
        return padding == null ? 0f : padding.getValue();
    }

    @Test
    public void theBarCoversTheStripItReserved() {
        SearchReplaceBar bar = openBar();
        float strip = reservedStrip();
        assertTrue("the editor reserved no strip, so there is nothing to cover", strip > 0f);
        assertEquals("the bar is laid out below the strip it created, leaving a band of empty document"
                        + " between the tab strip and the bar",
                -strip, bar.box().y(), 0.5f);
    }

    /**
     * <b>The strip is reserved once, not twice.</b>
     *
     * <p>Two mechanisms displace the text and both count the same number: the editor's {@code
     * padding-top}, and {@code textOriginY} adding that padding again. An absolutely positioned child
     * already starts after the padding here, so the first line lands two bar-heights down. It was
     * invisible while the bar itself sat in the second strip -- moving the bar onto the editor's edge is
     * what exposed it, as a band between the bar and line one.</p>
     */
    @Test
    public void theFirstLineStartsDirectlyUnderTheBar() {
        SearchReplaceBar bar = openBar();
        UIElement firstLine = null;
        for (UIElement line : allWithClass(TextEditor.LINE_CLASS)) {
            if (firstLine == null || line.box().y() < firstLine.box().y()) firstLine = line;
        }
        assertNotNull("no view line was realised", firstLine);
        // BOTH IN THE EDITOR'S OWN SPACE: the bar's bottom edge and the first line's top edge should be
        // the same y. A strip counted twice puts a whole bar's height between them.
        assertEquals("the strip is reserved twice, so the first line starts a bar's height too low",
                bar.box().y() + bar.box().height(), firstLine.box().y(), 1.5f);
    }

    /**
     * <b>The bar spans the editor's full width, padding included.</b>
     *
     * <p>{@code width: 100%} resolves against the content box, and this editor carries
     * {@code padding-left: 1px; padding-right: 3px} — so the bar's ground stopped short of both edges and
     * the editor's own background showed through beside it. Against the find bar's darker ground that
     * strip reads as a rail running down the editor, which is what it was reported as; below the bar it
     * is continuous with the editor, which is why it looked full-height and exactly the background
     * colour.</p>
     */
    @Test
    public void theBarSpansTheEditorsFullWidth() {
        SearchReplaceBar bar = openBar();
        assertEquals("the bar leaves the editor's padding uncovered on the left",
                -editor.paddingLeft(), bar.box().x(), 0.5f);
        assertEquals("the bar does not reach the editor's edges",
                editor.box().width(), bar.box().width(), 0.5f);
    }

    /** ...and the strip is still reserved, which is the half that must keep working. */
    @Test
    public void theTextStartsBelowTheBar() {
        SearchReplaceBar bar = openBar();
        assertEquals("the strip is not the bar's height", bar.box().height(), reservedStrip(), 0.5f);
    }
}
