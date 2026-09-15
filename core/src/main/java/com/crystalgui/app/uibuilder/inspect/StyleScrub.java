package com.crystalgui.app.uibuilder.inspect;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

import com.crystalgraphics.platform.CgPlatform;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.DragScrub;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.config.control.NumberControl;

/**
 * Dragging a handle scrubs one style property of a node, written inline as it goes and recorded as one undo step
 * when released — the box model's numbers and the Position section's insets.
 *
 * <pre>{@code
 * StyleScrub.on(label, node, LayoutProperties.LEFT, document)
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

    /** Units per pixel, in whole units: an edge wants a pixel's precision rather than reach. */
    public static final double RATE = 0.3;

    private final UIElement handle;
    private final UIElement node;
    private final StyleProperty<?> property;

    @Nullable
    private final UiBuilderDocument document;

    private DoubleSupplier measured = () -> Double.NaN;
    private DoubleFunction<String> css = value -> Math.round(value) + "px";
    private BooleanSupplier allowed = () -> true;
    private boolean signed;
    private double rate = RATE;
    private Runnable onStep = () -> { };

    private boolean live;
    private boolean passedThreshold;
    private double anchor;
    private int modifiers;
    private float anchoredAtX;
    private float anchoredAtY;
    private float pixelsPerUnit = 1f;

    @Nullable
    private JsonElement before;

    private StyleScrub(UIElement handle, UIElement node, StyleProperty<?> property, @Nullable UiBuilderDocument document) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.node = Objects.requireNonNull(node, "node");
        this.property = Objects.requireNonNull(property, "property");
        this.document = document;
    }

    public static StyleScrub on(UIElement handle, UIElement node, StyleProperty<?> property, @Nullable UiBuilderDocument document) {
        return new StyleScrub(handle, node, property, document);
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

    /** Units per pixel before modifiers, in whole units. {@link #RATE} by default. */
    public StyleScrub rate(double unitsPerPixel) {
        this.rate = unitsPerPixel;
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
        if (document == null || live || !allowed.getAsBoolean()) return false;
        double start = measured.getAsDouble();
        if (Double.isNaN(start)) return false;
        live = true;
        passedThreshold = false;
        anchor = start;
        modifiers = modifiersNow();
        anchoredAtX = 0f;
        anchoredAtY = 0f;
        pixelsPerUnit = Drag.pixelsPerLocalUnit(handle);
        before = NodeFields.inlineStyleOf(node);
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
        return live && passedThreshold;
    }

    private void update(float dxPixels, float dyPixels) {
        if (!live) return;
        if (!passedThreshold) {
            if (!DragScrub.passesThreshold(dxPixels, dyPixels, DragScrub.DEFAULT_THRESHOLD_PX)) return;
            passedThreshold = true;
            // FROM HERE, not from the press: pricing the travel spent reaching the threshold made the first step leap.
            anchoredAtX = dxPixels;
            anchoredAtY = dyPixels;
        }
        // A modifier pressed mid-drag re-anchors, so it prices only the travel still to come. @see NumberControl
        int now = modifiersNow();
        if (now != modifiers) {
            double at = measured.getAsDouble();
            if (!Double.isNaN(at)) anchor = at;
            anchoredAtX = dxPixels;
            anchoredAtY = dyPixels;
            modifiers = now;
        }
        DragScrub.Spec spec = DragScrub.Spec.INTEGRAL.withRate(rate);
        if (!signed) spec = spec.withRange(0d, Double.POSITIVE_INFINITY);
        double value = DragScrub.value(anchor, dxPixels - anchoredAtX, dyPixels - anchoredAtY, now, spec);
        LiveEdits.setInline(node, cast(property), css.apply(value));
        onStep.run();
    }

    /** Records the drag as one step, or puts the style back when it was cancelled or never moved. */
    private void end(boolean keep) {
        JsonElement was = before;
        boolean moved = passedThreshold;
        live = false;
        passedThreshold = false;
        before = null;
        if (was == null) return;
        if (!keep || !moved) {
            if (moved) InlineStyleCodec.replaceInto(JsonOps.INSTANCE, was, node);
            return;
        }
        LiveEdits.dropIfRedundant(node, property);
        JsonElement after = NodeFields.inlineStyleOf(node);
        if (document != null && !after.equals(was)) document.apply(new BuilderEdit.SetInlineStyle(node, was, after));
    }

    /** What is held down now; none when there is no platform to ask, as in a headless tree. */
    private static int modifiersNow() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
