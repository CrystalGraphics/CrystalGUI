package com.crystalgui.ui;

/**
 * Watches one tree for the changes a networked session has to relay.
 *
 * <h3>Why this rather than {@code onStyleChanged()}</h3>
 * <p>{@link UIElement#onStyleChanged()} is an existing no-op hook on the cascade's write path, and it
 * looks like the obvious seam. It is the wrong one twice over: it fires on every transition tick and
 * on every {@code IMPORTANT}-origin write a widget makes about itself (a {@code Tab} toggling its
 * pane's {@code display}, {@code Slider} pushing its fill weight), so the dirty set would be
 * dominated by churn; and what it reports is <em>computed styles</em>, which a session never sends —
 * stylesheets travel by reference and widget internals are re-derived client-side.</p>
 *
 * <p>The four callbacks below correspond to the only things that genuinely cannot be reconstructed on
 * the far side: the shape of the tree, an element's identity, and a widget's authored state.</p>
 *
 * <h3>Cost when nobody is watching</h3>
 * <p>One nullable field per element and one null check per mutation. A purely client-side GUI — which
 * is most of them — pays nothing else. That is why the observer lives here rather than the session
 * owning a set of listeners keyed by element, and why there is no per-element {@code Signal}.</p>
 *
 * <p>Single-threaded, like everything else in this engine. Implementations must not block.</p>
 */
public interface UITreeObserver {

    /** {@code element} joined the observed tree. Fires for the whole subtree, parents first. */
    void onAttached(UIElement element);

    /** {@code element} left the observed tree. Fires before the parent link is cleared. */
    void onDetached(UIElement element);

    /**
     * A widget's serializable state changed — re-read it with
     * {@link UIElement#writeStateTo} when flushing.
     *
     * <p>Deliberately carries no value. State is re-read at flush time rather than captured here, so
     * ten mutations in one tick collapse into one entry holding the final value instead of ten
     * entries describing a journey nobody needs.</p>
     */
    void onStateDirty(UIElement element);

    /** Id, classes, enabled or focus policy changed — things the far side's cascade depends on. */
    void onIdentityDirty(UIElement element);
}
