package com.crystalgui.ui.dom;

import com.crystalgui.style.selector.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * {@code querySelector} and friends over the node tree — the same selector engine the stylesheets
 * use, so a query and a rule can never disagree about what matches.
 *
 * <h3>The LIGHT tree, which is what the DOM queries</h3>
 *
 * <p>These walk {@link UIElement#children()}, never the composed tree, and that is the web's own rule:
 * {@code querySelector} does not see into a shadow root, because a shadow tree is the widget's
 * private business and a caller reaching into one has coupled itself to an implementation detail.
 * The engine has exactly one query that crosses the boundary — {@link UIElement#composedSubtree()} —
 * and it is the services', for hit-testing and focus order, where crossing is the entire point.</p>
 *
 * <p>The old engine had no boundary to respect, so {@code UITreeTraversal.querySelector} reached
 * every internal child of every widget: the rule that {@code .__content__} is claimed by three
 * unrelated widgets was a styling problem AND a query problem, and only the styling half was ever
 * written down.</p>
 *
 * <h3>Combinators are evaluated against the LIVE tree, not the scope</h3>
 *
 * <p>{@link Selector#matches} walks real parents, so {@code ".a .b"} can match inside this subtree
 * by virtue of an ancestor <em>outside</em> it. That is what the DOM does too, and it surprises
 * people often enough to be worth stating twice — the old traversal's javadoc says it as well.</p>
 */
public final class NodeQueries {

    private NodeQueries() {
    }

    /**
     * Parsed selectors, keyed by their source text.
     *
     * <p>Queries are made from listeners and per-frame code — a row binding, a command's
     * {@code enabledWhen} — so parsing on every call would put the CSS parser in the frame budget.
     * Bounded by the number of distinct selector STRINGS a program contains, which is a property of
     * the source rather than of the run.</p>
     */
    private static final Map<String, Selector> PARSED = new ConcurrentHashMap<>();

    private static Selector selector(String raw) {
        return PARSED.computeIfAbsent(raw, Selector::parse);
    }

    /**
     * The first match in document order, or null.
     *
     * @param includeScope whether {@code scope} itself is a candidate. A node-level query passes
     *                     false (DOM semantics: descendants only); a document-level one passes true,
     *                     since the document plays the part the root element does.
     */
    @Nullable
    public static UIElement querySelector(UIElement scope, String selector, boolean includeScope) {
        return first(scope, selector(selector), includeScope);
    }

    @Nullable
    private static UIElement first(UIElement scope, Selector parsed, boolean includeScope) {
        if (includeScope && parsed.matches(scope)) return scope;
        for (UIElement child : scope.children()) {
            UIElement found = first(child, parsed, true);
            if (found != null) return found;
        }
        return null;
    }

    /** Every match in {@code scope}'s light subtree, in document order (depth-first pre-order). */
    public static List<UIElement> querySelectorAll(UIElement scope, String selector, boolean includeScope) {
        List<UIElement> out = new ArrayList<>();
        all(scope, selector(selector), includeScope, out);
        return out;
    }

    private static void all(UIElement scope, Selector parsed, boolean includeScope, List<UIElement> out) {
        if (includeScope && parsed.matches(scope)) out.add(scope);
        for (UIElement child : scope.children()) all(child, parsed, true, out);
    }

    /**
     * The first node with this exact id, or null.
     *
     * <p>A plain walk rather than a selector, so an id containing selector punctuation can still be
     * looked up — the same reason the old traversal does it this way.</p>
     */
    @Nullable
    public static UIElement getElementById(UIElement scope, String id, boolean includeScope) {
        if (includeScope && scope.id().equals(id)) return scope;
        for (UIElement child : scope.children()) {
            UIElement found = getElementById(child, id, true);
            if (found != null) return found;
        }
        return null;
    }

    public static List<UIElement> getElementsByClassName(UIElement scope, String className, boolean includeScope) {
        List<UIElement> out = new ArrayList<>();
        byClass(scope, className, includeScope, out);
        return out;
    }

    private static void byClass(UIElement scope, String className, boolean includeScope, List<UIElement> out) {
        if (includeScope && scope.hasClass(className)) out.add(scope);
        for (UIElement child : scope.children()) byClass(child, className, true, out);
    }
}
