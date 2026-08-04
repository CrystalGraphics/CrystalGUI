package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.core.settings.SetSettingEdit;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.PlainOps;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.13 — the shader graph's own settings, on the general gear.
 *
 * <h3>What this pins</h3>
 * <p>These three values lived on {@code CgMasterNode}, which is the compiler's object and is never
 * serialised — so they were unsaved, unundoable, and invisible to a content hash. Each test below is one
 * of those three failures, stated as the behaviour that replaced it.</p>
 */
public class ShaderGraphSettingsTest {

    private final CgShaderNodeRegistry shaderNodes = CgShaderNodeRegistry.builtins();

    /** A document with an Output node, so there is something to compile toward. */
    private GraphDocument withMaster() {
        GraphDocument document = new GraphDocument();
        NodeTypeRegistry library = ShaderGraphBridge.asNodeLibrary(shaderNodes);
        document.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(0f, 0f));
        return document;
    }

    private String emit(GraphDocument document) {
        return ShaderGraphBridge.compile(document, shaderNodes, new CgMasterNode()).source();
    }

    // ── They reach the emitted file ─────────────────────────────────────────

    /** Nothing set still compiles, using the declared defaults. */
    @Test
    public void aDocumentThatSaysNothingGetsTheDeclaredDefaults() {
        String source = emit(withMaster());
        assertTrue(source, source.contains("#type spatial"));
        assertTrue(source, source.contains("\"RenderType\" = \"Opaque\""));
        assertTrue(source, source.contains("Queue = \"Geometry\""));
    }

    /**
     * <b>A setting changes the generated shader.</b>
     *
     * <p>The whole point, and the thing that was impossible while the values sat on an object the
     * document had no way to reach.</p>
     */
    @Test
    public void aSettingChangesTheEmittedShader() {
        GraphDocument document = withMaster();
        document.settings().set(SettingsLayer.DOCUMENT, ShaderGraphSettings.QUEUE, "Transparent");
        document.settings().set(SettingsLayer.DOCUMENT, ShaderGraphSettings.RENDER_TYPE, "Transparent");

        String source = emit(document);
        assertTrue(source, source.contains("Queue = \"Transparent\""));
        assertTrue(source, source.contains("\"RenderType\" = \"Transparent\""));
    }

    /**
     * The master is written at compile time and never read from as though it were storage.
     *
     * <p>Two documents compiled through the <em>same</em> master must not contaminate each other — which
     * they would if the master kept whatever the last compile left on it.</p>
     */
    @Test
    public void oneMasterCompilingTwoDocumentsDoesNotLeakBetweenThem() {
        CgMasterNode shared = new CgMasterNode();
        GraphDocument transparent = withMaster();
        transparent.settings().set(SettingsLayer.DOCUMENT, ShaderGraphSettings.QUEUE, "Transparent");
        GraphDocument plain = withMaster();

        String first = ShaderGraphBridge.compile(transparent, shaderNodes, shared).source();
        String second = ShaderGraphBridge.compile(plain, shaderNodes, shared).source();

        assertTrue(first.contains("Queue = \"Transparent\""));
        assertTrue("the second document never said Transparent, so it must not get it",
                second.contains("Queue = \"Geometry\""));
    }

    /** An enumerated setting refuses a value the shader parser has never heard of. */
    @Test
    public void anUnknownQueueFallsBackRatherThanReachingTheFile() {
        GraphDocument document = withMaster();
        document.settings().setRaw(SettingsLayer.DOCUMENT, "shader.queue", "NotAQueue");
        assertTrue(emit(document).contains("Queue = \"Geometry\""));
    }

    // ── They are undoable ───────────────────────────────────────────────────

    /** Document state, so Ctrl+Z reaches it — and undoing lands on absent, not on the default's text. */
    @Test
    public void aSettingIsUndoable() {
        GraphDocument document = withMaster();
        UndoStack undo = new UndoStack();

        undo.execute(SetSettingEdit.of(document.settings(), SettingsLayer.DOCUMENT,
                ShaderGraphSettings.QUEUE, "Overlay"));
        assertTrue(emit(document).contains("Queue = \"Overlay\""));

        undo.undo();
        assertTrue(emit(document).contains("Queue = \"Geometry\""));
        assertFalse("undo restores absence, not a pinned default",
                document.settings().isSet(ShaderGraphSettings.QUEUE));
    }

    // ── They survive a save, and they change identity ───────────────────────

    /** A setting round-trips through the document codec. */
    @Test
    public void settingsRoundTripThroughTheDocumentCodec() {
        GraphDocument document = withMaster();
        document.settings().set(SettingsLayer.DOCUMENT, ShaderGraphSettings.QUEUE, "AlphaTest");
        document.settings().set(SettingsLayer.DOCUMENT, ShaderGraphSettings.VERTEX_FORMAT,
                "pos2_uv2_col4ub");

        Object encoded = GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, document);
        GraphDocument reloaded = GraphCodecs.DOCUMENT.decode(PlainOps.INSTANCE, encoded);

        assertEquals("AlphaTest", reloaded.resolve(ShaderGraphSettings.QUEUE));
        assertEquals("pos2_uv2_col4ub", reloaded.resolve(ShaderGraphSettings.VERTEX_FORMAT));
        assertTrue(emit(reloaded).contains("#type pos2_uv2_col4ub"));
    }

    /**
     * <b>Two graphs differing only in a setting must not hash the same.</b>
     *
     * <p>The concrete failure that motivated the whole item: while the queue lived on the master, these
     * two encoded byte-identically, so a content-addressed cache would have served one for the other.</p>
     */
    @Test
    public void aSettingChangesTheDocumentsContentHash() {
        GraphDocument geometry = withMaster();
        GraphDocument transparent = new GraphDocument();
        for (var node : geometry.nodes()) transparent.addNode(node);
        transparent.settings().set(SettingsLayer.DOCUMENT, ShaderGraphSettings.QUEUE, "Transparent");

        assertNotEquals(
                ContentHash.of(PlainOps.INSTANCE, GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, geometry)),
                ContentHash.of(PlainOps.INSTANCE, GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, transparent)));
    }

    /** A document with no settings encodes exactly as it did before they existed. */
    @Test
    public void anUntouchedDocumentIsUnchangedByTheFeature() {
        GraphDocument document = withMaster();
        String encoded = String.valueOf(GraphCodecs.DOCUMENT.encode(PlainOps.INSTANCE, document));
        assertFalse("an empty layer must be omitted, not written as an empty map",
                encoded.contains("settings"));
    }

    /** Clearing a document drops its settings too — they describe the document, not the editor. */
    @Test
    public void clearingADocumentDropsItsSettings() {
        GraphDocument document = withMaster();
        document.settings().set(SettingsLayer.DOCUMENT, ShaderGraphSettings.QUEUE, "Overlay");
        document.clear();
        assertFalse(document.settings().isSet(ShaderGraphSettings.QUEUE));
    }

    /** A document is its own root: it must not inherit another open graph's options. */
    @Test
    public void aDocumentHasNoEnclosingScope() {
        assertNull(new GraphDocument().settingsParent());
    }
}
