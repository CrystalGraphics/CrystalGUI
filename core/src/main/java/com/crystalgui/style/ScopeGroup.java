package com.crystalgui.style;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

/**
 * One scope standing for many roots, so a sheet installed once reaches every member — what many small shadow
 * trees wearing the same sheets need, where installing per root would re-match the window for each.
 *
 * <pre>{@code
 * ScopeGroup cards = new ScopeGroup();
 * window.styles().addStylesheet(sheet, cards);   // once
 * cards.add(card.attachShadow());                // per card, any time
 * }</pre>
 *
 * <ul>
 *   <li>Members are held weakly; a root that is collected leaves by itself.</li>
 *   <li>Add a root before its content is attached, or that content matches without the group's sheets until
 *       something else re-matches it.</li>
 * </ul>
 */
public final class ScopeGroup implements StyleScope {

    private final Set<StyleScope> members = Collections.newSetFromMap(new WeakHashMap<>());

    public void add(StyleScope root) {
        members.add(root);
    }

    public void remove(StyleScope root) {
        members.remove(root);
    }

    @Override
    public boolean scopes(StyleScope candidate) {
        return members.contains(candidate);
    }

    /** A group is walked to, never from. */
    @Override
    @Nullable
    public StyleScope styleScopeParent() {
        return null;
    }
}
