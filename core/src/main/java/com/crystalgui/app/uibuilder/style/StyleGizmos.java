package com.crystalgui.app.uibuilder.style;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.joml.Vector2f;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.service.Drag;

/**
 * The two gestures every lab is built out of: dragging a value, and pointing at an angle.
 *
 * <pre>{@code
 * StyleGizmos.drag(handle, (dx, dy) -> move(dx, dy), () -> commit());   // pixels, from the press
 * StyleGizmos.aim(dial, degrees -> setAngle(degrees));                  // where the pointer is
 * }</pre>
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
     * @param move    the movement so far, in local pixels from the press — called every frame of the drag
     * @param release once, when it ends: where a lab writes the value it was previewing
     */
    public static void drag(UIElement handle, BiConsumer<Float, Float> move, Runnable release) {
        handle.addClass(HANDLE_CLASS);
        // A gesture needs the press, and a plain element is scenery with hit-testing off by default.
        handle.setHitTest(true);
        handle.onMouseDown.attachListener((element, event) -> {
            if (!(event instanceof MouseEvent.Down down)) return;
            float perUnit = Drag.pixelsPerLocalUnit(handle);
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
     * @param aimed called with the angle as the pointer moves, and once more when it is let go
     */
    public static void aim(UIElement dial, Consumer<Float> aimed, Runnable release) {
        dial.addClass(HANDLE_CLASS);
        dial.setHitTest(true);
        dial.onMouseDown.attachListener((element, event) -> {
            if (!(event instanceof MouseEvent.Down down)) return;
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
