package com.crystalgui.ui.elements.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Any {@link TreeDataSource}, with non-matching branches hidden.
 *
 * <h3>A match keeps its ancestors, or it is unreachable</h3>
 *
 * <p>The whole difficulty of filtering a tree, and the reason this is a class rather than an {@code if}
 * at each call site: a node three levels down that matches is only useful if the three above it survive
 * too. Filtering each level independently hides the path to every deep hit, and the search then appears
 * to find nothing while quietly matching plenty.</p>
 *
 * <p>So a node is kept when <b>it matches, or anything beneath it does</b>. That also gives the behaviour
 * people expect without asking for it: a matching node keeps its whole subtree, so searching for a
 * category shows everything in it.</p>
 *
 * <h3>The predicate is the caller's</h3>
 *
 * <p>This class never inspects an item. "Does this match" is domain knowledge — for a settings tree it
 * means "does any setting at or under this path match the query", which involves labels and descriptions
 * this source has never heard of. Keeping it out is what lets the same decorator serve a file tree, a
 * command list and a settings tree.</p>
 *
 * <h3>No query is not an empty query</h3>
 *
 * <p>With no filter set this delegates entirely, including {@link #hasChildren}, so an unfiltered tree
 * costs one virtual call per query and nothing else. A decorator that always walked its subtree would
 * make every ordinary tree pay for a feature it is not using.</p>
 */
public final class FilteredTreeSource<T> implements TreeDataSource<T> {

    /** How deep the "anything beneath me matches" walk will go before giving up. */
    private static final int MAX_DEPTH = 32;

    private final TreeDataSource<T> delegate;

    private Predicate<T> filter;

    public FilteredTreeSource(TreeDataSource<T> delegate) {
        this.delegate = delegate;
    }

    /** Sets the predicate, or null to show everything. */
    public FilteredTreeSource<T> setFilter(Predicate<T> filter) {
        this.filter = filter;
        return this;
    }

    public boolean isFiltering() {
        return filter != null;
    }

    public TreeDataSource<T> delegate() {
        return delegate;
    }

    @Override
    public List<T> roots() {
        return keep(delegate.roots());
    }

    @Override
    public List<T> children(T parent) {
        // A MATCHING node keeps its whole subtree, unfiltered. Searching for a category and being shown
        // the category with all its contents emptied out would be a strange answer, and it is the one
        // filtering each level independently gives: the children do not match the query, only their
        // parent did.
        if (filter != null && filter.test(parent)) return delegate.children(parent);
        return keep(delegate.children(parent));
    }

    @Override
    public boolean hasChildren(T item) {
        if (filter == null || filter.test(item)) return delegate.hasChildren(item);
        return !children(item).isEmpty();
    }

    private List<T> keep(List<T> items) {
        if (filter == null) return items;
        List<T> kept = new ArrayList<>(items.size());
        for (T item : items) {
            if (survives(item, 0)) kept.add(item);
        }
        return kept;
    }

    /**
     * Whether {@code item} or anything under it matches.
     *
     * <p>Depth-capped rather than cycle-checked with a visited set: a tree source that loops is a bug in
     * whoever wrote it, and the cheap defence keeps a malformed one from hanging the frame instead of
     * merely being wrong. {@code UITreeTraversal} and {@code SettingsScope} both take the same view.</p>
     */
    private boolean survives(T item, int depth) {
        if (filter.test(item)) return true;
        if (depth >= MAX_DEPTH) return false;
        for (T child : delegate.children(item)) {
            if (survives(child, depth + 1)) return true;
        }
        return false;
    }
}
