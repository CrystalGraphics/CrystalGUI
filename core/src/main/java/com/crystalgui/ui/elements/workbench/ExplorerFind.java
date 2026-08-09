package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.fs.CgPath;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.tree.TreeSearch;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The explorer's half of the search — VS Code's {@code ExplorerFindProvider}.
 *
 * <h3>Nearly nothing, and that is the point</h3>
 *
 * <p>The bar, the two modes, the arrows, the counter, the keys and the per-row marking are
 * {@link TreeSearch}'s, on any tree. What is left here is what a <b>file</b> tree knows and a generic one
 * cannot:</p>
 *
 * <ul>
 *   <li><b>Filtering is the source's.</b> {@link WorkspaceTreeSource} lists a directory at a time, so
 *       "does anything beneath this match" cannot be answered without fetching the project — it filters
 *       over what has arrived instead of delegating to {@code FilteredTreeSource}. This is the same bend
 *       both references make: VS Code's {@code IAsyncFindProvider} exists <em>because</em> the explorer
 *       is async, and IntelliJ's speed search sidesteps it by not filtering at all.</li>
 *   <li><b>The descendant count is free here and nowhere else.</b> The source already holds every listing
 *       in a map, so counting matches beneath a folder costs a walk over memory. Deriving it generically
 *       would mean calling {@code children()} on unlisted directories, turning a keystroke into a listing
 *       storm.</li>
 * </ul>
 *
 * @see ProjectFileTree for why a part sits beside the widget rather than behind an interface
 */
final class ExplorerFind implements TreeSearch.Model<CgPath> {

    private final ProjectFileTree tree;

    @Nullable
    private TreeSearch<CgPath> search;

    ExplorerFind(ProjectFileTree tree) {
        this.tree = tree;
    }

    /** Installs the shared component onto the panel's tree. */
    void build() {
        search = TreeSearch.installOn(tree.treeView(), tree.contentBox(), this, tree::activate);
        search.input().setPlaceholder("Search files");
    }

    private TreeSearch<CgPath> search() {
        if (search == null) throw new IllegalStateException("build() has not run");
        return search;
    }

    // ── TreeSearch.Model ────────────────────────────────────────────────────────────────────────

    @Override
    public void setQuery(String query, boolean filtering) {
        tree.source().setFilter(query);
        tree.source().setFindMode(filtering
                ? WorkspaceTreeSource.FindMode.FILTER
                : WorkspaceTreeSource.FindMode.HIGHLIGHT);
    }

    @Override
    public boolean isMatch(CgPath item) {
        return tree.source().isMatch(item);
    }

    @Override
    public List<SearchMatch.Range> matchRanges(CgPath item) {
        return tree.source().matchRanges(item);
    }

    @Override
    public int descendantMatches(CgPath item) {
        return tree.source().descendantMatches(item);
    }

    // ── What the panel exposes ──────────────────────────────────────────────────────────────────

    void openBar() {
        search().open();
    }

    void closeBar() {
        search().close();
    }

    boolean isOpen() {
        return search().isOpen();
    }

    void toggleFindMode() {
        search().toggleMode();
    }

    void setFiltering(boolean filtering) {
        search().setMode(filtering ? TreeSearch.Mode.FILTER : TreeSearch.Mode.HIGHLIGHT);
    }

    void setFilter(String query) {
        search().setQuery(query);
    }

    String filter() {
        return search().query();
    }

    boolean isFiltering() {
        return search().mode() == TreeSearch.Mode.FILTER;
    }

    @Nullable
    CgPath currentMatchPath() {
        return search().currentMatchItem();
    }

    int matchCount() {
        return search().matchCount();
    }

    int currentMatchIndex() {
        return search().currentMatchIndex();
    }

    /** Called from {@link FilesRenderer} during {@code bind}. @see TreeSearch#markRow */
    void applyMarks(UIElement row, ProjectFileTree.RowParts parts, CgPath item, boolean expandable) {
        search().markRow(row, parts.label(), parts.badge(), item, expandable);
    }
}
