package com.crystalgui.ui.dom;

import com.crystalgui.render.UiFrame;
import com.crystalgui.ui.service.CompositorAnimation;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One frame a document on a sequence hands the render thread: what to draw, and the facts its host reads back without
 * touching the tree. Immutable; built at the end of the document's frame.
 *
 * <ul>
 *   <li>{@code frame} is null when the frame painted nothing.</li>
 *   <li>{@code facts} is whatever the {@link DocumentDriver.Painter} answered for this frame.</li>
 * </ul>
 */
record UiCommit<F>(@Nullable UiFrame frame, @Nullable F facts, long index, @Nullable Follow follow,
                   List<Motion> motions) {

    /**
     * An element the frame recorded mid-gesture, moving one for one with the pointer: its spatial node in this frame and
     * where the pointer was, in surface pixels, when the document recorded it, and how far it may move from there before
     * an edge stops it. What Input.followPointer declared.
     */
    /**
     * An animation the compositor plays on one box of this frame. {@code moved} is 0 when the box drew under no node
     * here: the animation's clock still starts when this frame is presented, and nothing is moved.
     *
     * <p>The box's world in its node's parent space is {@code place × M(t)}, M the animation's transform about its
     * origin in box units; the frame recorded it at {@code M(recorded)}, so the node takes
     * {@code place × M(t) × recordedInverse × placeInverse} ahead of its recorded corner. Matrices are copies, read
     * on the render thread only.</p>
     */
    record Motion(CompositorAnimation animation, int moved, int faded, Matrix4f place, Matrix4f placeInverse,
                  Matrix4f recordedInverse, float width, float height, float originX, float originY,
                  float cornerX, float cornerY) {
    }

    record Follow(int node, float pointerX, float pointerY, float minX, float maxX, float minY, float maxY) {

        /** No edge. */
        static final float FREE = Float.POSITIVE_INFINITY;
    }
}
