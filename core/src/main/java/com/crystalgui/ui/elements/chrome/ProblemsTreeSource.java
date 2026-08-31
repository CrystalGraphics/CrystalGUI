package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.Markers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
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
public final class ProblemsTreeSource implements com.crystalgui.core.collection.tree.TreeDataSource<ProblemNode> {

    private final Markers markers;

    /** Which severities are shown. All of them until something says otherwise. */
    private final Set<DiagnosticSeverity> severities = EnumSet.allOf(DiagnosticSeverity.class);

    private String text = "";

    /** The parsed query, carrying the Match Case / Words / Regex options. Null when not searching. */
    @Nullable
    private SearchQuery query;

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
        setTextFilter(value == null ? null : SearchQuery.of(value));
    }

    /**
     * As above, with the query's <b>options</b> — Match Case, Words, Regex.
     *
     * <p>This used to be a private matcher: {@code value.trim().toLowerCase()} and a {@code contains}. It
     * was the second notion of "matches" in this panel (29.11 fixed the first), and it silently ignored
     * every option the search bar offered — {@code GRAPH} with Match Case and Words both lit still matched
     * {@code shadergraph}, because nothing about the toggles reached this far.</p>
     */
    public void setTextFilter(@Nullable SearchQuery value) {
        this.query = value == null || value.isEmpty() ? null : value;
        this.text = this.query == null ? "" : this.query.text();
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

    /**
     * Everything in one file that survives the current filter — <b>worst first</b>, then document order.
     *
     * <h3>Severity before position, and only here</h3>
     *
     * <p>{@code Diagnostic}'s natural order is positional and {@code DiagnosticSet} is sorted by it, which
     * is right for the model: an editor asks "what is on this row", and a squiggle lookup wants document
     * order. A <em>panel</em> is read top-down to decide what to fix, and an error is not one of six things
     * to scan for — it is the reason the panel is open. Burying two syntax errors under four unused-import
     * warnings, purely because the imports are at the top of the file, hides the only rows that stop the
     * file compiling.</p>
     *
     * <p>Both references sort their view this way and leave their marker model alone, which is the same
     * split — so this is a comparator here rather than a change to {@code compareTo}, where it would
     * reorder every consumer that has no opinion about severity.</p>
     *
     * <p>{@link DiagnosticSeverity}'s own declaration order is the ranking — {@code ERROR}, {@code WARNING},
     * {@code INFORMATION}, {@code HINT} — so this reads the enum rather than restating it. A new severity
     * slots in wherever it is declared, which is the one place anybody would think to look.</p>
     */
    public List<Diagnostic> matching(Resource resource) {
        // THE FILE'S NAME COUNTS AS A MATCH FOR EVERYTHING IN IT.
        //
        // The filter used to read messages only, while the search treats a heading as searchable by its
        // FILE NAME -- two different answers to "does this match", in one panel. It showed as `g` listing
        // both shadergraphs and `graph` listing one: `new.shadergraph` has "graph" in its name and not in
        // its message, so the row the search would have marked was filtered away before the marking ran.
        //
        // Keeping the whole file rather than only its named heading, because a heading that expands onto
        // nothing is worse than either: the name matched, so the file is what you were looking for.
        boolean searching = query != null;
        boolean fileMatches = searching && SearchMatcher.match(query, resource.name(), 0) != null;
        List<Diagnostic> kept = new ArrayList<>();
        for (Diagnostic diagnostic : markers.read(resource)) {
            if (!severities.contains(diagnostic.severity())) continue;
            if (searching && !fileMatches
                    && SearchMatcher.match(query, diagnostic.message(), 0) == null) continue;
            kept.add(diagnostic);
        }
        // Document order WITHIN a severity, which is what makes the group readable once you are in it --
        // and Diagnostic's own compareTo is exactly that, so it is the tiebreak rather than a second
        // comparator to keep in step.
        kept.sort(Comparator.comparingInt((Diagnostic d) -> d.severity().ordinal())
                .thenComparing(Comparator.naturalOrder()));
        return kept;
    }

    /** How many problems the tree is showing in total — what the panel's summary counts. */
    public int shownCount() {
        int total = 0;
        for (ProblemNode file : roots()) total += matching(file.resource()).size();
        return total;
    }
}
