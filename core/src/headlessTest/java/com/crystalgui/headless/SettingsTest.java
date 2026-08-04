package com.crystalgui.headless;

import com.crystalgui.core.settings.SetSettingEdit;
import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsChange;
import com.crystalgui.core.settings.SettingsCodec;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsModel;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.core.settings.SettingsScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.PlainOps;
import org.junit.Test;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * P6.1.13 — the settings gear, ported from VS Code's {@code platform/configuration/}.
 *
 * <p>Headless on purpose: every one of these is a statement about the <b>model</b>, and a model that
 * needed a window to be testable would not be one a dedicated server could use. The panel half is a
 * separate concern and a separate source set.</p>
 */
public class SettingsTest {

    private static final Setting<Integer> FONT_SIZE =
            Setting.integer("editor.fontSize", "Font Size", 14);
    private static final Setting<Boolean> WRAP =
            Setting.bool("editor.wordWrap", "Word Wrap", false);
    private static final Setting<String> QUEUE =
            Setting.select("shader.queue", "Queue",
                    List.of("Background", "Geometry", "AlphaTest", "Transparent", "Overlay"), "Geometry");

    // ── Precedence ──────────────────────────────────────────────────────────

    /** The declaration answers when nothing else does — that is what DEFAULT means. */
    @Test
    public void anUnsetSettingReadsItsDeclaredDefault() {
        Settings settings = new Settings();
        assertEquals(Integer.valueOf(14), settings.get(FONT_SIZE));
        assertEquals(SettingsLayer.DEFAULT, settings.sourceOf(FONT_SIZE));
        assertFalse(settings.isSet(FONT_SIZE));
    }

    /**
     * <b>The highest layer holding a value wins, and removing it falls back rather than blanking.</b>
     *
     * <p>Walked top-down here layer by layer, because the failure this guards is precedence drifting from
     * the enum that declares it — which cannot be caught by testing any single pair.</p>
     */
    @Test
    public void theHighestLayerWinsAndRemovalFallsBack() {
        Settings settings = new Settings();
        settings.set(SettingsLayer.USER, FONT_SIZE, 16);
        assertEquals(Integer.valueOf(16), settings.get(FONT_SIZE));

        settings.set(SettingsLayer.WORKSPACE, FONT_SIZE, 18);
        assertEquals(Integer.valueOf(18), settings.get(FONT_SIZE));

        settings.set(SettingsLayer.DOCUMENT, FONT_SIZE, 20);
        assertEquals(Integer.valueOf(20), settings.get(FONT_SIZE));

        settings.set(SettingsLayer.MEMORY, FONT_SIZE, 22);
        assertEquals("MEMORY is highest — a debug toggle must beat everything",
                Integer.valueOf(22), settings.get(FONT_SIZE));

        // Now unwind, and each removal must uncover the next one down rather than the default.
        settings.reset(SettingsLayer.MEMORY, FONT_SIZE);
        assertEquals(Integer.valueOf(20), settings.get(FONT_SIZE));
        settings.reset(SettingsLayer.DOCUMENT, FONT_SIZE);
        assertEquals(Integer.valueOf(18), settings.get(FONT_SIZE));
        settings.reset(SettingsLayer.WORKSPACE, FONT_SIZE);
        assertEquals(Integer.valueOf(16), settings.get(FONT_SIZE));
        settings.reset(SettingsLayer.USER, FONT_SIZE);
        assertEquals(Integer.valueOf(14), settings.get(FONT_SIZE));
    }

    /** DEFAULT is the declaration, not storage — writing to it is a bug worth naming. */
    @Test
    public void theDefaultLayerIsNotStorage() {
        Settings settings = new Settings();
        assertThrows(IllegalArgumentException.class, () -> settings.layer(SettingsLayer.DEFAULT));
    }

    /** A setting writable nowhere could only ever report its default, so declaring one must fail. */
    @Test
    public void aSettingWritableNowhereIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Setting.bool("x.y", "Y", false).writableAt(SettingsLayer.DEFAULT));
    }

    // ── inspect ─────────────────────────────────────────────────────────────

    /**
     * <b>"Explicitly set to the default" and "never set" are different states.</b>
     *
     * <p>They read identically through {@code get}, and a settings UI that cannot tell them apart
     * discards a user's deliberate choice the first time it offers Reset. This is the whole reason
     * {@code inspect} returns every layer rather than the winner.</p>
     */
    @Test
    public void inspectSeparatesExplicitlyDefaultFromNeverSet() {
        Settings settings = new Settings();
        assertFalse("never set", settings.inspect(FONT_SIZE).isOverridden());

        settings.set(SettingsLayer.USER, FONT_SIZE, 14);   // the same value the declaration has
        assertEquals("indistinguishable through get()", Integer.valueOf(14), settings.get(FONT_SIZE));
        assertTrue("but it IS a decision, and inspect must say so",
                settings.inspect(FONT_SIZE).isOverridden());
        assertEquals("14", settings.inspect(FONT_SIZE).at(SettingsLayer.USER));
    }

    /** Every layer reports separately, and the effective one is named. */
    @Test
    public void inspectReportsEachLayer() {
        Settings settings = new Settings();
        settings.set(SettingsLayer.USER, FONT_SIZE, 16);
        settings.set(SettingsLayer.DOCUMENT, FONT_SIZE, 20);

        Settings.Inspection inspection = settings.inspect(FONT_SIZE);
        assertEquals("16", inspection.at(SettingsLayer.USER));
        assertEquals("20", inspection.at(SettingsLayer.DOCUMENT));
        assertNull("a layer that says nothing is absent, not empty",
                inspection.at(SettingsLayer.WORKSPACE));
        assertEquals(SettingsLayer.DOCUMENT, inspection.effectiveLayer());
    }

    // ── Change events ───────────────────────────────────────────────────────

    /** One write, one signal, naming the key and the layer. */
    @Test
    public void aWriteEmitsExactlyOnce() {
        Settings settings = new Settings();
        List<SettingsChange> heard = new ArrayList<>();
        settings.onChanged.connect(heard::add);

        settings.set(SettingsLayer.USER, FONT_SIZE, 16);
        assertEquals(1, heard.size());
        assertEquals("editor.fontSize", heard.get(0).key());
        assertEquals(SettingsLayer.USER, heard.get(0).layer());

        settings.set(SettingsLayer.USER, FONT_SIZE, 16);
        assertEquals("a no-op write must not announce itself", 1, heard.size());
    }

    /**
     * {@code affects} is what lets a listener skip a change that is not its business.
     *
     * <p>Without it every panel rebuilds on every write — and a panel that rebuilds destroys the control
     * the user is currently dragging.</p>
     */
    @Test
    public void affectsMatchesSectionsButNotPrefixes() {
        SettingsChange change = new SettingsChange("editor.fontSize", SettingsLayer.USER);
        assertTrue(change.affects(FONT_SIZE));
        assertFalse(change.affects(QUEUE));
        assertTrue(change.affects("editor"));
        assertTrue(change.affects("editor.fontSize"));
        assertFalse("a dot is required, or 'editor' also claims 'editorial'",
                change.affects("edit"));
        assertFalse(new SettingsChange("editorial.mode", SettingsLayer.USER).affects("editor"));
    }

    /** Loading a whole layer reports the keys that actually differ, not one blanket "everything". */
    @Test
    public void replacingALayerReportsOnlyWhatChanged() {
        Settings settings = new Settings();
        settings.set(SettingsLayer.DOCUMENT, FONT_SIZE, 16);
        settings.set(SettingsLayer.DOCUMENT, WRAP, true);

        List<String> heard = new ArrayList<>();
        settings.onChanged.connect(change -> heard.add(change.key()));
        // fontSize keeps its value; wordWrap goes; queue arrives.
        settings.replaceLayer(SettingsLayer.DOCUMENT,
                Map.of("editor.fontSize", "16", "shader.queue", "Transparent"));

        assertEquals(2, heard.size());
        assertTrue(heard.contains("editor.wordWrap"));
        assertTrue(heard.contains("shader.queue"));
        assertFalse("an unchanged key must stay quiet", heard.contains("editor.fontSize"));
    }

    // ── Undo ────────────────────────────────────────────────────────────────

    /**
     * <b>Undoing a first-ever write must restore ABSENT, not the default's text.</b>
     *
     * <p>An absent key means "whatever the declaration says"; a stored one is a decision. Undo that
     * writes {@code "14"} leaves the document pinning a default it had previously left open, so a later
     * build that changes that default can never reach it — and nothing in the file reveals that it was
     * never chosen.</p>
     */
    @Test
    public void undoRestoresAbsenceRatherThanTheDefault() {
        Settings settings = new Settings();
        UndoStack undo = new UndoStack();

        undo.execute(SetSettingEdit.of(settings, SettingsLayer.DOCUMENT, FONT_SIZE, 20));
        assertTrue(settings.isSet(FONT_SIZE));

        undo.undo();
        assertEquals(Integer.valueOf(14), settings.get(FONT_SIZE));
        assertFalse("the key must be GONE, not stored as its default",
                settings.isSet(FONT_SIZE));
        assertNull(settings.layer(SettingsLayer.DOCUMENT).get("editor.fontSize"));
    }

    /** Consecutive writes to one key collapse, so dragging a slider is one undo step. */
    @Test
    public void consecutiveWritesToOneKeyMerge() {
        Settings settings = new Settings();
        UndoStack undo = new UndoStack();

        undo.beginMergeRun();
        for (int size = 15; size <= 20; size++) {
            undo.execute(SetSettingEdit.of(settings, SettingsLayer.DOCUMENT, FONT_SIZE, size));
        }
        undo.endMergeRun();

        assertEquals(Integer.valueOf(20), settings.get(FONT_SIZE));
        assertEquals("a drag is one step", 1, undo.undoDepth());

        undo.undo();
        assertFalse("and it undoes to where the drag started — absent", settings.isSet(FONT_SIZE));
    }

    /** Different keys, or the same key at different layers, are never one step. */
    @Test
    public void unrelatedWritesDoNotMerge() {
        Settings settings = new Settings();
        UndoStack undo = new UndoStack();
        undo.beginMergeRun();
        undo.execute(SetSettingEdit.of(settings, SettingsLayer.DOCUMENT, FONT_SIZE, 20));
        undo.execute(SetSettingEdit.of(settings, SettingsLayer.DOCUMENT, WRAP, true));
        undo.execute(SetSettingEdit.of(settings, SettingsLayer.MEMORY, FONT_SIZE, 30));
        undo.endMergeRun();
        assertEquals(3, undo.undoDepth());
    }

    // ── Scope resolution ────────────────────────────────────────────────────

    /** A minimal scope chain — the shape {@code UIElement} and {@code GraphDocument} both take. */
    private static final class Scope implements SettingsScope {
        private final Settings settings = new Settings();
        @Nullable private final SettingsScope parent;

        Scope(@Nullable SettingsScope parent) {
            this.parent = parent;
        }

        @Override public Settings settings() { return settings; }
        @Override public SettingsScope settingsParent() { return parent; }
    }

    /**
     * <b>Reading walks outward and stops at the first scope with an answer.</b>
     *
     * <p>This is VS Code's folder-over-workspace precedence obtained structurally instead of by URI —
     * the same argument {@code Keymap} makes for scoping bindings to the tree rather than to hand-
     * maintained {@code when} clauses.</p>
     */
    @Test
    public void resolutionWalksOutwardToTheNearestAnswer() {
        Scope root = new Scope(null);
        Scope panel = new Scope(root);
        Scope control = new Scope(panel);

        root.settings().set(SettingsLayer.USER, FONT_SIZE, 14);
        assertEquals("inherited from the root", Integer.valueOf(14), control.resolve(FONT_SIZE));

        panel.settings().set(SettingsLayer.USER, FONT_SIZE, 18);
        assertEquals("the nearer scope wins", Integer.valueOf(18), control.resolve(FONT_SIZE));
        assertSame(panel, control.scopeDefining("editor.fontSize"));

        control.settings().set(SettingsLayer.USER, FONT_SIZE, 24);
        assertEquals(Integer.valueOf(24), control.resolve(FONT_SIZE));
        assertEquals("an outer scope is unaffected by an inner override",
                Integer.valueOf(18), panel.resolve(FONT_SIZE));
    }

    /**
     * <b>An inner scope's low layer beats an outer scope's high one</b>, and that is not a bug.
     *
     * <p>The two axes are independent — a layer is <em>who said so</em>, a scope is <em>where</em>. If
     * an outer DOCUMENT value outranked an inner USER one, "override this setting just here" would be
     * unexpressible, which is the entire purpose of having scopes.
     */
    @Test
    public void anInnerScopeWinsRegardlessOfLayer() {
        Scope root = new Scope(null);
        Scope inner = new Scope(root);

        root.settings().set(SettingsLayer.MEMORY, FONT_SIZE, 40);
        inner.settings().set(SettingsLayer.USER, FONT_SIZE, 12);

        assertEquals(Integer.valueOf(12), inner.resolve(FONT_SIZE));
    }

    /** Nothing anywhere in the chain still yields the declaration. */
    @Test
    public void anUndefinedKeyFallsThroughTheWholeChain() {
        Scope root = new Scope(null);
        Scope inner = new Scope(new Scope(root));
        assertEquals(Integer.valueOf(14), inner.resolve(FONT_SIZE));
        assertNull(inner.scopeDefining("editor.fontSize"));
    }

    // ── Serialisation ───────────────────────────────────────────────────────

    /** A layer round-trips, keys and all. */
    @Test
    public void aLayerRoundTrips() {
        Settings settings = new Settings();
        settings.set(SettingsLayer.DOCUMENT, QUEUE, "Transparent");
        settings.set(SettingsLayer.DOCUMENT, FONT_SIZE, 18);

        Object encoded = SettingsCodec.MODEL.encode(PlainOps.INSTANCE,
                settings.layer(SettingsLayer.DOCUMENT));
        SettingsModel decoded = SettingsCodec.MODEL.decode(PlainOps.INSTANCE, encoded);

        assertEquals("Transparent", decoded.get("shader.queue"));
        assertEquals("18", decoded.get("editor.fontSize"));
    }

    /**
     * <b>Two documents differing only in a setting must not hash the same.</b>
     *
     * <p>The concrete failure that motivated the whole item: the shader graph's queue lived on
     * {@code CgMasterNode}, which is never serialised, so two graphs differing only in it were
     * byte-identical and a content-addressed cache would have served one for the other.</p>
     */
    @Test
    public void aSettingChangesTheContentHash() {
        SettingsModel geometry = new SettingsModel();
        geometry.set("shader.queue", "Geometry");
        SettingsModel transparent = new SettingsModel();
        transparent.set("shader.queue", "Transparent");

        assertNotEquals(
                ContentHash.of(PlainOps.INSTANCE, SettingsCodec.MODEL.encode(PlainOps.INSTANCE, geometry)),
                ContentHash.of(PlainOps.INSTANCE, SettingsCodec.MODEL.encode(PlainOps.INSTANCE, transparent)));
    }

    /** An empty layer is not worth writing, so a hash is not disturbed by a layer nobody touched. */
    @Test
    public void anEmptyLayerIsOmitted() {
        assertFalse(SettingsCodec.isWorthWriting(new SettingsModel()));
        SettingsModel one = new SettingsModel();
        one.set("a", "b");
        assertTrue(SettingsCodec.isWorthWriting(one));
    }

    // ── The registry ────────────────────────────────────────────────────────

    /**
     * Declarations are enumerable, which is the whole reason a settings PANEL can be generated rather
     * than hand-written — and the specific reason IntelliJ's state-class model was declined.
     */
    @Test
    public void declarationsAreEnumerableBySection() {
        SettingsRegistry registry = SettingsRegistry.get();
        registry.register(FONT_SIZE);
        registry.register(WRAP);
        registry.register(QUEUE);
        try {
            List<Setting<?>> editor = registry.section("editor");
            assertEquals(2, editor.size());
            assertEquals("declaration order, so a generated panel is stable",
                    "editor.fontSize", editor.get(0).getId());
            assertEquals(1, registry.section("shader").size());
            assertTrue(registry.section("edit").isEmpty());
        } finally {
            registry.unregister(FONT_SIZE.getId())
                    .unregister(WRAP.getId())
                    .unregister(QUEUE.getId());
        }
    }

    /** An enumerated setting refuses a value it does not offer, falling back rather than storing junk. */
    @Test
    public void anEnumeratedSettingFallsBackForAnUnknownValue() {
        assertEquals("Geometry", QUEUE.read("NoSuchQueue"));
        assertEquals("Transparent", QUEUE.read("Transparent"));
        assertEquals("Geometry", QUEUE.read(null));
    }

    /** A malformed stored value degrades to the default instead of throwing out of a getter. */
    @Test
    public void aMalformedValueDegrades() {
        assertEquals(Integer.valueOf(14), FONT_SIZE.read("not a number"));
    }
}
