package com.crystalgui.app.shadergraph;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import com.google.gson.JsonElement;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * Where somebody was looking at a graph is <b>theirs</b>, and does not go in the file.
 *
 * <h3>Why it matters more here than in a text file</h3>
 *
 * <p>A workspace is shared. With the camera in the document, whoever saves last imposes their view on
 * everyone else who opens the graph — and two people editing it conflict over a field neither of them
 * touched, because the bytes differ for a reason that is not an edit.</p>
 *
 * <p>It is also view state by the engine's own boundary: looking around is not an edit, which is why it
 * never went on the undo stack either.</p>
 */
public class ShaderGraphViewStateTest extends UiDocumentTestBase {

    private ShaderGraphEditor editor;

    /** A step that changes nothing, so the bytes stay identical and only the version can tell. */
    private static final class NoOpEdit implements Edit {
        @Override
        public void apply() {
        }

        @Override
        public void undo() {
        }

        @Override
        public String label() {
            return "edit";
        }
    }

    private void build() {
        editor = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        editor.adopt(new byte[0]);
    }

    private ShaderGraphEditor second(byte[] content) {
        ShaderGraphEditor other = new ShaderGraphEditor();
        UIElement root = new UIElement().layout(l -> l.width(800).height(500));
        root.append(other);
        document.append(root);
        other.adopt(content);
        return other;
    }

    /**
     * <b>The one that fails against the version before this.</b> Panning and zooming changed the encoded
     * document, so the next save carried this person's camera to everybody.
     */
    @Test
    public void movingTheCameraDoesNotChangeTheFile() {
        build();
        byte[] before = editor.encode();

        editor.graph().setZoom(2.5f);
        editor.graph().setPan(-400f, 175f);

        assertArrayEquals("a pan and a zoom are not an edit and must not reach the file",
                before, editor.encode());
    }

    /** And it does not make the tab dirty either, which is the same fact one layer up. */
    @Test
    public void movingTheCameraDoesNotMakeTheDocumentDirty() {
        build();
        int version = editor.version();

        editor.graph().setZoom(1.8f);
        editor.graph().setPan(20f, 30f);

        assertEquals("looking around is not a change", version, editor.version());
    }

    /**
     * The counter-control: the camera has to survive <em>somewhere</em>, or this is a regression rather
     * than a fix. It goes in the session, which is per person.
     */
    @Test
    public void theCameraSurvivesInTheSession() {
        build();
        editor.graph().setZoom(2.5f);
        editor.graph().setPan(-400f, 175f);

        StateMap<JsonElement> saved = new StateMap<>(JsonOps.INSTANCE);
        editor.writeViewState(saved);

        // A SECOND PERSON'S EDITOR over the same file, with a camera of their own.
        ShaderGraphEditor other = second(editor.encode());
        other.graph().setZoom(1f);
        other.graph().setPan(0f, 0f);

        assertEquals("the file carries no camera, so opening it leaves theirs alone",
                1f, other.graph().getZoom(), 0.001f);

        other.readViewState(new StateMap<>(JsonOps.INSTANCE, saved.encode()));
        assertEquals(2.5f, other.graph().getZoom(), 0.001f);
        assertEquals(-400f, other.graph().getPanX(), 0.001f);
        assertEquals(175f, other.graph().getPanY(), 0.001f);
    }

    /**
     * <b>Editing a graph does not serialise it</b> — dirtiness is {@code version() != savedVersion}.
     *
     * <p>The assertion is a step that changes <em>no bytes</em>, which is the case a byte comparison
     * gets wrong: it re-encodes the whole graph and reports clean. That was the shape dirtiness had —
     * {@code encode()} against the bytes last read, for every open document on every change, so a graph
     * with an animated node serialised itself to JSON every frame to decide whether a tab needed an
     * asterisk.</p>
     */
    @Test
    public void aGraphEditDoesNotSerialiseTheGraph() {
        build();
        byte[] before = editor.encode();
        int version = editor.version();

        editor.graph().undoStack().push(new NoOpEdit());

        assertEquals("the bytes are identical, which is why an encode-and-compare says clean",
                new String(before, StandardCharsets.UTF_8),
                new String(editor.encode(), StandardCharsets.UTF_8));
        assertNotEquals("and the version still moved, which is what dirtiness is made of",
                version, editor.version());
    }

    /** A graph offers no three-way merge: a line-based merge of a JSON graph produces a broken graph. */
    @Test
    public void aGraphConflictOffersNoMerge() {
        build();
        assertFalse("text is the one thing a three-way merge is actually for", editor.mergeable());
    }

    /**
     * <b>Pan and zoom survive a session restore</b> — through the record {@code WorkbenchSession}
     * writes for every open tab and reads back on the next launch.
     */
    @Test
    public void aGraphsPanAndZoomSurviveASessionRestore() {
        build();
        editor.graph().setZoom(1.75f);
        editor.graph().setPan(-42f, 88f);

        StateMap<JsonElement> record = new StateMap<>(JsonOps.INSTANCE);
        editor.writeViewState(record);
        JsonElement persisted = record.encode();

        ShaderGraphEditor restored = second(editor.encode());
        restored.readViewState(new StateMap<>(JsonOps.INSTANCE, persisted));

        assertEquals(1.75f, restored.graph().getZoom(), 0.001f);
        assertEquals(-42f, restored.graph().getPanX(), 0.001f);
        assertEquals(88f, restored.graph().getPanY(), 0.001f);
    }

    /**
     * A graph saved before the camera moved out of the file still opens where it was left.
     *
     * <p>Nothing writes those keys now, but files carrying them exist. Read as a seed for somebody who
     * has no session entry for this graph, and overridden the moment they do.</p>
     */
    @Test
    public void anOlderFilesCameraIsStillReadAsASeed() {
        build();
        String withCamera = new String(editor.encode(), StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"settings\":{\"graph.view.zoom\":\"3.0\","
                        + "\"graph.view.panX\":\"-90.0\",\"graph.view.panY\":\"12.0\"},");

        ShaderGraphEditor opened = second(withCamera.getBytes(StandardCharsets.UTF_8));

        assertEquals(3.0f, opened.graph().getZoom(), 0.001f);
        assertEquals(-90.0f, opened.graph().getPanX(), 0.001f);
    }
}
