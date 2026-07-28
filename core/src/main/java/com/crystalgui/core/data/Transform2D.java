package com.crystalgui.core.data;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * Point transforms for the 2D UI, in plain JOML.
 *
 * <h3>Why this exists rather than CrystalGraphics' equivalent</h3>
 * <p>{@code CgVertexTransformUtil.transformPosition} does exactly this and is itself pure JOML — but
 * it lives in CrystalGraphics, which is {@code compileOnly} and therefore <b>absent at runtime on a
 * dedicated server</b>. It was reached from {@code UIElement.screenToLocal} and
 * {@code UIWindow.elementHitTest}: the only two non-painting methods in the whole engine that
 * touched a CrystalGraphics type, and so the only two that would have made a server crash the moment
 * it dispatched an event. Everything else CG-related sits inside paint method bodies, which the JVM
 * never resolves unless they run.</p>
 */
public final class Transform2D {

    /** Zero-allocation scratch, matching the CrystalGraphics util this replaces. */
    private static final ThreadLocal<Vector4f> SCRATCH = ThreadLocal.withInitial(Vector4f::new);

    private Transform2D() {
    }

    /** Transforms {@code (x, y, 0, 1)} by {@code matrix} and returns the xy as a fresh vector. */
    public static Vector2f apply(Matrix4f matrix, float x, float y) {
        Vector4f v = SCRATCH.get().set(x, y, 0f, 1f);
        matrix.transform(v);
        return new Vector2f(v.x(), v.y());
    }

    /** As {@link #apply}, into {@code out}, for callers that already have a vector to fill. */
    public static Vector2f apply(Matrix4f matrix, float x, float y, Vector2f out) {
        Vector4f v = SCRATCH.get().set(x, y, 0f, 1f);
        matrix.transform(v);
        return out.set(v.x(), v.y());
    }
}
