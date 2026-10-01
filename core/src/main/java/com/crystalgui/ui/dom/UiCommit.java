package com.crystalgui.ui.dom;

import com.crystalgui.render.UiFrame;

import javax.annotation.Nullable;

/**
 * One frame a document on a sequence hands the render thread: what to draw, and the facts its host reads back without
 * touching the tree. Immutable; built at the end of the document's frame.
 *
 * <ul>
 *   <li>{@code frame} is null when the frame painted nothing.</li>
 *   <li>{@code facts} is whatever the {@link DocumentDriver.Painter} answered for this frame.</li>
 * </ul>
 */
record UiCommit<F>(@Nullable UiFrame frame, @Nullable F facts, long index) {
}
