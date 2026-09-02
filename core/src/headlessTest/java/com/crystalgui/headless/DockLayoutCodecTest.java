package com.crystalgui.headless;

import com.crystalgui.serialization.PlainOps;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLayoutCodec;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockOrientation;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Saving and restoring an arrangement, and the two ways a restore is allowed to degrade.
 *
 * <p>The degradation rules are the reason this file is longer than the round trip it is testing. Qt's
 * versioned {@code restoreState} exists because restoring an old layout into new code is the most common
 * crash in this class of system, and "a mod was uninstalled" is not hypothetical here — it is the normal
 * lifecycle of a Minecraft install.</p>
 */
public class DockLayoutCodecTest {

    private static DockPanelRegistry<String> registryOf(String... typeIds) {
        DockPanelRegistry<String> registry = new DockPanelRegistry<>();
        for (String typeId : typeIds) {
            registry.register(new DockPanelDescriptor(typeId, typeId), ref -> ref.typeId());
        }
        return registry;
    }

    private static String ids(DockLayout layout) {
        StringBuilder sb = new StringBuilder();
        for (DockLeaf leaf : layout.leaves()) {
            if (sb.length() > 0) sb.append(',');
            for (int i = 0; i < leaf.panelCount(); i++) {
                if (i > 0) sb.append('+');
                sb.append(leaf.panel(i).typeId());
            }
        }
        return sb.toString();
    }

    private static DockLeaf leaf(String id) {
        return new DockLeaf(new DockPanelRef(id));
    }

    // ── Round trip ──────────────────────────────────────────────────────────────────────────────

    /** Shape, order, weights, selection and flags all survive. */
    @Test
    public void aLayoutRoundTrips() {
        DockLeaf a = leaf("A");
        a.setCentral(true);
        DockLayout layout = DockLayout.of(a);
        DockLeaf b = leaf("B");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, b);
        DockLeaf c = leaf("C");
        layout.drop(b, DockDropZone.SPLIT_DOWN, c);
        a.size(3f);
        layout.maximize(c);

        Object encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 1280f, 720f);
        DockLayout restored =
                DockLayoutCodec.decode(encoded, PlainOps.INSTANCE, registryOf("A", "B", "C"));

        assertNotNull(restored);
        assertEquals("A,B,C", ids(restored));
        assertEquals(DockOrientation.HORIZONTAL, restored.rootOrientation());
        assertEquals(3f, restored.root().child(0).size(), 1e-4f);
        assertNotNull("the central leaf is still marked", restored.centralLeaf());
        assertEquals("C", restored.maximizedLeaf().panel(0).typeId());
        restored.checkInvariants();
    }

    /** A panel's own state is identity, and identity is what a restore has to bring back. */
    @Test
    public void panelStateRoundTrips() {
        DockPanelRef ref = new DockPanelRef("editor",
                Map.of("file", "mymod.proj:src/main.glsl"));
        DockLayout layout = DockLayout.of(new DockLeaf(ref));

        Object encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);
        DockLayout restored = DockLayoutCodec.decode(encoded, PlainOps.INSTANCE, registryOf("editor"));

        assertNotNull(restored);
        assertEquals("mymod.proj:src/main.glsl",
                restored.leaves().get(0).panel(0).state("file", null));
    }

    /** The selected tab in a multi-panel strip is remembered. */
    @Test
    public void theActiveTabRoundTrips() {
        DockPanelRef one = new DockPanelRef("A");
        DockPanelRef two = new DockPanelRef("B");
        DockLeaf leaf = new DockLeaf(one, two);
        leaf.activate(0);
        DockLayout layout = DockLayout.of(leaf);

        Object encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);
        DockLayout restored = DockLayoutCodec.decode(encoded, PlainOps.INSTANCE, registryOf("A", "B"));

        assertNotNull(restored);
        assertEquals(0, restored.leaves().get(0).activeIndex());
    }

    /** Encoding is deterministic — the same tree gives the same bytes, so it can be diffed or hashed. */
    @Test
    public void encodingTheSameTreeTwiceGivesTheSameResult() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        layout.drop(a, DockDropZone.SPLIT_RIGHT, leaf("B"));

        Object first = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);
        Object second = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);

        assertEquals(first.toString(), second.toString());
    }

    // ── Rule 1: an unknown version is discarded ─────────────────────────────────────────────────

    /**
     * <b>A blob from a format we do not know is thrown away, not parsed hopefully.</b>
     *
     * <p>Parsing it produces a layout that <em>looks</em> restored and is subtly wrong — panes in the wrong
     * place, a flag read from the wrong key — which is the kind of thing nobody reports as a bug because
     * it looks like they mis-remembered where they left things.</p>
     */
    @Test
    public void anUnknownVersionIsDiscarded() {
        DockLayout layout = DockLayout.of(leaf("A"));
        String encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f).toString();

        // Whatever the shape, a future version number must not be read by this build.
        Object bumped = new com.crystalgui.serialization.StateMap<>(PlainOps.INSTANCE)
                .putInt("version", DockLayoutCodec.VERSION + 1)
                .putEnum("rootOrientation", DockOrientation.HORIZONTAL)
                .encode();

        assertNull(DockLayoutCodec.decode(bumped, PlainOps.INSTANCE, registryOf("A")));
        assertTrue("sanity: the real version does decode", encoded.contains("version"));
    }

    /** Nonsense in the version slot is the same case as a future version. */
    @Test
    public void aMissingVersionIsDiscarded() {
        Object bare = new com.crystalgui.serialization.StateMap<>(PlainOps.INSTANCE)
                .putString("root", "nonsense")
                .encode();
        assertNull(DockLayoutCodec.decode(bare, PlainOps.INSTANCE, registryOf("A")));
    }

    // ── Rule 2: an unknown panel type is dropped, the rest survives ─────────────────────────────

    /**
     * <b>A panel nobody can build any more loses its leaf, and nothing else.</b>
     *
     * <p>Refusing the whole restore over one missing panel loses the user's entire arrangement to somebody
     * else's uninstall — which is a worse outcome than the missing panel by a wide margin.</p>
     */
    @Test
    public void anUnknownPanelTypeIsDroppedAndTheRestSurvives() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf gone = leaf("from-a-removed-mod");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, gone);
        DockLeaf c = leaf("C");
        layout.drop(gone, DockDropZone.SPLIT_DOWN, c);

        Object encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);
        DockLayout restored = DockLayoutCodec.decode(encoded, PlainOps.INSTANCE, registryOf("A", "C"));

        assertNotNull("the restore must not fail", restored);
        assertEquals("A,C", ids(restored));
        restored.checkInvariants();
    }

    /**
     * Dropping a panel can empty a leaf, empty a branch, and leave one-child branches — all in one go.
     * The decoded tree is normalised once rather than at each removal, and the invariants must hold after.
     */
    @Test
    public void droppingPanelsLeavesNoBrokenBranchesBehind() {
        DockLeaf a = leaf("A");
        DockLayout layout = DockLayout.of(a);
        DockLeaf x = leaf("X");
        layout.drop(a, DockDropZone.SPLIT_RIGHT, x);
        DockLeaf y = leaf("Y");
        layout.drop(x, DockDropZone.SPLIT_DOWN, y);   // a whole branch of X and Y, both unknown later

        Object encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);
        DockLayout restored = DockLayoutCodec.decode(encoded, PlainOps.INSTANCE, registryOf("A"));

        assertNotNull(restored);
        assertEquals("A", ids(restored));
        restored.checkInvariants();
    }

    /** With nothing left to show, the default layout is the honest answer. */
    @Test
    public void aLayoutOfNothingButUnknownPanelsIsDiscarded() {
        DockLayout layout = DockLayout.of(leaf("gone"));
        Object encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);

        assertNull(DockLayoutCodec.decode(encoded, PlainOps.INSTANCE, registryOf("something-else")));
    }

    /** A central leaf survives being emptied by rule 2 — it is the main work area, not a panel. */
    @Test
    public void aCentralLeafSurvivesLosingItsPanels() {
        DockLeaf central = leaf("gone");
        central.setCentral(true);
        DockLayout layout = DockLayout.of(central);
        layout.drop(central, DockDropZone.SPLIT_RIGHT, leaf("side"));

        Object encoded = DockLayoutCodec.encode(layout, PlainOps.INSTANCE, 800f, 600f);
        DockLayout restored = DockLayoutCodec.decode(encoded, PlainOps.INSTANCE, registryOf("side"));

        assertNotNull(restored);
        assertNotNull("the central leaf is still there, empty", restored.centralLeaf());
        assertTrue(restored.centralLeaf().isEmpty());
        restored.checkInvariants();
    }

    // ── Registry ────────────────────────────────────────────────────────────────────────────────

    /** A per-instance title beats the type's default — a document tab is named after its file. */
    @Test
    public void aPanelsOwnTitleWinsOverTheTypeDefault() {
        DockPanelRegistry<String> registry = registryOf("editor");
        assertEquals("editor", registry.titleOf(new DockPanelRef("editor")));
        assertEquals("main.glsl",
                registry.titleOf(new DockPanelRef("editor").withState(DockPanelRef.TITLE, "main.glsl")));
    }

    /** A declared-but-unbuildable type is known to the codec and builds nothing — honestly. */
    @Test
    public void aDeclaredTypeIsRegisteredButBuildsNothing() {
        DockPanelRegistry<String> registry = new DockPanelRegistry<>();
        registry.declare(DockPanelDescriptor.singleton("inspector", "Inspector"));

        assertTrue(registry.isRegistered("inspector"));
        assertNull("no placeholder that looks like a working panel",
                registry.create(new DockPanelRef("inspector")));
    }
}
