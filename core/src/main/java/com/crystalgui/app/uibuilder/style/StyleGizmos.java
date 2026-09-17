package com.crystalgui.app.uibuilder.style;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.joml.Vector2f;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;

/**
 * The two gestures a lab's gizmo is built out of: dragging a value, and pointing at an angle.
 *
 * <pre>{@code
 * StyleGizmos.drag(handle, () -> from = getValue(), (dx, dy) -> commit(from + dx), this::endInteraction);
 * StyleGizmos.aim(dial, this::beginInteraction, degrees -> commit(degrees), this::endInteraction);
 * }</pre>
 *
 * <p>A gizmo is a {@code ValueControl}, and these are what it calls from its constructor: the press reads
 * the value the drag starts from, every frame commits, and the release closes the undo step.</p>
 *
 * <p>Both are in the handle's <b>local</b> pixels, the space a gizmo draws in: a dot, a stop or a puck moved by
 * them stays under the pointer at any {@code uiScale} or zoom. Converted to physical pixels, as a field's scrub
 * rate is, they ran twice as far as the pointer at a scale of 2.</p>
 */
public final class StyleGizmos {

    public static final String HANDLE_CLASS = "__gizmo-handle__";

    private StyleGizmos() {
    }

    /**
     * Makes {@code handle} draggable.
     *
     * @param press   once, on the press: where the gesture reads the value it moves from
     * @param move    the movement so far, in local pixels from the press — called every frame of the drag
     * @param release once, when it ends or is cancelled
     */
    public static void drag(UIElement handle, Runnable press, BiConsumer<Float, Float> move, Runnable release) {
        handle.addClass(HANDLE_CLASS);
        // A gesture needs the press, and a plain element is scenery with hit-testing off by default.
        handle.setHitTest(true);
        handle.onMouseDown.attachListener((element, event) -> {
            // THE PRIMARY BUTTON ONLY: a right press on a gizmo is its reset, never a drag.
            if (!(event instanceof MouseEvent.Down down) || down.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            press.run();
            Drag.start(handle, down.getPosition().x(), down.getPosition().y(), new Drag.Listener() {
                @Override
                public void onDragUpdate(float x, float y, float startX, float startY, float dx, float dy) {
                    move.accept(dx, dy);
                }

                @Override
                public void onDragEnd(float x, float y) {
                    release.run();
                }

                @Override
                public void onDragCancel() {
                    release.run();
                }
            });
            event.preventDefault();
        }, false, true);
    }

    /**
     * Makes {@code dial} point at the pointer: the angle from its centre, in degrees clockwise from up,
     * which is CSS's own reading of a gradient's angle.
     *
     * @param press once, on the press, before the first angle
     * @param aimed called with the angle on the press and as the pointer moves
     */
    public static void aim(UIElement dial, Runnable press, Consumer<Float> aimed, Runnable release) {
        dial.addClass(HANDLE_CLASS);
        dial.setHitTest(true);
        dial.onMouseDown.attachListener((element, event) -> {
            if (!(event instanceof MouseEvent.Down down) || down.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            press.run();
            aimed.accept(angleAt(dial, down.getPosition().x(), down.getPosition().y()));
            Drag.start(dial, down.getPosition().x(), down.getPosition().y(), new Drag.Listener() {
                @Override
                public void onDragUpdate(float x, float y, float startX, float startY, float dx, float dy) {
                    // IN THE DIAL'S OWN SPACE already: a drag reports local coordinates, and converting them again as
                    // surface ones aimed at a point nowhere near the pointer.
                    aimed.accept(angleOf(dial, x, y));
                }

                @Override
                public void onDragEnd(float x, float y) {
                    release.run();
                }

                @Override
                public void onDragCancel() {
                    release.run();
                }
            });
            event.preventDefault();
        }, false, true);
    }

    /** Degrees clockwise from up, of the pointer about {@code element}'s centre. */
    public static float angleAt(UIElement element, float surfaceX, float surfaceY) {
        Vector2f local = element.toLocal(surfaceX, surfaceY);
        return angleOf(element, local.x, local.y);
    }

    /** As {@link #angleAt}, for a point already in {@code element}'s own space. */
    private static float angleOf(UIElement element, float localX, float localY) {
        float width = element.box() == null ? 1f : element.box().width();
        float height = element.box() == null ? 1f : element.box().height();
        float dx = localX - width / 2f;
        float dy = localY - height / 2f;
        // atan2 measured from UP and growing clockwise: y grows downward here, so the sign works out
        // without a flip -- the same convention CgUiGradient reads an angle in.
        float degrees = (float) Math.toDegrees(Math.atan2(dx, -dy));
        return degrees < 0f ? degrees + 360f : degrees;
    }

    /** How far along {@code element} a pointer is, 0 at its left edge and 1 at its right. */
    public static float fractionAt(UIElement element, float surfaceX, float surfaceY) {
        Vector2f local = element.toLocal(surfaceX, surfaceY);
        float width = element.box() == null ? 1f : Math.max(1f, element.box().width());
        return Math.max(0f, Math.min(1f, local.x / width));
    }
}
