package com.crystalgui.desktop.host;

import com.crystalgui.render.UiFrame;

import javax.annotation.Nullable;

/**
 * One frame a document on a sequence hands the render thread: what to draw, and the facts about the desktop a host asks
 * synchronously and must not read off the tree. Immutable; built at the end of the document's frame.
 *
 * <ul>
 *   <li>{@code frame} is null when the presentation painted nothing.</li>
 *   <li>{@code attached} and {@code pinned} are what {@code Desktop.presentation} reads besides the host's own screens.</li>
 *   <li>{@code textInputArea} is the focus owner's caret area in surface pixels, or null.</li>
 * </ul>
 */
public record UiCommit(@Nullable UiFrame frame, boolean attached, boolean pinned, @Nullable float[] textInputArea,
                       long index) {
}
