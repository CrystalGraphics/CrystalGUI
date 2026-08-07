package com.crystalgui.ui.elements.dock;

import com.crystalgui.ui.UIElement;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * <b>Where</b> to open something — VS Code's {@code PreferredGroup}.
 *
 * <h3>Why placement is a value</h3>
 *
 * <p>Because "open this next to me" is a request a widget can make, and until it is a value it is not:
 * the caller has to find the target group itself, which means reaching the dock, the layout and the
 * sibling's panel ref. {@code CrystalEditor.showCompiled} did exactly that —
 * {@code layout().leafContaining(refFor(parse(path)))} — which is why opening a generated shader beside
 * its graph was application code rather than a dock capability.</p>
 *
 * <p>VS Code spells the same idea as constants on one parameter ({@code ACTIVE_GROUP = -1},
 * {@code SIDE_GROUP = -2}); this is that, typed. The service resolves it, so a caller says what it wants
 * and never how to find it.</p>
 */
public sealed interface DockPlacement {

    /** The group commands resolve against — VS Code's {@code ACTIVE_GROUP}. */
    static DockPlacement active() {
        return Active.INSTANCE;
    }

    /** A new split beside the active group — VS Code's {@code SIDE_GROUP}. */
    static DockPlacement side(DockDropZone zone) {
        return new Side(Objects.requireNonNull(zone, "zone"));
    }

    /**
     * The group holding {@code element} — "next to me".
     *
     * <p>The one both references make trivial and this engine made an application's problem. A widget
     * knows itself; it should not have to know the layout to say "beside this".</p>
     */
    static DockPlacement with(UIElement element) {
        return new With(Objects.requireNonNull(element, "element"));
    }

    /** The central work area — the one leaf that always exists and cannot be closed. */
    static DockPlacement central() {
        return Central.INSTANCE;
    }

    /** A specific leaf, for a restore that already knows where things go. */
    static DockPlacement leaf(DockLeaf leaf) {
        return new Leaf(Objects.requireNonNull(leaf, "leaf"));
    }

    record Side(DockDropZone zone) implements DockPlacement {
    }

    record With(UIElement element) implements DockPlacement {
    }

    record Leaf(DockLeaf leaf) implements DockPlacement {
    }

    final class Active implements DockPlacement {
        static final Active INSTANCE = new Active();

        private Active() {
        }

        @Override
        public String toString() {
            return "DockPlacement.active()";
        }
    }

    final class Central implements DockPlacement {
        static final Central INSTANCE = new Central();

        private Central() {
        }

        @Override
        public String toString() {
            return "DockPlacement.central()";
        }
    }

    /**
     * The leaf this placement denotes in {@code area}, or null when it denotes none yet.
     *
     * <p>Null is an ordinary answer: {@link #side} asks for a split that does not exist, and
     * {@link #with} asks about an element that may not be in a dock at all. A caller opening something
     * treats null as "make one"; a caller merely asking treats it as "nowhere".</p>
     */
    @Nullable
    static DockLeaf resolve(DockPlacement placement, DockArea area) {
        if (placement instanceof Leaf named) return named.leaf();
        if (placement instanceof Central) return area.layout().centralLeaf();
        if (placement instanceof With with) {
            DockGroup group = area.groupOf(with.element());
            return group == null ? null : group.leaf();
        }
        // Active, and Side before its split exists, both resolve from the active group -- Side's caller
        // then splits it. Falling back the way activeGroup() does, so a placement asked for before
        // anything has been clicked answers with the work area rather than nothing.
        DockGroup active = area.activeGroup();
        return active == null ? null : active.leaf();
    }
}
