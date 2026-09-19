package com.crystalgui.app.shadergraph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>Where the floating panels sat survives a close and a reopen.</b>
 *
 * <p>Separate from {@code ShaderGraphViewStateTest}, which is about the CAMERA and its rule that a pan
 * must never reach the file. This is the other half of the same state — the Main Preview and the
 * blackboard — and it goes only through the session, for the same reason.</p>
 */
public class ShaderPanelViewStateTest extends UiDocumentTestBase {

    private static final String RECT = "40.0,60.0,300.0,200.0";

    private ShaderGraphView open() {
        ShaderGraphView editor = new ShaderGraphView();
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        editor.model().adopt(new byte[0]);
        document.update(W, H);
        return editor;
    }

    /**
     * The write half, which is where this failed: a panel is measured out of its live boxes, so an
     * editor asked after it has been detached reports nothing at all.
     */
    @Test
    public void aPlacedPanelIsWrittenIntoTheSessionState() {
        ShaderGraphView editor = open();

        StateMap<Object> placed = new StateMap<>(PlainOps.INSTANCE);
        placed.putString(ShaderGraphView.VIEW_PREVIEW_RECT, RECT);
        editor.readViewState(placed);
        document.update(W, H);

        StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
        editor.writeViewState(out);

        assertNotEquals("the panel's rectangle reached the session state",
                "", out.getString(ShaderGraphView.VIEW_PREVIEW_RECT, ""));
    }

    /** And the round trip a reopen actually performs. */
    @Test
    public void aSecondEditorOpensWherePanelsWereLeft() {
        ShaderGraphView first = open();

        StateMap<Object> placed = new StateMap<>(PlainOps.INSTANCE);
        placed.putString(ShaderGraphView.VIEW_PREVIEW_RECT, RECT);
        first.readViewState(placed);
        document.update(W, H);

        StateMap<Object> saved = new StateMap<>(PlainOps.INSTANCE);
        first.writeViewState(saved);

        ShaderGraphView reopened = open();
        reopened.readViewState(saved);
        document.update(W, H);

        StateMap<Object> after = new StateMap<>(PlainOps.INSTANCE);
        reopened.writeViewState(after);
        assertEquals("the reopened editor put its preview back where the first one left it",
                saved.getString(ShaderGraphView.VIEW_PREVIEW_RECT, "?"),
                after.getString(ShaderGraphView.VIEW_PREVIEW_RECT, ""));
    }
}
