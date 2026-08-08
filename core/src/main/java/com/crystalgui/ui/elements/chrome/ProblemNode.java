package com.crystalgui.ui.elements.chrome;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.diagnostic.Diagnostic;

import javax.annotation.Nullable;

/**
 * One row of the Problems tree — either a file, or a problem in one.
 *
 * <p>VS Code's {@code MarkersModel} has the same two-level shape: {@code ResourceMarkers} holding
 * {@code Marker}s, grouped by file and nothing deeper.</p>
 *
 * <h3>One type for both levels, rather than a sealed pair</h3>
 *
 * <p>{@link com.crystalgui.ui.elements.tree.TreeView} is generic in a single node type, so a two-type tree
 * means a wrapper somewhere regardless. Making the wrapper the node — a resource, plus the diagnostic when
 * there is one — keeps every row able to answer <em>which file am I in</em> without walking to its parent,
 * which is what a click has to know: choosing a problem means opening its file first.</p>
 *
 * @param resource   the file this row is about, at both levels
 * @param diagnostic the problem, or null when this row <em>is</em> the file
 */
public record ProblemNode(Resource resource, @Nullable Diagnostic diagnostic) {

    public static ProblemNode file(Resource resource) {
        return new ProblemNode(resource, null);
    }

    public static ProblemNode problem(Resource resource, Diagnostic diagnostic) {
        return new ProblemNode(resource, diagnostic);
    }

    /** Whether this row is the file heading rather than a problem under it. */
    public boolean isFile() {
        return diagnostic == null;
    }
}
