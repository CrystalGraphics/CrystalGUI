package com.crystalgui.viewport;

import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.render.UiFrame;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.CompositorAnimation;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What moves between a sequenced document's frames, on the render thread: an element following the pointer, and the
 * compositor's animations (a window opening, minimising), each written into the presented frame's property values.
 * Chromium's compositor-thread animations and scroll: the document records slowly, the picture still moves.
 *
 * <ul>
 *   <li>{@link #follow} and {@link #motions} run on the document as a frame is committed; everything else on the render
 *       thread.</li>
 *   <li>A flight that reveals a window waits for the frame carrying it to present and settle (Chrome's paint holding),
 *       so the window's first execution's stall never lands inside the flight.</li>
 * </ul>
 */
final class CompositorMotion {

    /** {@code -Dcrystalgui.ui.compositorMotion=false}: nothing moves between the document's frames. */
    static final boolean ENABLED = !"false".equals(System.getProperty("crystalgui.ui.compositorMotion"));

    /** A gap between presents within two frames at 60 Hz: the pipeline is holding its rate. */
    static final long CALM_NANOS = 34_000_000L;
    /** Calm presents in a row before a waiting flight starts. */
    static final int CALM_GAPS = 3;
    /** The longest a flight waits to start, as Chrome's paint holding times out. */
    static final long REVEAL_TIMEOUT_NANOS = 500_000_000L;

    /** The pointer as the render thread last saw it, in surface pixels. */
    private volatile float pointerX, pointerY;
    /** The commit {@link #followPointer} last moved, and by how much, so an unchanged offset is not drawn again. */
    @Nullable
    private UiCommit moved;
    private int movedX, movedY;

    /** The commit {@link #animate} last drew finished, so a settled flight is shown again rather than redrawn. */
    @Nullable
    private UiCommit settled;
    private long lastPresentNanos;
    /** When a flight first waited to start, 0 when none waits; and the calm gaps since, in a row. */
    private long waitingSince;
    private int calmGaps;
    private final Matrix4f motionAt = new Matrix4f(), motionNode = new Matrix4f();

    /** Where the pointer is now, from the event the host just offered. Render thread. */
    void pointerAt(float x, float y) {
        pointerX = x;
        pointerY = y;
    }

    /** Writes {@code commit}'s motion for this host frame; true when it changed anything. Render thread. */
    boolean move(UiCommit commit) {
        return followPointer(commit) | animate(commit);
    }

    /** A host frame was presented now. Render thread. */
    void presented(long nanos) {
        lastPresentNanos = nanos;
    }

    // ── On the document, at commit ──────────────────────────────────────────────────────────────

    /** What follows the pointer in {@code frame}, if anything and it has a node there to move. */
    @Nullable
    static UiCommit.Follow follow(UIDocument document, @Nullable UiFrame frame) {
        UIElement element = frame == null ? null : document.input().pointerFollower();
        Box box = element == null ? null : element.box();
        int node = box == null ? 0 : box.movedNode(frame.frameId());
        if (node == 0) return null;
        ReadOnlyVec2f pointer = document.input().pointer();
        UIElement within = document.input().pointerFollowerWithin();
        Box bounds = within == null ? null : within.box();
        if (bounds == null) {
            float free = UiCommit.Follow.FREE;
            return new UiCommit.Follow(node, pointer.x(), pointer.y(), -free, free, -free, free);
        }
        // World rectangles, axis-aligned: what a clamp on left/top keeps inside its container.
        float x0 = box.worldX(), y0 = box.worldY();
        float x1 = x0 + box.width() * box.localToWorld().m00(), y1 = y0 + box.height() * box.localToWorld().m11();
        float bx0 = bounds.worldX(), by0 = bounds.worldY();
        float bx1 = bx0 + bounds.width() * bounds.localToWorld().m00();
        float by1 = by0 + bounds.height() * bounds.localToWorld().m11();
        float minX = Math.min(0f, bx0 - x0), minY = Math.min(0f, by0 - y0);
        return new UiCommit.Follow(node, pointer.x(), pointer.y(),
                minX, Math.max(minX, bx1 - x1), minY, Math.max(minY, by1 - y1));
    }

    /** What the compositor plays in {@code frame}: every animation handed to it, with its box's node if it has one. */
    static List<UiCommit.Motion> motions(UIDocument document, @Nullable UiFrame frame) {
        List<CompositorAnimation> playing = document.animation().onCompositor();
        if (frame == null || playing.isEmpty()) return List.of();
        List<UiCommit.Motion> motions = new ArrayList<>(playing.size());
        for (CompositorAnimation animation : playing) {
            Box box = animation.target().box();
            int moved = box == null ? 0 : box.movedNode(frame.frameId());
            if (moved == 0) {
                motions.add(new UiCommit.Motion(animation, 0, 0, null, null, null, 0f, 0f, 0f, 0f, 0f, 0f));
                continue;
            }
            float width = box.width(), height = box.height();
            float originX = animation.originX(width), originY = animation.originY(height);
            Matrix4f recorded = box.transform().applyTo(new Matrix4f(), 0f, 0f, width, height,
                    box.transformOriginX() != null ? box.transformOriginX() : originX,
                    box.transformOriginY() != null ? box.transformOriginY() : originY);
            Matrix4f recordedInverse = recorded.invert(new Matrix4f());
            Matrix4f world = box.movedWorld();
            Matrix4f place = new Matrix4f(world).mul(recordedInverse);
            motions.add(new UiCommit.Motion(animation, moved, box.fadedNode(frame.frameId()), place,
                    place.invert(new Matrix4f()), recordedInverse, width, height, originX, originY,
                    // AT REST: the node is the box's corner without its transform. @see Box#restToWorld
                    Math.round(place.m30()), Math.round(place.m31())));
        }
        return motions;
    }

    // ── On the render thread ────────────────────────────────────────────────────────────────────

    /**
     * Moves what the commit recorded following the pointer by the pointer's travel since: a window dragged across a busy
     * document stays under the hand. Whole device pixels, as a spatial node is placed.
     */
    private boolean followPointer(UiCommit commit) {
        UiCommit.Follow follow = commit.follow();
        if (follow == null || commit.frame() == null) return false;
        int dx = Math.round(clamp(pointerX - follow.pointerX(), follow.minX(), follow.maxX()));
        int dy = Math.round(clamp(pointerY - follow.pointerY(), follow.minY(), follow.maxY()));
        if (commit == moved && dx == movedX && dy == movedY) return false;
        commit.frame().values().translate(follow.node(), dx, dy);
        moved = commit;
        movedX = dx;
        movedY = dy;
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Plays the commit's compositor animations at this host frame: each box's node takes the animation's transform
     * now, and its effect node the opacity. A flight waiting to start holds its start value (an opening window unseen)
     * until the frame carrying it has been presented and {@value #CALM_GAPS} calm presents followed, or
     * {@link #REVEAL_TIMEOUT_NANOS} passed.
     */
    private boolean animate(UiCommit commit) {
        List<UiCommit.Motion> motions = commit.motions();
        if (motions.isEmpty() || commit.frame() == null || commit == settled) return false;
        long now = System.nanoTime();
        boolean start = readyToStart(motions, now);
        boolean finished = true;
        for (int i = 0; i < motions.size(); i++) {
            UiCommit.Motion motion = motions.get(i);
            CompositorAnimation animation = motion.animation();
            if (start || !reveals(animation, now)) animation.startAt(now);
            finished &= animation.isFinished(now);
            if (motion.moved() == 0) continue;
            Transform at = animation.transformAt(now);
            at.applyTo(motionAt.identity(), 0f, 0f, motion.width(), motion.height(), motion.originX(), motion.originY());
            motionNode.set(motion.place()).mul(motionAt).mul(motion.recordedInverse()).mul(motion.placeInverse())
                    .translate(motion.cornerX(), motion.cornerY(), 0f);
            commit.frame().values().transform(motion.moved(), motionNode.m00(), motionNode.m01(), motionNode.m10(),
                    motionNode.m11(), motionNode.m30(), motionNode.m31());
            if (motion.faded() != 0) commit.frame().values().opacity(motion.faded(), animation.opacityAt(now));
        }
        if (finished) settled = commit;
        return true;
    }

    /** A flight that has not started and starts unseen: an open or a restore, which a window already shown never waits for. */
    private static boolean reveals(CompositorAnimation animation, long now) {
        return animation.startNanos() == 0L && animation.opacityAt(now) == 0f;
    }

    /** Whether the revealing flights in {@code motions} start now; see {@link #animate}. */
    private boolean readyToStart(List<UiCommit.Motion> motions, long now) {
        boolean waiting = false;
        for (int i = 0; i < motions.size() && !waiting; i++) waiting = reveals(motions.get(i).animation(), now);
        if (!waiting) {
            waitingSince = 0L;
            return false;
        }
        if (waitingSince == 0L) {
            // Its first present: what executing it costs lands in the next gap
            waitingSince = now;
            calmGaps = 0;
            return false;
        }
        calmGaps = now - lastPresentNanos <= CALM_NANOS ? calmGaps + 1 : 0;
        if (calmGaps < CALM_GAPS && now - waitingSince < REVEAL_TIMEOUT_NANOS) return false;
        waitingSince = 0L;
        return true;
    }
}
