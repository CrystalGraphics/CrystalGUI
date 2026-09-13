package com.crystalgui.widget.surface;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * Pans the plane while a drag holds the pointer near the viewport's edge, so a target below the fold can be
 * reached without letting go.
 *
 * <pre>{@code
 * EdgePan pan = new EdgePan(surface);
 * pan.start(() -> resolveDropAgain());   // on the drag's first tick
 * pan.pointerAt(rawX, rawY);             // every update
 * pan.stop();                            // however the drag ended
 * }</pre>
 *
 * <p>GrapesJS's {@code AutoScroller} (BSD-3-Clause), on both axes: every frame, how far the pointer is into
 * the {@link #BAND} is how far the plane moves, so it accelerates the closer the pointer gets. Scaled by the
 * host's frame delta, so the speed does not depend on the frame rate, and capped at the band's width for a
 * pointer the drag has carried off the viewport.</p>
 *
 * <ul>
 *   <li>{@code onPanned} runs after each frame that moved the plane: what is under a still pointer changed.</li>
 *   <li>Always {@link #stop} it — the per-frame hook otherwise runs until the surface leaves the tree.</li>
 * </ul>
 */
public final class EdgePan {

    /** How far from the viewport's edge panning starts, in the viewport's logical pixels. */
    public static final float BAND = 24f;

    private final Surface surface;

    @Nullable
    private Runnable onPanned;

    private float rawX;
    private float rawY;
    private boolean hasPointer;

    /** Bumped by every start and stop, so a hook from an earlier drag ends itself. */
    private int generation;

    public EdgePan(Surface surface) {
        this.surface = surface;
    }

    /** Starts panning with the pointer; {@code onPanned} runs after each frame that moved the plane. */
    public void start(@Nullable Runnable onPanned) {
        stop();
        this.onPanned = onPanned;
        UIElement viewport = surface.element();
        UIDocument window = viewport == null ? null : viewport.document();
        if (window == null) return;
        int mine = ++generation;
        window.animation().every(viewport, delta -> {
            if (mine != generation) return false;
            tick(delta);
            return true;
        });
    }

    /** Where the pointer is, in raw surface pixels. */
    public void pointerAt(float rawX, float rawY) {
        this.rawX = rawX;
        this.rawY = rawY;
        this.hasPointer = true;
    }

    public void stop() {
        generation++;
        hasPointer = false;
        onPanned = null;
    }

    private void tick(float deltaSeconds) {
        UIElement viewport = surface.element();
        Box box = viewport == null ? null : viewport.box();
        if (!hasPointer || box == null) return;
        Vector2f local = viewport.toLocal(rawX, rawY);
        float[] step = step(local.x, local.y, box.width(), box.height(), BAND);
        float frames = Math.max(0f, deltaSeconds) * 60f;
        if ((step[0] == 0f && step[1] == 0f) || frames == 0f) return;
        surface.panBy(step[0] * frames, step[1] * frames);
        if (onPanned != null) onPanned.run();
    }

    /**
     * The pan for one 60 Hz frame with the pointer at a viewport-local point — the depth into the band on
     * each side, as a pan: positive where the band is on the left or top, since revealing what is there
     * moves the plane right or down.
     */
    public static float[] step(float x, float y, float width, float height, float band) {
        return new float[] {axis(x, width, band), axis(y, height, band)};
    }

    private static float axis(float at, float extent, float band) {
        if (extent <= band * 2f) return 0f;
        if (at < band) return Math.min(band - at, band);
        if (at > extent - band) return -Math.min(at - (extent - band), band);
        return 0f;
    }
}
