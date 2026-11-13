package com.crystalgui.desktop.window;

/**
 * Where a window was and how big it was, in logical pixels: what a session records and a restore asks for.
 *
 * <p>The position is where the window was last asked to be ({@link WindowFrame#getWantedLeft}), so saving it and
 * restoring on a smaller desktop does not write the clamp into the record. Never a zero size.</p>
 */
public record WindowBounds(float left, float top, float width, float height) {
}
