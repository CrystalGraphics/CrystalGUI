package com.crystalgui.desktop.app;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.service.Animation;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Where the shell shows an application's popups: in its top layer, above every window, exactly where the application
 * laid them out relative to the window it last took input in. Drawn and hit-tested where the application has them, so
 * nothing here moves them; the application keeps them inside its own window.
 *
 * <pre>{@code
 * appDocument.showPopupsIn(shell);    // by AppWindows, with the application's first window
 * }</pre>
 *
 * <ul>
 *   <li>The portal draws the picture and takes no pointer itself; one small child per popup does, so the space between
 *       two popups stays the shell's. A press anywhere else in the shell closes the popups.</li>
 *   <li>Pointer events over a popup go to the application's document in its own pixels. Keys reach it through the
 *       focused window, as any key does.</li>
 *   <li>A press on a popup leaves the focus where it was, on the application's window.</li>
 * </ul>
 */
final class PopupPortal extends UIElement {

    /** What the sheets address it by. */
    static final Name NAME = Name.of("popupportal");

    private final AppDocument appDocument;
    private final PopupLayer layer;
    private float left = Float.NaN, top, width = -1f, height = -1f;
    /** Where the popups' corner is, in the application's document: what a pointer over this is relative to. */
    private float originX, originY;
    /** One per popup shown, in the order of {@link PopupLayer#rectangles()}. */
    private final List<Slot> slots = new ArrayList<>();

    PopupPortal(AppDocument appDocument, PopupLayer layer) {
        super(NAME);
        this.appDocument = appDocument;
        this.layer = layer;
        layout(l -> l.width(0f).height(0f));
        setFocusPolicy(FocusPolicy.NONE);
        set(Attribute.HIT_TRANSPARENT, true);
        layer.onChanged(() -> {
            UIDocument shell = document();
            if (shell != null) shell.execute(this::repaint);
        });
        onConnected(() -> document().animation().everyIfAbsent(this, place));
        whileConnected(() -> document().onMouseDown.subscribe((element, event) -> {
            if (layer.occupied() != null && !within(event.getTarget() instanceof UIElement hit ? hit : null)) appDocument.dismissPopups();
        }, true, false));
    }

    /** What takes the pointer over one popup. */
    private final class Slot extends UIElement {
        private float left = Float.NaN, top, width, height;

        Slot() {
            // A press here is a press in the application: the shell must not clear the focus its window holds.
            setFocusPolicy(FocusPolicy.NONE);
            setRetainsFocus(true);
            onMouseMove.attachListener((element, event) -> pointer(event.getPosition(), -1, false, 0f), false, true);
            onMouseDown.attachListener((element, event) -> {
                pointer(event.getPosition(), event.getButtonId(), true, 0f);
                UIDocument shell = document();
                if (shell == null) return;
                // Keys follow the window the popups belong to, wherever the shell's focus was.
                ClientPortal window = appDocument.active();
                if (window != null) shell.focus().requestPointerFocus(window);
                shell.input().setPointerCapture(this);
            }, false, true);
            onMouseUp.attachListener((element, event) -> {
                pointer(event.getPosition(), event.getButtonId(), false, 0f);
                UIDocument shell = document();
                if (shell != null && shell.input().pointerCaptureTarget() == this) shell.input().releasePointerCapture();
            }, false, true);
            onMouseScroll.attachListener((element, event) -> pointer(event.getPosition(), -1, false, event.getScroll()),
                    false, true);
        }

        void place(float x, float y, float w, float h) {
            if (x == left && y == top && w == width && h == height) return;
            left = x;
            top = y;
            width = w;
            height = h;
            layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(x).top(y).width(w).height(h));
        }
    }

    /** Whether a press on {@code target} is on these popups, or on a window of the application they belong to. */
    private boolean within(@Nullable UIElement target) {
        for (UIElement node = target; node != null; node = node.parentElement()) {
            if (node == this) return true;
            if (node instanceof ClientPortal portal && portal.application() == appDocument) return true;
        }
        return false;
    }

    private final Animation.Hook place = delta -> {
        placeNow();
        return true;
    };

    /** Puts the portal over the popups: at their place relative to the active window. */
    private void placeNow() {
        float[] occupied = layer.occupied();
        ClientPortal window = appDocument.active();
        Box windowBox = window == null ? null : window.box();
        UIDocument shell = document();
        if (occupied == null || windowBox == null || shell == null) {
            setPlace(left, top, 0f, 0f);
            slotsFor(new float[0][]);
            return;
        }
        float scale = shell.boxes().uiScale();
        // A box's world position is in surface pixels and a style's left and top are logical: the window's is converted.
        originX = occupied[0];
        originY = occupied[1];
        setPlace(windowBox.worldX() / scale + originX, windowBox.worldY() / scale + originY, occupied[2] - originX,
                occupied[3] - originY);
        slotsFor(layer.rectangles());
    }

    private void setPlace(float x, float y, float w, float h) {
        if (Float.isNaN(x)) return;
        if (x == left && y == top && w == width && h == height) return;
        left = x;
        top = y;
        width = w;
        height = h;
        layout(l -> l.left(x).top(y).width(w).height(h));
    }

    /** One slot per popup, over it, in the portal's own pixels. */
    private void slotsFor(float[][] rects) {
        while (slots.size() > rects.length) remove(slots.remove(slots.size() - 1));
        while (slots.size() < rects.length) {
            Slot slot = new Slot();
            slots.add(slot);
            append(slot);
        }
        for (int i = 0; i < rects.length; i++) {
            float[] r = rects[i];
            slots.get(i).place(r[0] - originX, r[1] - originY, r[2] - r[0], r[3] - r[1]);
        }
    }

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        Surface picture = layer.picture();
        if (picture == null || width <= 0f) return;
        // The whole picture, shifted so the popups' corner is this box's: what is not a popup in it is transparent.
        picture.drawPicture(ctx, -originX, -originY, picture.pictureWidth(), picture.pictureHeight());
    }

    private void pointer(ReadOnlyVec2f at, int button, boolean pressed, float wheel) {
        UIDocument shell = document();
        if (shell == null) return;
        Vector2f local = toLocal(at.x(), at.y());
        float scale = shell.boxes().uiScale();
        appDocument.mouse(new CgSystemInput.Mouse.Event(Math.round((local.x + originX) * scale),
                Math.round((local.y + originY) * scale), 0, 0, button, pressed, wheel, System.currentTimeMillis()));
    }
}
