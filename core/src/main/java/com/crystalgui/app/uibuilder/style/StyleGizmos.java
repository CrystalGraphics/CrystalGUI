package com.crystalgui.app.uibuilder.style;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.joml.Vector2f;

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
 * <p>Both are in <b>local</b> pixels, so a lab inside a zoomed canvas still moves a value by what the
 * pointer moved on screen — {@link Drag#pixelsPerLocalUnit} sampled once at the press, as the box model's
 * scrub does.</p>
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
            if (!(event instanceof MouseEvent.Down down)) return;
            float perUnit = Drag.pixelsPerLocalUnit(handle);
            press.run();
            Drag.start(handle, down.getPosition().x(), down.getPosition().y(), new Drag.Listener() {
                @Override
                public void onDragUpdate(float x, float y, float startX, float startY, float dx, float dy) {
                    move.accept(dx * perUnit, dy * perUnit);
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
            if (!(event instanceof MouseEvent.Down down)) return;
            press.run();
            aimed.accept(angleAt(dial, down.getPosition().x(), down.getPosition().y()));
            Drag.start(dial, down.getPosition().x(), down.getPosition().y(), new Drag.Listener() {
                @Override
                public void onDragUpdate(float x, float y, float startX, float startY, float dx, float dy) {
                    aimed.accept(angleAt(dial, x, y));
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
        float width = element.box() == null ? 1f : element.box().width();
        float height = element.box() == null ? 1f : element.box().height();
        float dx = local.x - width / 2f;
        float dy = local.y - height / 2f;
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
