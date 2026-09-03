package com.crystalgui.ui.dom;

import com.crystalgui.style.property.visual.Resize;

import javax.annotation.Nullable;

/**
 * The seam that makes CSS {@code resize} ambient — a node grows grab handles because a stylesheet
 * says so, not because it was constructed as a resizable kind.
 *
 * <h3>Why this is a seam and not a method</h3>
 *
 * <p>{@code resize} is a CSS property that applies to elements generally, exactly as {@code overflow}
 * makes any element a scroll container — the web has no resizable-element interface, and neither did
 * the old engine, whose {@code onResizeModeChanged} javadoc said so in as many words: <em>"ambient on
 * every element, driven by the cascade ... not a widget you opt into by construction"</em>. That was
 * lost in the port, where a handle set became something a widget asked for; the eight shipped
 * {@code resize} declarations then meant nothing on their own, and the five widgets that had handles
 * were the five that remembered to call for them.</p>
 *
 * <p>The handles themselves cannot live here. {@code Resizer} is a widget and this package is the
 * engine, so the dependency only points one way — the engine states WHEN, and the widget layer
 * supplies WHAT. {@code Widgets} installs the one implementation at bootstrap, the same shape a
 * platform service takes.</p>
 *
 * <p>A tree with no installer is not broken, it is headless: a server builds nodes, resolves
 * {@code resize} like any other property, and has nothing to grab them with.</p>
 */
public final class ResizeHandles {

    /**
     * Attaches one handle as the node's own structure. @see UIElement#appendAmbient
     *
     * <p>Here rather than on {@code Resizer} because only this package can reach the structural
     * insert, and this class is already the engine's half of the arrangement: it states when handles
     * exist, so it can also state that they are the engine's and not a caller's.</p>
     */
    public static void attach(UIElement target, UIElement handle) {
        target.appendAmbient(handle);
    }

    /** What the widget layer supplies. @see #setInstaller */
    @FunctionalInterface
    public interface Installer {
        /**
         * Brings {@code node}'s handles into line with {@code mode}.
         *
         * <p>Called on every change of the computed value and must be idempotent: the cascade
         * re-resolves a property whenever anything about an element's match changes, so this sees
         * {@code BOTH} many times over for a node that already has its handles.</p>
         */
        void apply(UIElement node, Resize mode);
    }

    @Nullable
    private static Installer installer;

    private ResizeHandles() {
    }

    /** Installs the one implementation. @see Installer */
    public static void setInstaller(@Nullable Installer newInstaller) {
        installer = newInstaller;
    }

    /**
     * Applies {@code mode} to {@code node}, or does nothing when no widget layer is present.
     *
     * <p>{@code null} is what a listener is handed for a property nothing has written at any origin —
     * the initial value is not a candidate — so it means {@code none} here, which is the initial.</p>
     */
    public static void apply(UIElement node, @Nullable Resize mode) {
        Installer current = installer;
        if (current != null) current.apply(node, mode == null ? Resize.NONE : mode);
    }
}
