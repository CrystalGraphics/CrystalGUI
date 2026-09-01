package com.crystalgui.document;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The files opened most recently, newest first — VS Code's {@code recentlyOpened}, IntelliJ's
 * {@code RecentProjectsManager} for files.
 *
 * <h3>Why this is a model and not a menu</h3>
 *
 * <p>{@code File ▸ Open Recent} is the obvious consumer and deliberately not the owner. The list is a fact
 * about the workbench — a Go-to-File dialog wants it ranked, a welcome screen wants it, a session restore
 * wants it — and a list that lived inside the menu would be reachable only while the menu was open, which
 * is exactly the coupling {@link com.crystalgui.core.command.MenuId} exists to remove one layer up.</p>
 *
 * <h3>Re-opening promotes rather than duplicates</h3>
 *
 * <p>An MRU whose entries repeat is not an MRU. {@link #record} removes any existing entry for the path
 * before pushing it to the front, so opening the same file five times leaves one row at the top rather
 * than five identical ones — which is the whole behaviour people mean by "recent".</p>
 *
 * <h3>Capped, and the cap is small on purpose</h3>
 *
 * <p>{@value #DEFAULT_LIMIT}, which is VS Code's own default for the File menu's recent list. A submenu
 * long enough to need scrolling defeats the point: the value of a recent list is that the thing you want
 * is near the top, and past a dozen entries Go-to-File is faster than reading.</p>
 */
public final class RecentFiles {

    /** VS Code's own default for the File menu's list. */
    public static final int DEFAULT_LIMIT = 10;

    /** Fires whenever the list changes, so a view can follow it rather than poll. */
    public final Signal.Action onDidChange = new Signal.Action();

    private final Deque<CgPath> paths = new ArrayDeque<>();
    private final int limit;

    public RecentFiles() {
        this(DEFAULT_LIMIT);
    }

    public RecentFiles(int limit) {
        this.limit = Math.max(1, limit);
    }

    /**
     * Notes that {@code path} was opened, moving it to the front.
     *
     * <p>Silent when the path is already at the front, so re-activating the tab you are already in does
     * not announce a change nobody can see — the same equality guard {@code Property.set} makes, and what
     * stops a menu contributor being asked to rebuild on every tab click.</p>
     */
    public void record(CgPath path) {
        if (path == null) return;
        if (!paths.isEmpty() && paths.peekFirst().equals(path)) return;
        paths.remove(path);
        paths.addFirst(path);
        while (paths.size() > limit) paths.removeLast();
        onDidChange.emit();
    }

    /** Drops {@code path}, for a file that has been deleted or moved. */
    public boolean forget(CgPath path) {
        if (!paths.remove(path)) return false;
        onDidChange.emit();
        return true;
    }

    /** Newest first. */
    public List<CgPath> paths() {
        return new ArrayList<>(paths);
    }

    public boolean isEmpty() {
        return paths.isEmpty();
    }

    public int size() {
        return paths.size();
    }

    public void clear() {
        if (paths.isEmpty()) return;
        paths.clear();
        onDidChange.emit();
    }
}
