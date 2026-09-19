package com.crystalgui.app.uibuilder.canvas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.google.gson.JsonElement;

/**
 * <b>A pane's copy of a UI document says exactly what the document says</b>, after every kind of edit, its undo, and
 * a reopen — and keeps its nodes where it can, since a copy rebuilt is a canvas that loses its boxes.
 */
public class ShownTreeTest {

    private static final UIElementMirror<JsonElement> MIRROR =
            new UIElementMirror<>(JsonOps.INSTANCE, UIElementMirror.Keys.DOCUMENT);

    private UiBuilderDocument model;
    private ShownTree shown;
    private UIElement a;
    private UIElement b;

    @Before
    public void aDocumentAndItsCopy() {
        UIElementRegistry.bootstrap();
        model = new UiBuilderDocument(UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8), "probe:a.cgui");
        a = new UIElement();
        a.setId("a");
        b = new UIElement();
        b.setId("b");
        model.apply(new BuilderEdit.Insert(model.root(), a, 0));
        model.apply(new BuilderEdit.Insert(model.root(), b, 1));
        shown = new ShownTree(model);
    }

    private void assertSameTree() {
        shown.sync();
        assertEquals(MIRROR.describe(model.root()), MIRROR.describe(shown.root()));
    }

    @Test
    public void theCopyIsNotTheDocumentsTree() {
        assertSameTree();
        assertNotSame(model.root(), shown.root());
        assertNotNull(shown.shown(a));
        assertSame(a, shown.source(shown.shown(a)));
    }

    @Test
    public void insertRemoveAndMoveReachTheCopy() {
        UIElement c = new UIElement();
        model.apply(new BuilderEdit.Insert(a, c, 0));
        assertSameTree();
        UIElement keptB = shown.shown(b);

        model.apply(new BuilderEdit.Move(c, a, 0, b, 0));
        assertSameTree();
        assertSame("a moved node keeps its copy, and a sibling untouched keeps its own", keptB, shown.shown(b));

        model.apply(new BuilderEdit.Remove(b, c, 0));
        assertSameTree();
        assertNull("a removed node is forgotten", shown.shown(c));
    }

    @Test
    public void identityAndStyleReachTheCopy() {
        model.apply(new BuilderEdit.SetId(a, "a", "renamed"));
        model.apply(new BuilderEdit.SetClasses(a, List.of(), List.of("big", "red")));
        assertSameTree();
        assertTrue(shown.shown(a).hasClass("red"));
    }

    /** A declaration the document drops leaves the copy too: merged instead, an undone {@code absolute} stays. */
    @Test
    public void aRemovedDeclarationLeavesTheCopy() {
        JsonElement before = InlineStyleCodec.encode(JsonOps.INSTANCE, a);
        StyleGroup.inlinePipeline(a.getStyle().getLayoutGroup(), l -> l.width(99));
        model.apply(new BuilderEdit.SetInlineStyle(a, before, InlineStyleCodec.encode(JsonOps.INSTANCE, a)));
        assertSameTree();
        model.history().undo();
        assertSameTree();
    }

    @Test
    public void undoAndRedoReachTheCopy() {
        model.apply(new BuilderEdit.SetId(b, "b", "second"));
        model.apply(new BuilderEdit.Remove(model.root(), a, 0));
        assertSameTree();
        model.history().undo();
        model.history().undo();
        assertSameTree();
        model.history().redo();
        assertSameTree();
    }

    @Test
    public void aReopenReplacesTheCopy() {
        UIElement before = shown.root();
        model.adopt(UiBuilderDocument.EMPTY.getBytes(StandardCharsets.UTF_8));
        assertTrue("the caller is told to show the new root", shown.sync());
        assertNotSame(before, shown.root());
        assertSameTree();
    }
}
