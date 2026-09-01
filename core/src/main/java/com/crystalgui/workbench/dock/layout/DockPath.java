package com.crystalgui.workbench.dock.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Where a node sits in the tree, as the child indices walked from the root.
 *
 * <h3>Why a position needs a coordinate at all</h3>
 *
 * <p>A closed panel is in no leaf, so nothing in the layout can say where it belongs — and that is exactly
 * when something has to. The obvious answers do not work:</p>
 *
 * <ul>
 *   <li><b>An outer edge</b> (LEFT/RIGHT/TOP/BOTTOM) describes only panels against a wall. A tool window
 *       beside the editor area but not spanning the window is on no edge, and asking which edge it is
 *       "nearest" moves it on reopen — the same bug in a different direction.</li>
 *   <li><b>A node reference</b> does not survive the close: {@code closePanel} collapses the branch that
 *       held it, so the object a caller kept is detached and reinserting it puts the panel outside the
 *       tree.</li>
 * </ul>
 *
 * <p>Indices survive both, because they describe the position rather than the thing occupying it. This is
 * the same reason a text editor stores a caret as an offset and not as a character.</p>
 *
 * <h3>It is a hint, and callers must treat it as one</h3>
 *
 * <p>The tree changes shape while a panel is closed — another panel splits a pane, a drag collapses a
 * branch — and then these indices name a different place, or none. So {@link DockLayout#insertAt} returns
 * whether it landed, and every caller needs a fallback. <b>A path is the best available guess at a
 * remembered position, never a guarantee</b>, and code that assumes otherwise will silently drop panels
 * into the wrong pane on exactly the layouts that moved most.</p>
 *
 * <p>Stored as {@code "0.1.2"} so a session record stays readable, and because a session file is
 * something people open and diff.</p>
 */
public final class DockPath {

    /** The root itself — an empty walk. */
    public static final DockPath ROOT = new DockPath(new int[0]);

    private final int[] indices;

    private DockPath(int[] indices) {
        this.indices = indices;
    }

    public static DockPath of(int... indices) {
        return indices.length == 0 ? ROOT : new DockPath(indices.clone());
    }

    /** {@code "0.1.2"}, or the empty string for {@link #ROOT}. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) out.append('.');
            out.append(indices[i]);
        }
        return out.toString();
    }

    /** Reads {@link #toString()} back. Null for anything malformed — a record is not trusted input. */
    @Nullable
    public static DockPath parse(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return ROOT;
        String[] parts = trimmed.split("\\.");
        int[] indices = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                indices[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException notAnIndex) {
                return null;
            }
            if (indices[i] < 0) return null;
        }
        return new DockPath(indices);
    }

    public int depth() {
        return indices.length;
    }

    public int index(int at) {
        return indices[at];
    }

    public boolean isRoot() {
        return indices.length == 0;
    }

    /** This path with {@code child} appended — the path of that child of this node. */
    public DockPath child(int child) {
        int[] longer = Arrays.copyOf(indices, indices.length + 1);
        longer[indices.length] = child;
        return new DockPath(longer);
    }

    /** Everything but the last step, or null at the root. */
    @Nullable
    public DockPath parent() {
        if (indices.length == 0) return null;
        return new DockPath(Arrays.copyOf(indices, indices.length - 1));
    }

    /** The last step, or -1 at the root. */
    public int lastIndex() {
        return indices.length == 0 ? -1 : indices[indices.length - 1];
    }

    public List<Integer> toList() {
        List<Integer> out = new ArrayList<>(indices.length);
        for (int index : indices) out.add(index);
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof DockPath other && Arrays.equals(indices, other.indices));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(indices);
    }
}
