package com.crystalgui.ui.elements.chrome;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Problems tree's contents — every file with something to report, and what it reports.
 *
 * <p>VS Code's {@code MarkersModel} plus its {@code MarkersFilters}, over our {@link Markers} index.</p>
 *
 * <h3>Filtering happens here, not in the view</h3>
 *
 * <p>Because it changes the <b>shape</b> of the tree and not just what is painted: a file whose only error
 * is filtered out must stop being a row, or the panel shows a heading you can expand onto nothing. A view
 * that hid rows after the fact could not do that — it would have to know that a parent's visibility depends
 * on its children's, which is exactly the knowledge a data source already has.</p>
 *
 * <p>The same reason the severity filter is a set rather than a minimum level. "Errors and hints but not
 * warnings" is not a threshold, and VS Code's three independent toggles are not a ladder.</p>
 */
public final class ProblemsTreeSource implements com.crystalgui.ui.elements.tree.TreeDataSource<ProblemNode> {

    private final Markers markers;

    /** Which severities are shown. All of them until something says otherwise. */
    private final Set<DiagnosticSeverity> severities = EnumSet.allOf(DiagnosticSeverity.class);

    private String text = "";

    /**
     * When set, the only file the tree shows — VS Code's "Show Active File Only".
     *
     * <p>Kept as a filter rather than as a second panel, because it is the same list asked a narrower
     * question. The workbench sets it from whichever tab is in front.</p>
     */
    @Nullable
    private Resource only;

    public ProblemsTreeSource(Markers markers) {
        this.markers = markers;
    }

    public Markers markers() {
        return markers;
    }

    /** Shows or hides one severity. @see ProblemsTreeSource */
    public void setShown(DiagnosticSeverity severity, boolean shown) {
        if (shown) severities.add(severity);
        else severities.remove(severity);
    }

    public boolean isShown(DiagnosticSeverity severity) {
        return severities.contains(severity);
    }

    /** Substring match against the message, case-insensitive. Empty shows everything. */
    public void setTextFilter(@Nullable String value) {
        this.text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public String textFilter() {
        return text;
    }

    /**
     * Restricts the tree to one file, or to all of them when null. @see #only
     *
     * <p>{@code null} here means <b>unrestricted</b>, which is why {@link #setScope} exists beside it:
     * "the File tab with nothing open" is a restriction to nothing, and passing null for it would show the
     * entire workspace under a tab claiming to show one file.</p>
     */
    public void setOnlyResource(@Nullable Resource resource) {
        setScope(resource != null, resource);
    }

    /**
     * Sets the scope explicitly — restricted to {@code file}, or the whole workspace.
     *
     * <p>The distinction {@code setOnlyResource} alone cannot draw: restricted with no file is <b>empty</b>,
     * not unrestricted. Without it the File tab showed every problem in the workspace whenever nothing was
     * open, which is the most misleading thing a scoped view can do — it does not look like a bug, it looks
     * like the project having problems in files you are not in.</p>
     */
    public void setScope(boolean restricted, @Nullable Resource file) {
        this.restricted = restricted;
        this.only = file;
    }

    /** Whether the tree is scoped to one file at all. @see #setScope */
    private boolean restricted;

    @Nullable
    public Resource onlyResource() {
        return only;
    }

    @Override
    public List<ProblemNode> roots() {
        List<ProblemNode> files = new ArrayList<>();
        // RESTRICTED TO NOTHING IS EMPTY, not everything. @see #setScope
        if (restricted && only == null) return files;
        for (Resource resource : markers.resources()) {
            if (restricted && !only.equals(resource)) continue;
            // A FILE WITH NOTHING LEFT AFTER FILTERING IS NOT A ROW. Otherwise the panel offers a heading
            // that expands onto nothing, which reads as a broken tree rather than as an active filter.
            if (matching(resource).isEmpty()) continue;
            files.add(ProblemNode.file(resource));
        }
        return files;
    }

    @Override
    public List<ProblemNode> children(ProblemNode parent) {
        if (parent == null || !parent.isFile()) return List.of();
        List<ProblemNode> rows = new ArrayList<>();
        for (Diagnostic diagnostic : matching(parent.resource())) {
            rows.add(ProblemNode.problem(parent.resource(), diagnostic));
        }
        return rows;
    }

    @Override
    public boolean hasChildren(ProblemNode item) {
        return item != null && item.isFile();
    }

    /** Everything in one file that survives the current filter, in document order. */
    public List<Diagnostic> matching(Resource resource) {
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic diagnostic : markers.read(resource)) {
            if (!severities.contains(diagnostic.severity())) continue;
            if (!text.isEmpty() && !diagnostic.message().toLowerCase(Locale.ROOT).contains(text)) continue;
            kept.add(diagnostic);
        }
        return kept;
    }

    /** How many problems the tree is showing in total — what the panel's summary counts. */
    public int shownCount() {
        int total = 0;
        for (ProblemNode file : roots()) total += matching(file.resource()).size();
        return total;
    }
}
