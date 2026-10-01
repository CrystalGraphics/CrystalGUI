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
record UiCommit<F>(@Nullable UiFrame frame, @Nullable F facts, long index, @Nullable Follow follow) {

    /**
     * An element the frame recorded mid-gesture, moving one for one with the pointer: its spatial node in this frame and
     * where the pointer was, in surface pixels, when the document recorded it, and how far it may move from there before
     * an edge stops it. What Input.followPointer declared.
     */
    record Follow(int node, float pointerX, float pointerY, float minX, float maxX, float minY, float maxY) {

        /** No edge. */
        static final float FREE = Float.POSITIVE_INFINITY;
    }
}
