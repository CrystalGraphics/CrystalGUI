package com.crystalgui.ui.tree;

import com.crystalgui.ui.UIElement;

import com.crystalgui.style.selector.Selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Pure, stateless queries over the UIElement tree structure.
 * No method here mutates any element or knows about input/focus/style state —
 * everything is derivable purely from parent/child/sibling relationships.
 */
public final class UITreeTraversal {

    private UITreeTraversal() {} // non-instantiable

    // ── Boundary chains ──────────────────────────────────────────────────

    /**
     * Walks every element being <b>left</b> — {@code from} up to but excluding {@code common} —
     * innermost first.
     *
     * <p>Lives here, and paired with {@link #forEachEntered}, because the ordering <em>is</em> the
     * contract and it was written twice: once for {@code mouseenter}/{@code mouseleave} and again for
     * drag enter/leave. Two hand-rolled copies of a subtle order is how they drift apart, and a drag
     * that fired its boundary events in a different order from the pointer's would be a genuinely
     * horrible bug to find.</p>
     *
     * <p>Passing {@code null} for {@code common} walks all the way to the root, which is what a
     * cancelled interaction wants.</p>
     */
    public static void forEachLeft(UIElement from, UIElement common, java.util.function.Consumer<UIElement> action) {
        for (var e = from; e != null && e != common; e = e.getParent()) action.accept(e);
    }

    /** Walks every element being <b>entered</b> — {@code common} (exclusive) down to {@code to} —
     * outermost first, so an ancestor learns of the arrival before its child. @see #forEachLeft */
    public static void forEachEntered(UIElement to, UIElement common, java.util.function.Consumer<UIElement> action) {
        int depth = 0;
        for (var e = to; e != null && e != common; e = e.getParent()) depth++;
        if (depth == 0) return;
        UIElement[] chain = new UIElement[depth];
        int i = depth;
        for (var e = to; e != null && e != common; e = e.getParent()) chain[--i] = e;
        for (UIElement e : chain) action.accept(e);
    }

    // ── Ancestry ─────────────────────────────────────────────────────────

    /**
     * The deepest element that is an ancestor of both, or {@code null} when there is none.
     *
     * <p><b>Null when they are in different trees</b>, which is not a hypothetical: the hover diff
     * compares last frame's element against this frame's, and an element deleted while the pointer was
     * over it is detached but still referenced for one more frame. Two chains that never converge used
     * to walk each other straight off the end of their trees and throw an NPE inside the hover diff —
     * from a delete, which is nowhere near where it lands.</p>
     *
     * <p>The depth cache cannot be trusted to prevent it either. A detached element's cached depth is
     * whatever it was when it was attached, so the equalising walk can overshoot before the convergence
     * loop even starts. Both loops are therefore null-guarded rather than relying on the invariant that
     * the inputs share a root.</p>
     */
    public static UIElement commonAncestor(UIElement a, UIElement b) {
        if (a == null || b == null) return null;

        var depthA = a.getRuntimeCache().getDepth();
        var depthB = b.getRuntimeCache().getDepth();

        var nodeA = a;
        var nodeB = b;

        while (depthA > depthB && nodeA != null) { nodeA = nodeA.getParent(); depthA--; }
        while (depthB > depthA && nodeB != null) { nodeB = nodeB.getParent(); depthB--; }

        while (nodeA != nodeB && nodeA != null && nodeB != null) {
            nodeA = nodeA.getParent();
            nodeB = nodeB.getParent();
        }
        return nodeA == nodeB ? nodeA : null;
    }

    /**
     * Whether {@code node} is {@code ancestor} or sits beneath it.
     *
     * <p>Reflexive, like the DOM's {@code contains}: an element contains itself. Callers asking "did this
     * happen inside me" mean to include themselves, and the one that does not can compare first.</p>
     *
     * <p>Null-safe both ways, and walks parents rather than consulting the depth cache — a detached
     * element's cached depth is whatever it was when it was attached, which is the trap
     * {@link #commonAncestor} documents at length.</p>
     */
    public static boolean isAncestor(UIElement ancestor, UIElement node) {
        if (ancestor == null || node == null) return false;
        for (UIElement current = node; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    /** Root-to-element chain, root first. Empty-safe: single-element list if element has no parent. */
    public static UIElement[] pathToRoot(UIElement element) {
        UIElement[] path = new UIElement[element.getRuntimeCache().getDepth()];
        int i = path.length - 1;
        for (var e = element; e != null; e = e.getParent()) {
            path[i--] = e;
        }
        return path;
    }

    // ── Focus order (tab traversal) ─────────────────────────────────────

    /*
     * On the `hasFocusableDescendant` guards below: that cache is only a fast-path filter, never a
     * commitment. Every recursive descent keeps searching the remaining siblings when it comes back
     * empty, rather than returning the null straight out.
     *
     * This matters because the flag can legitimately be stale — `focusable()` depends on
     * enabled/focus-policy/display, and not every path that changes those invalidates the chain
     * (display in particular is written straight through the Taffy bridge). Before this, a single
     * stale-true bit made a walk descend into a subtree with nothing focusable, return null, and
     * never try the next sibling — which killed the Tab key outright rather than skipping one
     * element. Correct behaviour with a fresh cache is unchanged; this only bounds the blast radius
     * when it isn't.
     */

    // ── Selector queries (DOM-shaped) ────────────────────────────────────

    /**
     * Parsed-selector cache. Queries are expected inside per-frame code, and re-parsing the same
     * string every frame would be pure waste. Same lazy-cache convention as
     * {@code StyleSheetRegistry}.
     */
    private static final Map<String, Selector> SELECTOR_CACHE = new ConcurrentHashMap<>();

    private static Selector selector(String raw) {
        return SELECTOR_CACHE.computeIfAbsent(raw, Selector::parse);
    }

    /**
     * First element in {@code scope}'s subtree matching {@code selector}, in document order, or
     * {@code null}.
     *
     * <p>Uses the same {@link Selector} the stylesheet cascade uses — there is deliberately no second
     * matcher — so only the supported subset applies (id/class/type/pseudo-class, descendant and child
     * combinators; no {@code :not()}, attribute selectors, sibling combinators or pseudo-elements —
     * see {@code StyleSheet}'s class javadoc).</p>
     *
     * <p><b>Combinators are evaluated against the live tree, not the scope.</b>
     * {@link Selector#matches} walks real parents, so {@code ".a .b"} can match inside this subtree by
     * virtue of an ancestor <em>outside</em> it. That is what the DOM does too, and it surprises
     * people often enough to be worth stating.</p>
     *
     * @param includeScope whether {@code scope} itself is a candidate. Element-level queries pass
     *                     {@code false} (DOM semantics: descendants only); window-level queries pass
     *                     {@code true}, since the root element is the document here.
     */
    public static UIElement querySelector(UIElement scope, String selector, boolean includeScope) {
        Selector parsed = selector(selector);
        if (includeScope && parsed.matches(scope)) return scope;
        for (UIElement child : scope.getChildren()) {
            UIElement found = querySelector(child, selector, true);
            if (found != null) return found;
        }
        return null;
    }

    /** Every match in {@code scope}'s subtree, in document order (depth-first pre-order). */
    public static List<UIElement> querySelectorAll(UIElement scope, String selector, boolean includeScope) {
        List<UIElement> out = new ArrayList<>();
        collectMatches(scope, selector(selector), includeScope, out);
        return out;
    }

    private static void collectMatches(UIElement scope, Selector parsed, boolean includeScope, List<UIElement> out) {
        if (includeScope && parsed.matches(scope)) out.add(scope);
        for (UIElement child : scope.getChildren()) {
            collectMatches(child, parsed, true, out);
        }
    }

    /** First element with this exact id, or {@code null}. Plain walk rather than a selector, so an id
     * containing selector punctuation can still be looked up. */
    public static UIElement getElementById(UIElement scope, String id, boolean includeScope) {
        if (includeScope && scope.getId().equals(id)) return scope;
        for (UIElement child : scope.getChildren()) {
            UIElement found = getElementById(child, id, true);
            if (found != null) return found;
        }
        return null;
    }

    /** Every element carrying this class, in document order. */
    public static List<UIElement> getElementsByClassName(UIElement scope, String className, boolean includeScope) {
        List<UIElement> out = new ArrayList<>();
        collectByClass(scope, className, includeScope, out);
        return out;
    }

    private static void collectByClass(UIElement scope, String className, boolean includeScope, List<UIElement> out) {
        if (includeScope && scope.hasClass(className)) out.add(scope);
        for (UIElement child : scope.getChildren()) {
            collectByClass(child, className, true, out);
        }
    }

    /*
     * Two predicates, and the distinction is the whole of the roving-tabindex pattern:
     *
     *   focusable()  — may hold focus at all. What a focus *delegate* wants (a dialog handing focus to
     *                  its first control), and what arrow-key navigation inside a composite wants.
     *   tabbable()   — additionally in the Tab sequence. What Tab/Shift+Tab wants.
     *
     * They differ only for FocusPolicy.CLICK_NOT_TABBABLE. Keeping them as constants rather than
     * repeating `x.focusable()` inline is what stops the four walkers from drifting apart, which is
     * exactly how a Tab key ends up skipping one direction but not the other.
     */
    private static final Predicate<UIElement> FOCUSABLE = UIElement::focusable;
    private static final Predicate<UIElement> TABBABLE = UIElement::tabbable;

    /** First element able to hold focus in this subtree, in document order. The focus-delegate query —
     * see the predicate note above for why this is not the same as {@link #firstTabbableIn}. */
    public static UIElement firstFocusableIn(UIElement subtreeRoot) { return firstIn(subtreeRoot, FOCUSABLE); }

    /** Last element able to hold focus in this subtree, in reverse document order. */
    public static UIElement lastFocusableIn(UIElement subtreeRoot) { return lastIn(subtreeRoot, FOCUSABLE); }

    /** Where Tab lands when nothing is focused yet. */
    public static UIElement firstTabbableIn(UIElement subtreeRoot) { return firstIn(subtreeRoot, TABBABLE); }

    /** Where Shift+Tab lands when nothing is focused yet. */
    public static UIElement lastTabbableIn(UIElement subtreeRoot) { return lastIn(subtreeRoot, TABBABLE); }

    private static UIElement firstIn(UIElement subtreeRoot, Predicate<UIElement> accepts) {
        if (accepts.test(subtreeRoot)) return subtreeRoot;
        for (UIElement child : subtreeRoot.getChildren()) {
            if (!child.getRuntimeCache().hasFocusableDescendant.get()) continue;
            UIElement found = firstIn(child, accepts);
            if (found != null) return found;
        }
        return null;
    }

    private static UIElement lastIn(UIElement subtreeRoot, Predicate<UIElement> accepts) {
        List<UIElement> children = subtreeRoot.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            if (!children.get(i).getRuntimeCache().hasFocusableDescendant.get()) continue;
            UIElement found = lastIn(children.get(i), accepts);
            if (found != null) return found;
        }
        return accepts.test(subtreeRoot) ? subtreeRoot : null;
    }

    /** Previous element in the Tab sequence, or {@code null} at the start of the document. */
    public static UIElement previousTabbable(UIElement current) {
        return previousTabbable(current, null);
    }

    /**
     * As {@link #previousTabbable(UIElement)}, but never climbs above {@code scope} — <b>this is the
     * focus trap</b>. Pass the active modal dialog and Shift+Tab cannot walk out of it, which is what
     * "everything outside a modal is inert" means for sequential navigation. {@code null} scope means the
     * whole document.
     */
    public static UIElement previousTabbable(UIElement current, UIElement scope) {
        UIElement node = current;
        while (node != scope && node.getParent() != null) {
            List<UIElement> siblings = node.getParent().getChildren();
            for (int i = node.getSiblingIndex() - 1; i >= 0; i--) {
                if (!siblings.get(i).getRuntimeCache().hasFocusableDescendant.get()) continue;
                UIElement found = lastIn(siblings.get(i), TABBABLE);
                if (found != null) return found;
            }
            if (node.getParent().tabbable()) return node.getParent();
            node = node.getParent();
        }
        return null;
    }

    /** Next element in the Tab sequence, or {@code null} at the end of the document. */
    public static UIElement nextTabbable(UIElement current) {
        return nextTabbable(current, null);
    }

    /** As {@link #nextTabbable(UIElement)}, but never climbs above {@code scope}. @see #previousTabbable(UIElement, UIElement) */
    public static UIElement nextTabbable(UIElement current, UIElement scope) {
        for (UIElement child : current.getChildren()) {
            if (!child.getRuntimeCache().hasFocusableDescendant.get()) continue;
            UIElement found = firstIn(child, TABBABLE);
            if (found != null) return found;
        }
        UIElement node = current;
        while (node != scope && node.getParent() != null) {
            List<UIElement> siblings = node.getParent().getChildren();
            for (int i = node.getSiblingIndex() + 1; i < siblings.size(); i++) {
                if (!siblings.get(i).getRuntimeCache().hasFocusableDescendant.get()) continue;
                UIElement found = firstIn(siblings.get(i), TABBABLE);
                if (found != null) return found;
            }
            node = node.getParent();
        }
        return null;
    }
}