package com.crystalgui.widget.layout;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Many pages, one shown — built on first visit and kept alive after.
 *
 * <h3>Lazy, then permanent, and both halves earn their keep</h3>
 *
 * <p><b>Lazy</b> because a window with a hundred categories must not build a hundred panels to open.
 * <b>Permanent</b> because a page that is rebuilt each visit forgets everything the view was holding —
 * scroll offset, which groups were collapsed, a half-typed value in a field. Coming back to a page you
 * were just on and finding it scrolled to the top is the sort of thing nobody reports and everybody
 * notices.</p>
 *
 * <p>The pair is why this is a class rather than a {@code clearAllChildren()} at the call site: either
 * half alone is the wrong trade, and the combination is fiddly enough to be worth writing once.</p>
 *
 * <h3>Hidden, not detached</h3>
 *
 * <p>An off-screen page keeps its place in the tree with {@code display: none} — the {@code Tab} idiom.
 * Detaching would churn the Taffy tree on every navigation and lose exactly the state the caching exists
 * to preserve.</p>
 *
 * @param <K> whatever names a page. A path, an id, an enum constant
 */
public class PageStack<K> extends UINode {

    public static final Name NAME = Name.of("pagestack");

    public static final String PAGE_CLASS = "__page__";

    /** Shown when a key has no page — see {@link #setPlaceholder}. */
    public static final String PLACEHOLDER_CLASS = "__page-placeholder__";

    private final Map<K, UINode> pages = new LinkedHashMap<>();

    private Function<K, UINode> factory = key -> null;

    @Nullable
    private UINode placeholder;

    @Nullable
    private K current;

    public PageStack() {
        super(NAME);
    }

    /**
     * How a page is built, the first time one is asked for.
     *
     * <p>May return null, which means "this key has no page of its own" — a parent category with nothing
     * declared directly on it, for instance. The placeholder is shown instead, so the node is still worth
     * clicking rather than a dead end.</p>
     */
    public PageStack<K> setPageFactory(Function<K, UINode> factory) {
        this.factory = factory == null ? key -> null : factory;
        return this;
    }

    /** What to show for a key with no page. Replaces any previous placeholder. */
    public PageStack<K> setPlaceholder(@Nullable UINode replacement) {
        if (placeholder != null) remove(placeholder);
        placeholder = replacement;
        if (placeholder != null) {
            placeholder.addClass(PLACEHOLDER_CLASS);
            placeholder.setDisplayed(false);
            append(placeholder);
        }
        return this;
    }

    /**
     * Shows the page for {@code key}, building it if this is its first visit.
     *
     * @return the page now shown, or null when the key has none
     */
    @Nullable
    public UINode show(@Nullable K key) {
        current = key;
        UINode shown = key == null ? null : pageFor(key);

        for (Map.Entry<K, UINode> entry : pages.entrySet()) {
            // NULL IS A CACHED ANSWER, not a missing one -- a key whose factory said "no page of my own"
            // is remembered so it is not asked again, and there is nothing to show or hide for it.
            UINode page = entry.getValue();
            if (page != null) page.setDisplayed(page == shown);
        }
        if (placeholder != null) placeholder.setDisplayed(shown == null && key != null);
        return shown;
    }

    @Nullable
    private UINode pageFor(K key) {
        if (pages.containsKey(key)) return pages.get(key);
        UINode built = factory.apply(key);
        if (built != null) {
            built.addClass(PAGE_CLASS);
            // ADDED BEFORE it is shown, so the first layout pass that runs after this sees it -- an
            // element added and measured in the same breath is the ordering bug the command palette's key
            // boxes already paid for.
            append(built);
        }
        // Cached either way, INCLUDING a null: a factory that answers "no page" answers it once rather
        // than on every navigation to the same node.
        pages.put(key, built);
        return built;
    }

    @Nullable
    public K current() {
        return current;
    }

    /** The page for a key if one has been built, without building one. */
    @Nullable
    public UINode built(K key) {
        return pages.get(key);
    }

    public List<K> builtKeys() {
        List<K> keys = new ArrayList<>();
        for (Map.Entry<K, UINode> entry : pages.entrySet()) {
            if (entry.getValue() != null) keys.add(entry.getKey());
        }
        return keys;
    }

    /** Drops every built page — for a stack whose subject changed entirely. */
    public void clearPages() {
        for (UINode page : pages.values()) {
            if (page != null) remove(page);
        }
        pages.clear();
        current = null;
    }
}
