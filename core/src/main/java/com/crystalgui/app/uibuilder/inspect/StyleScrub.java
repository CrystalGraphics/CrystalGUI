package com.crystalgui.app.uibuilder.inspect;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

import javax.annotation.Nullable;



import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.DragScrub;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.config.control.NumberControl;

/**
 * Dragging a handle scrubs one style property, written as it goes and recorded as one undo step when released —
 * the box model's numbers and the Position section's insets.
 *
 * <pre>{@code
 * StyleScrub.on(label, LayoutProperties.LEFT, Declarations.inline(node, document))
 *         .measuring(() -> node.box().x())                 // the value the drag starts from
 *         .writing(value -> Math.round(value) + "px")      // what a value is as CSS
 *         .allowedWhen(() -> isAbsolute(node))
 *         .attach();                                       // a press on the label starts it
 * }</pre>
 *
 * <p>Whole units a little over three pixels apart by default, Shift ten times faster, Escape mid-drag restoring the value — the
 * rate {@link DragScrub} prices, re-anchored where the drag passes its threshold so the first step does not leap.
 * A value dragged back to what the node has without the declaration leaves nothing inline.</p>
 *
 * <ul>
 *   <li>With no document nothing is scrubbed: a live pick has nothing to record into.</li>
 *   <li>A measurement of NaN — nothing laid out — refuses the drag.</li>
 *   <li>Call {@link #begin} yourself instead of {@link #attach} when the handle's press means something else too,
 *       as a double-click that opens a field does.</li>
 * </ul>
 */
public final class StyleScrub {

    private final UIElement handle;
    private final StyleProperty<?> property;

    /** Where the drag's values land: the node's inline style, or a rule. @see Declarations */
    private final Declarations target;

    private DoubleSupplier measured = () -> Double.NaN;
    private DoubleFunction<String> css = value -> Math.round(value) + "px";
    private BooleanSupplier allowed = () -> true;
    private boolean signed;
    /** An edge wants a pixel's precision rather than reach, and is written in whole pixels. */
    private DragScrub.Spec spec = DragScrub.Spec.PIXELS.withIntegral(true);
    private Runnable onStep = () -> { };

    private boolean live;
    private DragScrub.Gesture scrub = new DragScrub.Gesture(spec);
    private float pixelsPerUnit = 1f;

    private StyleScrub(UIElement handle, StyleProperty<?> property, Declarations target) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.property = Objects.requireNonNull(property, "property");
        this.target = Objects.requireNonNull(target, "target");
    }

    /** A drag writing wherever {@code target} keeps the declaration. */
    public static StyleScrub on(UIElement handle, StyleProperty<?> property, Declarations target) {
        return new StyleScrub(handle, property, target);
    }

    /** As {@link #on(UIElement, StyleProperty, Declarations)}, writing the node's own inline style. */
    public static StyleScrub on(UIElement handle, UIElement node, StyleProperty<?> property,
                                @Nullable UiBuilderDocument document) {
        return new StyleScrub(handle, property, Declarations.inline(node, document));
    }

    /** The value a drag starts from, asked at the press and when a modifier re-anchors it. */
    public StyleScrub measuring(DoubleSupplier value) {
        this.measured = Objects.requireNonNull(value, "value");
        return this;
    }

    /** What a scrubbed value is written as. Pixels by default. */
    public StyleScrub writing(DoubleFunction<String> css) {
        this.css = Objects.requireNonNull(css, "css");
        return this;
    }

    /** Whether a press may start a drag right now. */
    public StyleScrub allowedWhen(BooleanSupplier allowed) {
        this.allowed = Objects.requireNonNull(allowed, "allowed");
        return this;
    }

    /** Units per pixel before modifiers, in whole units. {@link DragScrub.Spec#PIXELS}'s by default. */
    public StyleScrub rate(double unitsPerPixel) {
        this.spec = spec.withRate(unitsPerPixel);
        return this;
    }

    /**
     * Runs after every value a drag writes — for a field showing the same property, which otherwise hears of the change
     * only when the drag records it on release.
     */
    public StyleScrub onStep(Runnable step) {
        this.onStep = Objects.requireNonNull(step, "step");
        return this;
    }

    /** Whether the value may go below zero — a margin or an inset may, a padding or a size may not. */
    public StyleScrub signed(boolean signed) {
        this.signed = signed;
        return this;
    }

    /** Makes a single press on the handle start a drag, with the scrub cursor over it. */
    public StyleScrub attach() {
        handle.addClass(NumberControl.SCRUB_HANDLE_CLASS);
        // A gesture needs the press, and a label is scenery with hit-testing off by default.
        handle.setHitTest(true);
        handle.onMouseDown.attachListener((element, event) -> {
            if (event instanceof MouseEvent.Down down && down.getDetail() < 2 && begin(down.getPosition().x(), down.getPosition().y())) {
                event.preventDefault();
            }
        }, false, true);
        return this;
    }

    /** Starts a drag at a press in surface pixels; false when it may not start. Below the threshold it writes nothing. */
    public boolean begin(float surfaceX, float surfaceY) {
        if (!target.canWrite() || live || !allowed.getAsBoolean()) return false;
        double start = measured.getAsDouble();
        if (Double.isNaN(start)) return false;
        live = true;
        scrub = new DragScrub.Gesture(signed ? spec : spec.withRange(0d, Double.POSITIVE_INFINITY));
        scrub.begin(start);
        pixelsPerUnit = Drag.pixelsPerLocalUnit(handle);
        target.beginGesture();
        Drag.start(handle, surfaceX, surfaceY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mouseX, float mouseY, float startX, float startY, float deltaX, float deltaY) {
                update(deltaX * pixelsPerUnit, deltaY * pixelsPerUnit);
            }

            @Override
            public void onDragEnd(float mouseX, float mouseY) {
                end(true);
            }

            @Override
            public void onDragCancel() {
                end(false);
            }
        });
        return true;
    }

    /** Whether a drag has passed its threshold and is writing values. */
    public boolean isScrubbing() {
        return live && scrub.isLive();
    }

    private void update(float dxPixels, float dyPixels) {
        if (!live || !scrub.update(dxPixels, dyPixels)) return;
        target.set(property, css.apply(scrub.value()));
        onStep.run();
    }

    /** Records the drag as one step, or puts the value back when it was cancelled or never moved. */
    private void end(boolean keep) {
        boolean moved = scrub.isLive();
        live = false;
        target.endGesture(keep && moved);
    }
}
