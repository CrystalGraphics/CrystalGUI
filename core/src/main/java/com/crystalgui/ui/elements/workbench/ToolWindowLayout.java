package com.crystalgui.ui.elements.workbench;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.elements.dock.DockRegion;
import com.crystalgui.ui.elements.dock.RegionSide;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Every tool window's placement, open or closed — IntelliJ's {@code DesktopLayout}.
 *
 * <h3>Beside the dock layout, never inside it</h3>
 *
 * <p>This is the half of the architecture that makes closing a tool window non-destructive. The
 * {@code DockLayout} describes what is <b>on screen</b>; a panel that is closed has left it entirely and
 * there is nothing there to remember it. This map is what remembers, and it is keyed by <em>type</em>
 * because a tool window is a singleton — there is exactly one Project panel, and "the Project panel's
 * placement" is a well-formed thing to store.</p>
 *
 * <p>Both editors are built this way. IntelliJ persists a {@code WindowInfoImpl} per tool window into
 * {@code workspace.xml} and keeps the splittable tree for editors only; VS Code stores
 * {@code workbench.activity.pinnedViewlets2} plus per-container view state, and serialises the editor grid
 * separately. Neither derives a placement from the layout, because in neither is a tool window's placement
 * ever <em>in</em> the layout to be derived from.</p>
 *
 * <h3>Serialised whole, and versioned by its owner</h3>
 *
 * <p>The encoding is a flat list rather than an object keyed by type id, so the order is fixed and a
 * record diffs cleanly — the same reason {@code UIDescriptionCodec} fixes field order. Unknown keys are
 * ignored and malformed entries dropped individually: a session naming a tool window this build no longer
 * has is ordinary (a mod was removed), and it must not cost the placements of the ones that remain.</p>
 */
public final class ToolWindowLayout {

    private static final String KEY_ID = "id";
    private static final String KEY_VISIBLE = "visible";
    private static final String KEY_REGION = "region";
    private static final String KEY_SIDE = "side";
    private static final String KEY_WEIGHT = "weight";
    private static final String KEY_ORDER = "order";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_STRIPE = "stripe";

    /** Insertion-ordered, so an encode is byte-stable for an unchanged layout. */
    private final Map<String, ToolWindowState> states = new LinkedHashMap<>();

    /** The placement for a type, or null when it has never been seen. */
    @Nullable
    public ToolWindowState get(String typeId) {
        return states.get(typeId);
    }

    /**
     * The placement for a type, creating an initial one from the descriptor's defaults if absent.
     *
     * <p>The order defaults to the number of tool windows already known, so registration order becomes
     * activity-bar order until something reorders it — which is what both editors do for a freshly
     * installed extension.</p>
     */
    public ToolWindowState getOrCreate(String typeId, DockRegion region, RegionSide side) {
        return states.computeIfAbsent(typeId,
                id -> ToolWindowState.initial(id, region, states.size()).withSide(side));
    }

    /** As {@link #getOrCreate(String, DockRegion, RegionSide)}, taking the region's first half. */
    public ToolWindowState getOrCreate(String typeId, DockRegion region) {
        return getOrCreate(typeId, region, RegionSide.PRIMARY);
    }

    public ToolWindowLayout put(ToolWindowState state) {
        states.put(state.typeId(), state);
        return this;
    }

    public boolean contains(String typeId) {
        return states.containsKey(typeId);
    }

    /** Every placement, in activity-bar order. */
    public List<ToolWindowState> ordered() {
        List<ToolWindowState> out = new ArrayList<>(states.values());
        out.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return out;
    }

    public Collection<ToolWindowState> all() {
        return states.values();
    }

    public boolean isEmpty() {
        return states.isEmpty();
    }

    public void clear() {
        states.clear();
    }

    // ── Serialisation ───────────────────────────────────────────────────────────────────────────

    /** Writes every placement into {@code out} under {@code key}, as a list. */
    public <T> void encodeInto(StateMap<T> out, String key) {
        out.putList(key, ordered(), (entry, state) -> {
            entry.putString(KEY_ID, state.typeId());
            entry.putBool(KEY_VISIBLE, state.visible());
            // REGION AND SIDE, which used to be one `anchor` naming an outer wall. Together they are the
            // whole of where a tool window lives, and StripeRail derives its rail and group from the pair.
            entry.putString(KEY_REGION, state.region().name());
            entry.putString(KEY_SIDE, state.side().name());
            entry.putFloat(KEY_WEIGHT, state.weight());
            entry.putInt(KEY_ORDER, state.order());
            entry.putBool(KEY_ACTIVE, state.active());
            entry.putBool(KEY_STRIPE, state.showStripeButton());
            // The path, the strip-mates and the neighbour are GONE, with the four-tier restoration
            // heuristic that consumed them. All three described a position in the dock tree, which a tool
            // window no longer occupies: it belongs to a REGION, and a region is not destroyed by hiding
            // the thing in it. See ToolWindowManager and plan.md §23 F2b.
        });
    }

    /**
     * Reads placements written by {@link #encodeInto}. Never throws: a malformed entry is skipped and the
     * rest are kept.
     *
     * <p>A {@code region} this build does not recognise falls back to the sidebar rather than dropping the
     * whole placement — losing a region costs one drag, losing the record costs the size, the order and
     * whether it was open.</p>
     */
    public static <T> ToolWindowLayout decodeFrom(StateMap<T> in, String key) {
        ToolWindowLayout layout = new ToolWindowLayout();
        for (ToolWindowState state : in.getList(key, entry -> decodeOne(entry, layout.states.size()))) {
            if (state != null) layout.put(state);
        }
        return layout;
    }

    @Nullable
    private static <T> ToolWindowState decodeOne(StateMap<T> entry, int fallbackOrder) {
        String id = entry.getString(KEY_ID, "");
        if (id.isEmpty()) return null;

        // Read as a STRING and matched by hand, not through getEnum -- that throws a CodecException for a
        // constant this build does not have, which would take the whole placement with it. A record is
        // untrusted input written by a possibly-newer build, and losing a region must not cost the size,
        // the order and whether it was open.
        DockRegion region = regionOf(entry.getString(KEY_REGION, ""));
        ToolWindowState state = ToolWindowState
                .initial(id, region, entry.getInt(KEY_ORDER, fallbackOrder))
                .withSide(RegionSide.ofName(entry.getString(KEY_SIDE, "")))
                .withVisible(entry.getBool(KEY_VISIBLE, false))
                .withWeight(entry.getFloat(KEY_WEIGHT, ToolWindowState.DEFAULT_WEIGHT))
                .withActive(entry.getBool(KEY_ACTIVE, true))
                .withShowStripeButton(entry.getBool(KEY_STRIPE, true));
        // A record written earlier still carries anchor/path/grouped/relative keys. They are simply not
        // read: an unknown key is ignored by StateMap, and the version bump is what says the omission is
        // deliberate rather than a reader that fell behind.
        return state;
    }

    /**
     * A name this build does not know falls back to the sidebar rather than dropping the placement.
     *
     * <p>{@link DockRegion#EDITOR} is refused as well as unknown names, and that is the interesting case:
     * it is a legal constant that no tool window may hold. A record claiming one would give it a
     * {@link RegionHost} that does not exist, so it would never open and never be reachable to move.</p>
     */
    private static DockRegion regionOf(String name) {
        for (DockRegion region : DockRegion.values()) {
            if (region != DockRegion.EDITOR && region.name().equals(name)) return region;
        }
        return DockRegion.SIDEBAR;
    }
}
