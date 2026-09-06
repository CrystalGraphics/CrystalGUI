package com.crystalgui.app.shadergraph;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>Being made the active tab while out of the tree.</b>
 *
 * <p>A dock rebuild detaches every group, syncs each one — which announces the active panel — and only
 * then re-attaches what it built. So {@code activated(true)} routinely arrives at a view whose element is
 * not in any document, where a status bar resolved by walking outward does not exist. That threw, and it
 * threw from inside the rebuild, so the stack named the dock rather than the status entry.</p>
 */
public class ShaderGraphStatusWhileDetachedTest extends UiDocumentTestBase {

    private final StatusBar bar = new StatusBar();
    private ShaderGraphEditor editor;
    private UIElement root;

    private void build() {
        document.addDataProvider(this::statusBarFor);
        editor = new ShaderGraphEditor();
        root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
        editor.recompile();
    }

    private Object statusBarFor(DataKey<?> key) {
        return key == UiDataKeys.STATUS_BAR ? bar : null;
    }

    /** The baseline the rest is measured against: attached and active writes the summary. */
    @Test
    public void anActiveTabWritesItsCompileSummary() {
        build();
        editor.activated(true);
        assertEquals(1, bar.size());
        assertEquals(ShaderGraphEditor.COMPILE_STATUS, bar.idOf(bar.entries().get(0)));
    }

    /**
     * <b>The crash.</b> Activated while detached, which is what a dock rebuild does.
     *
     * <p>Nothing is written — there is nowhere to write it — and nothing is thrown.</p>
     */
    @Test
    public void activatingWhileDetachedWritesNothingAndDoesNotThrow() {
        build();
        root.remove(editor);
        frame();

        editor.activated(true);

        assertEquals("no bar, no entry", 0, bar.size());
    }

    /**
     * <b>And the summary is not lost, only deferred.</b>
     *
     * <p>A dropped entry would have left the tab in front with an empty bar until its next compile, which
     * for a graph nobody is editing is never.</p>
     */
    @Test
    public void reattachingPublishesTheSummaryThatCouldNotBeWritten() {
        build();
        root.remove(editor);
        frame();
        editor.activated(true);
        assertEquals(0, bar.size());

        root.append(editor);
        frame();

        assertEquals("written on the way back in", 1, bar.size());
        assertEquals(ShaderGraphEditor.COMPILE_STATUS, bar.idOf(bar.entries().get(0)));
    }

    /** A tab that is not in front stays off the bar however often it is re-attached. */
    @Test
    public void reattachingAnInactiveTabWritesNothing() {
        build();
        editor.activated(false);
        root.remove(editor);
        frame();

        root.append(editor);
        frame();

        assertEquals(0, bar.size());
    }
}
