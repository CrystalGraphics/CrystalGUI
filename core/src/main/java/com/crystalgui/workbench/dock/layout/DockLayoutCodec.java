package com.crystalgui.workbench.dock.layout;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;

import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saving and restoring an arrangement.
 *
 * <pre>
 * { version, viewportWidth, viewportHeight, rootOrientation, root: node }
 *
 * node := { type: "branch", size, children: [node…] }
 *       | { type: "leaf",   size, panels: [{typeId, state:[{k,v}…]}…], active, central?, maximized? }
 * </pre>
 *
 * <h3>The leaf payload is opaque, and that is the whole trick</h3>
 *
 * <p>A layout is a tree of <b>sizes and panel identities</b>, never of elements. Round-tripping the
 * element tree through {@code UIDescriptionCodec} would restore a frozen DOM of whatever graph nodes
 * happened to be on screen, detached from the document that produced them. Every system surveyed — VS
 * Code, Golden Layout, ImGui, Qt — keeps this payload opaque and hands it to a factory, and the reason is
 * the same in all four.</p>
 *
 * <h3>Two degradation rules, and a restore never fails</h3>
 *
 * <p>Qt's {@code restoreState(data, version)} exists because restoring an old layout into new code is the
 * single most common crash in this class of system. Both rules below must hold, and both are pinned by
 * tests:</p>
 *
 * <ol>
 *   <li>A blob whose <b>version</b> we do not know is <b>discarded</b> for the default layout, not parsed
 *       hopefully. A format that changed meaning is worse than a format that is missing.</li>
 *   <li>A leaf naming a <b>panel type nobody registers</b> — a mod was uninstalled — is <b>dropped and its
 *       weight redistributed</b>, with everything else intact. Refusing the whole restore over one missing
 *       panel loses the user's entire arrangement to somebody else's uninstall.</li>
 * </ol>
 *
 * <p>The viewport is recorded but not enforced: sizes are weights (see {@link DockLayout}), so a restore
 * into a different window is already proportional. It is here because a consumer that wants absolute
 * pixels back needs to know what the weights were measured against, and adding it later would be a format
 * change.</p>
 */
public final class DockLayoutCodec {

    private DockLayoutCodec() {
    }

    /** Bump when the shape changes meaning. An unknown version is discarded, never guessed at. */
    public static final int VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_VIEWPORT_W = "viewportWidth";
    private static final String KEY_VIEWPORT_H = "viewportHeight";
    private static final String KEY_ROOT_ORIENTATION = "rootOrientation";
    private static final String KEY_ROOT = "root";

    private static final String KEY_TYPE = "type";
    private static final String KEY_SIZE = "size";
    private static final String KEY_CHILDREN = "children";
    private static final String KEY_PANELS = "panels";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_CENTRAL = "central";
    private static final String KEY_MAXIMIZED = "maximized";

    private static final String KEY_TYPE_ID = "typeId";
    private static final String KEY_STATE = "state";
    private static final String KEY_STATE_KEY = "k";
    private static final String KEY_STATE_VALUE = "v";

    private static final String TYPE_BRANCH = "branch";
    private static final String TYPE_LEAF = "leaf";

    // ── Encode ──────────────────────────────────────────────────────────────────────────────────

    public static <T> T encode(DockLayout layout, DynamicOps<T> ops, float viewportWidth, float viewportHeight) {
        StateMap<T> out = new StateMap<>(ops);
        out.putInt(KEY_VERSION, VERSION);
        out.putFloat(KEY_VIEWPORT_W, viewportWidth);
        out.putFloat(KEY_VIEWPORT_H, viewportHeight);
        out.putEnum(KEY_ROOT_ORIENTATION, layout.rootOrientation());
        out.putList(KEY_ROOT, List.of(layout.root()), DockLayoutCodec::writeNode);
        return out.encode();
    }

    private static <T> void writeNode(StateMap<T> out, DockNode node) {
        out.putFloat(KEY_SIZE, node.size());
        if (node.isLeaf()) {
            DockLeaf leaf = (DockLeaf) node;
            out.putString(KEY_TYPE, TYPE_LEAF);
            out.putList(KEY_PANELS, leaf.panels(), DockLayoutCodec::writePanel);
            out.putInt(KEY_ACTIVE, Math.max(0, leaf.activeIndex()));
            // Omitted when false rather than written as false: a description that is byte-identical for the
            // same tree is what makes content-addressing it possible, and the same discipline
            // UIDescriptionCodec already keeps.
            out.putBoolIfNot(KEY_CENTRAL, leaf.isCentral(), false);
            out.putBoolIfNot(KEY_MAXIMIZED, leaf.isMaximized(), false);
        } else {
            DockBranch branch = (DockBranch) node;
            out.putString(KEY_TYPE, TYPE_BRANCH);
            out.putList(KEY_CHILDREN, branch.children(), DockLayoutCodec::writeNode);
        }
    }

    private static <T> void writePanel(StateMap<T> out, DockPanelRef panel) {
        out.putString(KEY_TYPE_ID, panel.typeId());
        List<Map.Entry<String, String>> entries = new ArrayList<>(panel.state().entrySet());
        out.putList(KEY_STATE, entries, (entry, pair) -> {
            entry.putString(KEY_STATE_KEY, pair.getKey());
            entry.putString(KEY_STATE_VALUE, pair.getValue());
        });
    }

    // ── Decode ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Restores a layout, or returns {@code null} when the blob cannot be trusted.
     *
     * <p>{@code null} means "use the default layout" and is a normal outcome, not an error path — an
     * unknown version and a structurally impossible tree both land here. The caller is expected to have a
     * default, because it needs one on first run anyway.</p>
     */
    public static <T> DockLayout decode(T encoded, DynamicOps<T> ops, DockPanelRegistry<?> registry) {
        StateMap<T> in;
        try {
            in = new StateMap<>(ops, encoded);
        } catch (RuntimeException e) {
            CrystalGuiCore.LOGGER.warn("Dock layout could not be read; falling back to the default", e);
            return null;
        }

        int version = in.getInt(KEY_VERSION, -1);
        if (version != VERSION) {
            // Rule 1. A format that changed meaning is worse than one that is missing: parsing it hopefully
            // produces a layout that looks restored and is subtly wrong, which nobody reports as a bug.
            CrystalGuiCore.LOGGER.info("Dock layout is version {} but this build reads {}; using the default",
                    version, VERSION);
            return null;
        }

        List<DockNode> roots = in.getList(KEY_ROOT, map -> readNode(map, registry));
        if (roots.isEmpty() || roots.get(0) == null || roots.get(0).isLeaf()) {
            CrystalGuiCore.LOGGER.warn("Dock layout has no usable root; using the default");
            return null;
        }

        DockBranch root = (DockBranch) roots.get(0);
        DockOrientation orientation =
                in.getEnum(KEY_ROOT_ORIENTATION, DockOrientation.class, DockOrientation.HORIZONTAL);
        DockLayout layout = DockLayout.of(root, orientation);

        // Rule 2's aftermath. Dropping unknown panels empties leaves, empty leaves empty branches, and
        // removing branches leaves one-child branches anywhere in the tree.
        layout.normalise();
        if (layout.leaves().isEmpty() || layout.root().childCount() == 0) {
            CrystalGuiCore.LOGGER.warn("Dock layout had nothing left after dropping unknown panels; "
                    + "using the default");
            return null;
        }
        return layout;
    }

    private static <T> DockNode readNode(StateMap<T> in, DockPanelRegistry<?> registry) {
        String type = in.getString(KEY_TYPE, "");
        float size = in.getFloat(KEY_SIZE, 1f);

        if (TYPE_LEAF.equals(type)) {
            List<DockPanelRef> panels = in.getList(KEY_PANELS, map -> readPanel(map, registry));
            List<DockPanelRef> kept = new ArrayList<>();
            for (DockPanelRef panel : panels) {
                if (panel != null) kept.add(panel);
            }
            DockLeaf leaf = new DockLeaf(kept.toArray(new DockPanelRef[0]));
            leaf.size(size > 0f ? size : 1f);
            leaf.activate(in.getInt(KEY_ACTIVE, 0));
            leaf.setCentral(in.getBool(KEY_CENTRAL, false));
            leaf.setMaximized(in.getBool(KEY_MAXIMIZED, false));
            return leaf;
        }
        if (TYPE_BRANCH.equals(type)) {
            DockBranch branch = new DockBranch(size > 0f ? size : 1f);
            List<DockNode> children = in.getList(KEY_CHILDREN, map -> readNode(map, registry));
            int index = 0;
            for (DockNode child : children) {
                if (child != null) branch.addChild(child, index++);
            }
            return branch;
        }
        return null;
    }

    private static <T> DockPanelRef readPanel(StateMap<T> in, DockPanelRegistry<?> registry) {
        String typeId = in.getString(KEY_TYPE_ID, "");
        if (typeId.isEmpty()) return null;
        if (!registry.isRegistered(typeId)) {
            // Rule 2. Named at info rather than warn: a mod being uninstalled is a thing the user did, and
            // it should be findable in a log without looking like a fault.
            CrystalGuiCore.LOGGER.info("Dock layout references unknown panel type '{}'; dropping it", typeId);
            return null;
        }
        Map<String, String> state = new LinkedHashMap<>();
        for (String[] pair : in.getList(KEY_STATE,
                map -> new String[]{map.getString(KEY_STATE_KEY, ""), map.getString(KEY_STATE_VALUE, "")})) {
            if (!pair[0].isEmpty()) state.put(pair[0], pair[1]);
        }
        return new DockPanelRef(typeId, state);
    }
}
