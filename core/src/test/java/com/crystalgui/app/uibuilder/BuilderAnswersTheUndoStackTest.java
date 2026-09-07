package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.surface.SurfaceEditor;

/**
 * <b>Undo reaches the builder.</b>
 *
 * <p>Everything was wired and none of it was reachable. The surface IS an undo scope, the stack it hands
 * out IS the document's history, and a resize DOES record an edit into it — but
 * {@code BuilderSurface.getData} answered its own three keys and ended in {@code return null} rather
 * than asking its supertype. An override like that does not fall through: it is the provider the walk
 * found, and the walk stops at the first one that answers. So every key {@code SurfaceEditor} answers
 * was unreachable through the builder — the undo stack above all, which is how a command finds a
 * history.</p>
 *
 * <p>Ctrl+Z therefore did nothing on the canvas: the stack existed, the edits were in it, and
 * {@code enabledWhereData} could not see it, so the command reported itself disabled. Cut, Copy and
 * Paste went the same way, and anything resolving {@code SURFACE}.</p>
 */
public class BuilderAnswersTheUndoStackTest extends UiDocumentTestBase {

    @Test
    public void aNodeOnTheCanvasResolvesTheDocumentsHistory() {
        UiBuilderDocument model = openBuilder();
        BuilderEditor editor = editorFor(model);
        UIElement node = model.root().children().get(0);

        // THE WALK CTRL+Z USES -- DataContext, outward through commandParent(), not UndoScope's own.
        assertSame("a command asking from the canvas cannot find the history",
                model.history(), DataContext.from(node).get(UiDataKeys.UNDO_STACK));

        // AND THE KEYS THE SUPERTYPE ANSWERS ARE STILL THERE, which is the general shape of the bug:
        // an override that answers some keys and drops the rest.
        assertNotNull("the surface itself became unreachable",
                DataContext.from(node).get(SurfaceEditor.SURFACE));
    }

    /**
     * <b>And the keystroke itself undoes.</b>
     *
     * <p>The end of the chain, driven the way a user drives it: focus in the surface, an edit recorded,
     * {@code Mod+Z} pressed. Everything above asserts a link; this asserts the whole thing, which is what
     * was reported broken.</p>
     */
    @Test
    public void modZUndoesOnTheCanvas() {
        UiBuilderDocument model = openBuilder();
        BuilderEditor editor = editorFor(model);
        UIElement node = model.root().children().get(0);
        editor.selection().selectOnly(node);
        document.focus().requestFocus(editor.surface());
        document.update(W, H);

        var before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        node.layout(l -> l.width(99));
        model.apply(new BuilderEdit.SetInlineStyle(node, before,
                InlineStyleCodec.encode(JsonOps.INSTANCE, node)));
        document.update(W, H);
        assertEquals(99f, node.box().width(), 0.01f);

        assertTrue("Mod+Z was not handled at all", chord(CgKeyCodes.KEY_Z, CgModifiers.CTRL));
        releaseModifiers();
        document.update(W, H);
        assertEquals("Mod+Z did not undo the edit", 40f, node.box().width(), 0.01f);
    }

    /** And an edit made on the canvas is genuinely undoable through it. */
    @Test
    public void anEditOnTheCanvasUndoes() {
        UiBuilderDocument model = openBuilder();
        editorFor(model);
        UIElement node = model.root().children().get(0);

        var before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        node.layout(l -> l.width(99));
        var after = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        model.apply(new BuilderEdit.SetInlineStyle(node, before, after));
        document.update(W, H);
        assertEquals(99f, node.box().width(), 0.01f);

        DataContext.from(node).get(UiDataKeys.UNDO_STACK).undo();
        document.update(W, H);
        assertEquals("undo did not put the box back", 40f, node.box().width(), 0.01f);
    }

    /**
     * <b>An undo selects what it changed.</b>
     *
     * <p>A reversal you cannot see is indistinguishable from a key that did nothing — on a canvas the
     * changed node may be scrolled off, or one of forty that look alike. Selecting it puts the outline,
     * the handles and the inspector on the thing that moved.</p>
     */
    @Test
    public void anUndoSelectsTheNodeItChanged() {
        UiBuilderDocument model = openBuilder();
        BuilderEditor editor = editorFor(model);
        UIElement node = model.root().children().get(0);

        var before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        node.layout(l -> l.width(99));
        model.apply(new BuilderEdit.SetInlineStyle(node, before,
                InlineStyleCodec.encode(JsonOps.INSTANCE, node)));
        // Deliberately selecting something else, so the assertion cannot pass by the selection simply
        // never having moved.
        editor.selection().selectOnly(null);
        document.update(W, H);

        model.history().undo();
        document.update(W, H);
        assertSame("an undo left the canvas showing nothing that changed",
                node, editor.selection().node());
    }

    /** A redo says what it put back, for the same reason. */
    @Test
    public void aRedoSelectsItToo() {
        UiBuilderDocument model = openBuilder();
        BuilderEditor editor = editorFor(model);
        UIElement node = model.root().children().get(0);

        var before = InlineStyleCodec.encode(JsonOps.INSTANCE, node);
        node.layout(l -> l.width(99));
        model.apply(new BuilderEdit.SetInlineStyle(node, before,
                InlineStyleCodec.encode(JsonOps.INSTANCE, node)));
        model.history().undo();
        editor.selection().selectOnly(null);
        document.update(W, H);

        model.history().redo();
        document.update(W, H);
        assertSame(node, editor.selection().node());
    }

    private UiBuilderDocument openBuilder() {
        UIElementRegistry.bootstrap();
        UiBuilderDocument model = new UiBuilderDocument(
                UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:x.cgui");
        model.root().append(new UIElement().layout(l -> l.width(40).height(20)));
        return model;
    }

    private BuilderEditor editorFor(UiBuilderDocument model) {
        BuilderEditor editor = new BuilderEditor(model);
        UIElement host = new UIElement().layout(l -> l.width(400).height(300));
        host.append(editor.view());
        document.append(host);
        document.update(W, H);
        return editor;
    }
}
