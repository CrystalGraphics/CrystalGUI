package com.crystalgui.workbench.explorer;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.fs.CgPath;

import java.util.ArrayList;
import java.util.List;

/**
 * What Cut and Copy put down, and Paste picks up.
 *
 * <h3>Its own clipboard, not the platform's</h3>
 *
 * <p>The platform clipboard is <b>text</b> — {@code CgInputService} offers {@code getClipboard()} and
 * {@code setClipboard(String)} and nothing else, which is the right shape for the thing it is and cannot
 * carry "these four paths, and I intend to move them". Cut in particular is not expressible: the
 * difference between a cut and a copy is an <em>intent</em> that lives with the clipboard until a paste
 * consumes it.</p>
 *
 * <p>So the paths and the intent live here, and the text form is written to the platform clipboard
 * <em>as well</em> — pasting into a script or a chat message then gives something useful, which is the
 * half a purely internal clipboard would lose. Every file manager does both.</p>
 *
 * <h3>A cut is consumed; a copy is not</h3>
 *
 * <p>Pasting a cut clears it, because the files have moved and a second paste would be a move from a path
 * that no longer exists. Pasting a copy leaves it, because copying the same thing into three folders is a
 * real gesture. Windows Explorer, Finder and VS Code all behave this way.</p>
 */
public final class ExplorerClipboard {

    /** What a paste should do with what is held. */
    public enum Mode {
        COPY, CUT
    }

    private final List<CgPath> paths = new ArrayList<>();
    private Mode mode = Mode.COPY;

    /** Puts paths down for copying. */
    public void copy(List<CgPath> selection) {
        put(selection, Mode.COPY);
    }

    /** Puts paths down for moving. */
    public void cut(List<CgPath> selection) {
        put(selection, Mode.CUT);
    }

    private void put(List<CgPath> selection, Mode intent) {
        paths.clear();
        paths.addAll(selection);
        mode = intent;
        if (paths.isEmpty()) return;

        // The TEXT form goes to the platform too, so a path pasted into a script or a message is useful.
        // Newline-separated, which is what every file manager writes and what a multi-line paste expects.
        StringBuilder text = new StringBuilder();
        for (CgPath path : paths) {
            if (text.length() > 0) text.append('\n');
            text.append(path);
        }
        CgPlatform.input().setClipboard(text.toString());
    }

    public boolean isEmpty() {
        return paths.isEmpty();
    }

    public Mode mode() {
        return mode;
    }

    /** A copy, so a caller iterating it cannot be surprised by {@link #consumeIfCut()}. */
    public List<CgPath> paths() {
        return List.copyOf(paths);
    }

    /**
     * Clears the clipboard if it held a cut, and reports what it held.
     *
     * <p>Called by a paste once it has issued its operations. A cut is spent — the files have moved, and a
     * second paste would try to move them from a path that no longer exists, which fails with an error
     * about a missing file rather than saying the obvious thing.</p>
     */
    public List<CgPath> consumeIfCut() {
        List<CgPath> held = List.copyOf(paths);
        if (mode == Mode.CUT) paths.clear();
        return held;
    }

    public void clear() {
        paths.clear();
    }
}
