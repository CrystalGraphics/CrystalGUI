package com.crystalgui.ui.service;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * <b>How a thing on top goes away</b> — Escape, and a press outside.
 *
 * <p>Two stacks, and they are not redundant. {@link #pushCloseWatcher} drives <b>Escape</b>: the web's
 * {@code CloseWatcher} as a general primitive, so a modal dialog, a window frame and a mode can each
 * register one. {@link #pushAutoPopover} drives <b>light dismiss</b>: a press outside closes the chain
 * from the top down. The same node is routinely in one and not the other — a modal dialog has a close
 * watcher and is not light-dismissable, and a {@code MANUAL} popover is in neither — so collapsing
 * them makes one of those two cases wrong.</p>
 *
 * <h3>Escape is a cascade, and the scope is a rung of it</h3>
 *
 * <p>{@link #topCloseWatcher} asks the ACTIVE scope's watchers first and only then the document's.
 * A dropdown opened inside a window closes before that window's modal, which closes before the window
 * itself — a frame registers as its own last watcher — and only once a window has nothing left to
 * close does Escape reach anything registered outside one. Without the scoping, one global stack means
 * Escape closes whatever was opened LAST anywhere, so a dialog left open in a background window
 * swallows the Escape aimed at the window in front.</p>
 *
 * <p>A live {@link InputMode} still eats Escape before any of this, because a drag or a window switch
 * is the innermost live interaction. That is the mode stack's business and needs nothing here.</p>
 *
 * <h3>The service names no widget</h3>
 *
 * <p>{@code Popover}, {@code Dialog} and {@code WindowFrame} are M6 and this is 6.0, so what a node
 * DOES when asked to close is {@link UINode#requestClose()} — a hook, exactly as the old engine had
 * it, because the web's {@code CloseWatcher} is a general primitive rather than a dialog feature.</p>
 */
public final class Dismiss {

    private final UIDocument document;

    /** Escape's stack, bottom-most first. */
    private final List<UINode> closeWatchers = new ArrayList<>();
    /** Light dismiss's stack, bottom-most first. */
    private final List<UINode> autoPopovers = new ArrayList<>();

    /**
     * Bumped by every show, INCLUDING a re-show of something already open.
     *
     * @see #lightDismiss(UINode, int)
     */
    private int showSeq;

    public Dismiss(UIDocument document) {
        this.document = document;
    }

    // ── Close watchers: Escape ───────────────────────────────────────────────

    /**
     * Registers {@code node} to receive Escape, ahead of anything registered before it.
     *
     * <p>Idempotent, and re-registering RAISES — which is what reopening a popup should do, and what
     * makes "show me again" one call rather than a remove/add dance the caller has to get right.</p>
     */
    public void pushCloseWatcher(UINode node) {
        Objects.requireNonNull(node, "node");
        closeWatchers.remove(node);
        closeWatchers.add(node);
    }

    public void popCloseWatcher(UINode node) {
        closeWatchers.remove(node);
    }

    /** The stack, bottom-most first. Read-only. */
    public List<UINode> closeWatchers() {
        return Collections.unmodifiableList(closeWatchers);
    }

    /**
     * What Escape should ask, or null.
     *
     * @param activeScope the scope the keyboard is in — a window frame, a dialog. Its watchers are
     *                    asked first; null asks only the document's own.
     */
    @Nullable
    public UINode topCloseWatcher(@Nullable UINode activeScope) {
        if (activeScope != null) {
            UINode scoped = topWatcherIn(activeScope);
            if (scoped != null) return scoped;
        }
        return topWatcherIn(null);
    }

    /** The topmost watcher whose own scope is {@code scope}. */
    @Nullable
    private UINode topWatcherIn(@Nullable UINode scope) {
        Focus focus = document.focus();
        for (int i = closeWatchers.size() - 1; i >= 0; i--) {
            UINode watcher = closeWatchers.get(i);
            // A watcher's scope is the one CONTAINING it, never itself: a dialog is a scope, and
            // asking `scopeOf(dialog)` would answer the dialog -- so a dialog's own Escape would
            // never be found from the window it is in. Same shape as the modality bug 5.5 found.
            UINode enclosing = focus.scopeOf(watcher.parent() == null ? watcher : watcher.parent());
            if (enclosing == scope) return watcher;
        }
        return null;
    }

    /**
     * Offers Escape to the top of the cascade. Returns whether anything took it.
     *
     * @param activeScope @see #topCloseWatcher
     */
    public boolean escape(@Nullable UINode activeScope) {
        UINode watcher = topCloseWatcher(activeScope);
        return watcher != null && watcher.requestClose();
    }

    // ── Light dismiss: a press outside ───────────────────────────────────────

    /** Open auto popovers, bottom-most first. Read-only. */
    public List<UINode> autoPopovers() {
        return Collections.unmodifiableList(autoPopovers);
    }

    public void pushAutoPopover(UINode node) {
        Objects.requireNonNull(node, "node");
        autoPopovers.remove(node);
        autoPopovers.add(node);
    }

    public void popAutoPopover(UINode node) {
        autoPopovers.remove(node);
    }

    /** The current show sequence. Capture BEFORE dispatching a press; hand to {@link #lightDismiss}. */
    public int showSeq() {
        return showSeq;
    }

    /** Bumped by a show, whether or not the popover was already open. */
    public int nextShowSeq() {
        return ++showSeq;
    }

    /**
     * The spec's light dismiss: a press on {@code target} closes every open auto popover that
     * {@code target} is not inside.
     *
     * <p>Ported from HTML's "light dismiss open popovers" — find the target's innermost popover
     * ancestor, then close everything above it. So a press INSIDE a menu closes its submenus and not
     * itself, and a press anywhere unrelated closes the whole chain. Any other rule gives either a
     * submenu that cannot be dismissed without killing its parent, or a parent that dies when you
     * reach for its child.</p>
     *
     * <p><b>Run this AFTER the press has been dispatched</b>, never before: dismissing first tears
     * down the tree under an undelivered event. Browsers both dismiss and activate.</p>
     */
    public void lightDismiss(@Nullable UINode target) {
        lightDismiss(target, Integer.MAX_VALUE);
    }

    /**
     * As above, sparing anything shown after {@code shownBefore} — the value {@link #showSeq()}
     * returned before the press was dispatched.
     *
     * <p><b>This is what stops a popover dismissing itself.</b> Light dismiss runs after the press is
     * delivered, so a handler that opens a context menu on press has already put it in the stack by
     * the time dismissal runs — and the pressed node is not inside it, so the naive algorithm closes
     * the menu on the very press that asked for it. It opens and vanishes in the same frame, which
     * from outside is indistinguishable from never opening at all.</p>
     *
     * <p>A COUNTER rather than a snapshot of the stack, and the difference is a real case: a menu
     * that is already open and gets re-shown at a new position by the press is in any before-snapshot,
     * so a membership test dismisses it — right-clicking elsewhere would close the menu instead of
     * moving it. "Was this shown during the press" answers both with one rule.</p>
     */
    public void lightDismiss(@Nullable UINode target, int shownBefore) {
        if (autoPopovers.isEmpty()) return;
        UINode ancestor = innermostPopoverAncestor(target);
        // Copy and walk downwards: closing mutates the live list, and requestClose() runs listener
        // code that may open or close further popovers.
        List<UINode> doomed = new ArrayList<>();
        for (int i = autoPopovers.size() - 1; i >= 0; i--) {
            UINode popover = autoPopovers.get(i);
            if (popover == ancestor) break;
            if (shownAt.getOrDefault(popover, 0) > shownBefore) continue;
            doomed.add(popover);
        }
        for (UINode popover : doomed) popover.requestClose();
    }

    /** When each open popover was last shown. @see #lightDismiss(UINode, int) */
    private final java.util.Map<UINode, Integer> shownAt = new java.util.IdentityHashMap<>();

    /** Records that {@code popover} was shown now, and returns the sequence it was shown at. */
    public int recordShown(UINode popover) {
        int seq = nextShowSeq();
        shownAt.put(popover, seq);
        return seq;
    }

    /** Forgets a popover that has closed. */
    public void forget(UINode node) {
        closeWatchers.remove(node);
        autoPopovers.remove(node);
        shownAt.remove(node);
    }

    /**
     * The innermost open auto popover {@code target} belongs to, or null.
     *
     * <p><b>The invoker counts as part of its popover</b>, and without that carve-out a dropdown
     * button dies on its own press: light dismiss closes the menu on mouse-down and the button's
     * click reopens it, so it can never be shut by pressing the button again — and flickers while
     * trying.</p>
     */
    @Nullable
    private UINode innermostPopoverAncestor(@Nullable UINode target) {
        for (UINode node = target; node != null; node = node.composedParent()) {
            if (autoPopovers.contains(node)) return node;
            for (int i = autoPopovers.size() - 1; i >= 0; i--) {
                UINode popover = autoPopovers.get(i);
                if (popover.popoverInvoker() == node) return popover;
            }
        }
        return null;
    }
}
