package com.crystalgui.core.input;

import com.crystalgui.style.property.visual.Cursor;

/**
 * Loader-blind seam for showing a mouse cursor, matching the registration shape of
 * {@link CgUiInputAdapter}, {@link UIClipboard} and {@code UISoundSystem}
 * ({@link com.crystalgui.core.CrystalGuiCore#getCursorService()} / {@code setCursorService}).
 *
 * <p>The engine resolves <em>which</em> cursor an element wants — the {@code cursor} CSS property plus
 * its inheritance and the {@code auto} context rule — and hands the answer here. Actually presenting
 * one is loader-specific to an awkward degree, which is exactly why it is a seam:</p>
 *
 * <ul>
 *   <li><b>LWJGL3 / GLFW</b> (MC 1.20.x) has standard system cursors, including the full resize set.
 *       A mapping table is the whole implementation.</li>
 *   <li><b>LWJGL2</b> (MC 1.7.10, and the harness) has <b>no standard cursors at all</b> —
 *       {@code Mouse.setNativeCursor} takes a {@code Cursor} built from raw pixel data. So that
 *       platform needs bitmaps, or has to draw its own indicator instead.</li>
 * </ul>
 *
 * <p>{@link #NOOP} is the default so nothing has to register anything for the property to cascade and
 * resolve correctly — an unimplemented cursor is a cosmetic gap, never a functional one.</p>
 *
 * <p><b>Called only on change</b>, not per frame: the input handler tracks the last cursor it asked
 * for and stays quiet while the pointer sits still. Implementations may therefore do real work here
 * (creating a native cursor object) without needing their own caching, though caching by
 * {@link Cursor} is still wise since a user waving the pointer across a UI will cycle through a
 * handful repeatedly.</p>
 */
public interface UICursorService {

    /**
     * Shows {@code cursor}.
     *
     * <p>Never receives {@link Cursor#AUTO} — the handler resolves that to a concrete value first, so
     * an implementation only ever sees something it can map or ignore.</p>
     */
    void setCursor(Cursor cursor);

    UICursorService NOOP = cursor -> {};
}
