package com.crystalgui.workbench.toolwindow;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.region.RegionSide;

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
    private static final String KEY_SIDE_WEIGHT = "sideWeight";
    private static final String KEY_ORDER = "order";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_STRIPE = "stripe";
    private static final String KEY_TYPE = "type";
    private static final String KEY_FLOAT_X = "fx";
    private static final String KEY_FLOAT_Y = "fy";
    private static final String KEY_FLOAT_W = "fw";
    private static final String KEY_FLOAT_H = "fh";

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

    /**
     * A placement changed — the rails' cue to re-ask which of them owns a button.
     *
     * <p><b>On the store rather than on the manager, because the store is what a restore replaces.</b>
     * The manager announced from its own three mutators, which covers every gesture and misses the one
     * writer that does not go through them: a session restore clears this and puts every decoded state
     * straight in. The panels then moved to the restored record — {@code applyVisibility} re-shows them —
     * and the buttons did not, because nothing told the rails. Notifications opened bottom-left with its
     * bell on the top right, and Problems bottom-right with its icon on the bottom left: the arrangement
     * from the record and the arrangement from before it, on screen at once.</p>
     *
     * <p>Announcing where the write happens makes every writer correct without any of them having to
     * remember — a rule kept by three call sites is a rule the fourth breaks.</p>
     */
    public final Signal.Value<String> onDidChange = new Signal.Value<>();

    public ToolWindowLayout put(ToolWindowState state) {
        ToolWindowState previous = states.put(state.typeId(), state);
        // ONLY WHEN SOMETHING ACTUALLY MOVED. `put` is how every hide, show and capture records itself,
        // and most of those write back what was already there -- a rail re-syncing for each of them
        // would be work with nothing to show for it.
        if (!state.equals(previous)) onDidChange.emit(state.typeId());
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

    /**
     * Forgets every placement — what a restore does before installing the record it read.
     *
     * <p>Announced per type rather than as one blanket event, because the signal names a type and every
     * consumer re-asks about that one. Copied first: a listener is free to write back.</p>
     */
    public void clear() {
        List<String> forgotten = new ArrayList<>(states.keySet());
        states.clear();
        for (String typeId : forgotten) onDidChange.emit(typeId);
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
            entry.putFloat(KEY_SIDE_WEIGHT, state.sideWeight());
            entry.putInt(KEY_ORDER, state.order());
            entry.putBool(KEY_ACTIVE, state.active());
            entry.putBool(KEY_STRIPE, state.showStripeButton());
            // THE MODE, and the geometry that only means anything in it. A float's frame is destroyed on
            // every hide, so nothing but this record can say that the Inspector was floating -- without
            // it a restored session brings every tool window back docked, silently, and the user's
            // arrangement is gone with no error to attribute it to.
            entry.putString(KEY_TYPE, state.type().name());
            ToolWindowState.Bounds bounds = state.floatingBounds();
            // WRITTEN ONLY WHEN THERE IS ONE. An absent optional is omitted rather than written as
            // zeroes, which is the same rule UIDescriptionCodec follows and for a sharper reason here:
            // a 0x0 rect at the origin is a legal value, so a reader cannot tell it from "never
            // floated" -- and a float restored at 0x0 is a window that cannot be seen or grabbed.
            if (bounds != null) {
                entry.putFloat(KEY_FLOAT_X, bounds.left());
                entry.putFloat(KEY_FLOAT_Y, bounds.top());
                entry.putFloat(KEY_FLOAT_W, bounds.width());
                entry.putFloat(KEY_FLOAT_H, bounds.height());
            }
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
                .withSideWeight(entry.getFloat(KEY_SIDE_WEIGHT, ToolWindowState.DEFAULT_SIDE_WEIGHT))
                .withActive(entry.getBool(KEY_ACTIVE, true))
                .withShowStripeButton(entry.getBool(KEY_STRIPE, true))
                .withType(ToolWindowType.ofName(entry.getString(KEY_TYPE, "")));
        // A width of zero is what an entry with no rect reads as, and it is also the one value that
        // cannot be a real float -- so it is the "never floated" test as well as the malformed-record
        // one. Restoring a 0x0 frame would put a window on screen with nothing to grab.
        float width = entry.getFloat(KEY_FLOAT_W, 0f);
        float height = entry.getFloat(KEY_FLOAT_H, 0f);
        if (width > 0f && height > 0f) {
            state = state.withFloatingBounds(new ToolWindowState.Bounds(
                    entry.getFloat(KEY_FLOAT_X, 0f), entry.getFloat(KEY_FLOAT_Y, 0f), width, height));
        }
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
