package com.crystalgui.widget.surface.snap;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>The scene a laid-out box snaps within</b> — everything under its parent except itself, as DRAWN, in
 * the parent's own space.
 *
 * <pre>{@code
 * SnapScene scene = BoxTargets.sceneFor(node);
 * SnapScene scene = BoxTargets.sceneFor(node, artboard);   // and the page's edges, from any depth
 * }</pre>
 *
 * <ul>
 *   <li><b>The whole subtree, not the siblings</b>, as tldraw walks from the common ancestor: a button
 *       inside a header is something a panel beside the header can line up with.</li>
 *   <li><b>Gaps only between the direct siblings</b>, by their drawn bounds. @see SnapScene</li>
 *   <li><b>As drawn</b>: each box's corners go through its own {@code localToWorld} and back through the
 *       parent's {@code worldToLocal}, so an element carrying a {@code transform} is snapped where it is
 *       seen — what tldraw and Excalidraw snap to. Every transform and scroll in between is carried.</li>
 *   <li><b>The parent's own space</b> is the one its children are placed in: a child's {@code Box.x()}
 *       less the parent's scroll. A caller measuring its own box must use the same.</li>
 *   <li>A node with no box offers nothing, nor does anything under it.</li>
 * </ul>
 */
public final class BoxTargets {

    private BoxTargets() {
    }

    /** What {@code moving} can snap to. {@link SnapScene#EMPTY} when it has no laid-out parent. */
    public static SnapScene sceneFor(@Nullable UIElement moving) {
        return sceneFor(moving, null);
    }

    /**
     * As {@link #sceneFor(UIElement)}, plus the edges and centre of {@code frame} — the page the tree is
     * laid out on — when it is the parent or one of the parent's ancestors. Ignored otherwise.
     */
    public static SnapScene sceneFor(@Nullable UIElement moving, @Nullable UIElement frame) {
        UIElement parent = moving == null ? null : moving.parentElement();
        Box parentBox = parent == null ? null : parent.box();
        if (parentBox == null) return SnapScene.EMPTY;
        Matrix4f toParent = new Matrix4f(parentBox.worldToLocal());

        List<SnapScene.Outline> points = new ArrayList<>();
        List<SnapScene.Rect> peers = new ArrayList<>();
        for (UIElement child : parent.children()) {
            Box box = child == moving ? null : child.box();
            if (box == null) continue;
            SnapScene.Outline outline = outline(box, toParent);
            peers.add(outline.bounds());
            points.add(outline);
            descend(child, toParent, points);
        }

        // A child's position is measured from the border edge, so the content box starts past both.
        float left = parentBox.border().left + parentBox.padding().left;
        float top = parentBox.border().top + parentBox.padding().top;
        float right = parentBox.border().right + parentBox.padding().right;
        float bottom = parentBox.border().bottom + parentBox.padding().bottom;
        SnapScene.Rect content = new SnapScene.Rect(left, top,
                parentBox.width() - left - right, parentBox.height() - top - bottom);
        return SnapScene.ofOutlines(points, peers, content, frameIn(parent, frame, toParent));
    }

    /** The points alone, for a solver with no use for gaps. */
    public static SnapTargets around(@Nullable UIElement moving) {
        return sceneFor(moving);
    }

    /**
     * {@code node} as drawn, in {@code space}'s own coordinates — what a gesture measures its own box
     * with, so that it and the scene agree. Null when either has no box.
     *
     * <pre>{@code
     * SnapScene.Outline mine = BoxTargets.outlineIn(node, node.parentElement());
     * }</pre>
     */
    @Nullable
    public static SnapScene.Outline outlineIn(@Nullable UIElement node, @Nullable UIElement space) {
        Box box = node == null ? null : node.box();
        Box spaceBox = space == null ? null : space.box();
        if (box == null || spaceBox == null) return null;
        return outline(box, new Matrix4f(spaceBox.worldToLocal()));
    }

    private static void descend(UIElement node, Matrix4f toParent, List<SnapScene.Outline> into) {
        for (UIElement child : node.children()) {
            Box box = child.box();
            if (box == null) continue;
            into.add(outline(box, toParent));
            descend(child, toParent, into);
        }
    }

    /** The four corners in order round the box, then its centre, through its world matrix into a space. */
    private static SnapScene.Outline outline(Box box, Matrix4f toSpace) {
        Matrix4f m = new Matrix4f(toSpace).mul(box.localToWorld());
        float w = box.width();
        float h = box.height();
        float[] localX = {0f, w, w, 0f, w * 0.5f};
        float[] localY = {0f, 0f, h, h, h * 0.5f};
        float[] xs = new float[localX.length];
        float[] ys = new float[localX.length];
        for (int i = 0; i < localX.length; i++) {
            xs[i] = m.m00() * localX[i] + m.m10() * localY[i] + m.m30();
            ys[i] = m.m01() * localX[i] + m.m11() * localY[i] + m.m31();
        }
        return new SnapScene.Outline(xs, ys);
    }

    /** {@code frame}'s drawn bounds in the parent's space, when it is the parent or one of its ancestors. */
    @Nullable
    private static SnapScene.Rect frameIn(UIElement parent, @Nullable UIElement frame, Matrix4f toParent) {
        if (frame == null) return null;
        for (UIElement at = parent; at != null; at = at.parentElement()) {
            if (at != frame) continue;
            Box box = at.box();
            return box == null ? null : outline(box, toParent).bounds();
        }
        return null;
    }
}
