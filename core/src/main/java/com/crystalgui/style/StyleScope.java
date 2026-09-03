package com.crystalgui.style;

import javax.annotation.Nullable;

/**
 * Something a stylesheet can be scoped <em>to</em> — CSS {@code @scope}'s root.
 *
 * <p>Separate from {@link Styleable} because the two answer different questions, and a shadow root is
 * the case that proves it. A scope root is only ever <b>walked to</b>: {@code StyleEngine} asks how
 * many hops separate an element from it, and ranks candidates by that distance. It is never matched,
 * never asked for an id or a class, and never given a cascade of its own. So the set of things that
 * can be a scope is strictly larger than the set of things that can be styled.</p>
 *
 * <p>A {@code ShadowRoot} is exactly that difference. It is a {@code DocumentFragment}: no id, no
 * classes, no tag a selector can name, so it is not {@code Styleable} — and scoping a composite's own
 * sheet to it is the documented way that sheet reaches its parts and nothing outside them. Before the
 * Node/Element split a shadow root was an element and this distinction had nowhere to live, which is
 * why the scope parameter was typed {@code Styleable} and happened to work.</p>
 *
 * <p>{@code Styleable} extends this, so every element is a scope; the node tree implements it on
 * {@code UINode}, so every node is. The chain is the <b>light</b> parent chain including shadow roots
 * — not {@link Styleable#getParent()}, which stops at a shadow boundary on purpose so that a
 * descendant combinator outside a shadow tree cannot reach into one.</p>
 */
public interface StyleScope {

    /**
     * The next scope out: the light parent, <b>including</b> a shadow root, or null at a root.
     *
     * <p>This is the one place the two chains differ. {@code getParent()} answers null when the
     * parent is a shadow root, because a rule outside must not match through it; this answers the
     * shadow root, because a sheet scoped to it must be findable from the parts inside it.</p>
     */
    @Nullable
    StyleScope styleScopeParent();
}
