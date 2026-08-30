package com.crystalgui.ui.service;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;

/**
 * The lifecycle service: freeze, thaw, destroy — and the reason hide-as-detach has no counterpart.
 *
 * <h3>What a freeze is</h3>
 *
 * <p>The subtree stays in the tree and stops being live: its boxes are dropped, so it lays out
 * nothing, paints nothing and hit-tests nothing; its per-frame hooks are dropped; it matches no
 * selector; and input forgets every reference to it. Everything it HOLDS survives, because nothing
 * was taken away — the text in a field, a scroll position, a selection, an id, a listener.</p>
 *
 * <h3>Why that is the whole point</h3>
 *
 * <p>The old engine hid a window by DETACHING it, and eight invariant rows are the bill: session
 * state captured on the way out and re-applied on the way back, every Taffy node destroyed and
 * rebuilt, the modal and popover stacks popped, a stylesheet's candidates outliving the reparent and
 * winning forever, a geometry that had to be measured BEFORE the detach or came back zero, a ticker
 * that carried on because nothing else could stop it, and a `removeChild` that silently refused an
 * internal child so the window reported HIDDEN and stayed on screen. None of that exists here:
 * freezing takes nothing out of the tree, so there is nothing to capture and nothing to restore.</p>
 */
public final class Lifecycle {

    private final UIDocument document;

    public Lifecycle(UIDocument document) {
        this.document = document;
    }

    /**
     * Freezes a subtree in place. Idempotent; freezing something already frozen does nothing.
     *
     * <p>The frozen flag is set on the whole composed subtree so every reader — the box tree's sync,
     * the style pass, the focus predicates — can answer with one field read rather than a walk.</p>
     */
    public void freeze(UINode node) {
        if (node.isFrozen()) return;
        document.input().forget(node);
        document.focus().forget(node);
        document.animation().forget(node);
        for (UINode at : node.composedSubtree()) at.setFrozen(true);
        // The structure changed as far as the box tree is concerned: a frozen subtree has no boxes.
        node.markStructureChanged();
        for (UINode at : node.composedSubtree()) at.fireFrozen();
    }

    /** Brings a frozen subtree back. Its boxes are rebuilt on the next pass. */
    public void thaw(UINode node) {
        if (!node.isFrozen()) return;
        for (UINode at : node.composedSubtree()) at.setFrozen(false);
        node.markStructureChanged();
        for (UINode at : node.composedSubtree()) {
            at.invalidateStyleMatch();
            at.fireThawed();
        }
    }

    /**
     * Takes a subtree out of the tree for good: every service forgets it and it is disconnected.
     *
     * <p>Deliberately not a "close": whether something MAY be destroyed is a policy question its
     * owner answers, and this is what happens once that has been decided.</p>
     */
    public void destroy(UINode node) {
        document.input().forget(node);
        document.focus().forget(node);
        document.animation().forget(node);
        node.removeSelf();
    }
}
