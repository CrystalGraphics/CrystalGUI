package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.render.texture.CgUiColorField;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;

/**
 * A gradient's stops laid left to right: the ramp over a checkerboard, and under it a swatch for each stop pointing at
 * where it sits.
 *
 * <pre>{@code
 * Property<Integer> selected = Property.of(0);
 * GradientBar bar = new GradientBar("ramp", BACKGROUND, selected);
 * bar.bind(css.map(Gradient::parse, Gradient::toString));
 * }</pre>
 *
 * <ul>
 *   <li><b>Click</b> the ramp or the empty track to add a stop there, in the color the ramp already shows.</li>
 *   <li><b>Drag</b> a swatch along to move its stop, or off the track to remove it; coming back cancels.</li>
 *   <li><b>Arrow keys</b> move the picked stop a percent, a tenth with Ctrl; <b>Delete</b> removes it.</li>
 * </ul>
 *
 * <p><b>Beside the ramp, not on it</b>, as Figma's, Photoshop's and DevTools' stops are: a handle over the ramp hid the
 * very color it marks. <b>Always left to right</b>, whatever the gradient's own direction: a stop at 20% sits under the
 * color at 20%.</p>
 */
public final class GradientBar extends ValueControl<Gradient> {

    public static final Name NAME = Name.of("gradientbar");

    public static final String BAR_CLASS = "__gradient-bar__";
    /** The strip the gradient is drawn in, over its checkerboard. */
    public static final String RAMP_CLASS = "__gradient-ramp__";
    public static final String FILL_CLASS = "__gradient-fill__";
    /** Where the stops' swatches sit, under the ramp. */
    public static final String TRACK_CLASS = "__gradient-track__";
    public static final String STOP_CLASS = "__gradient-stop__";
    public static final String POINTER_CLASS = "__gradient-stop-pointer__";
    public static final String SWATCH_CLASS = "__gradient-stop-swatch__";
    public static final String ACTIVE_CLASS = "__active__";
    /** On a stop dragged far enough off the track that letting go removes it. */
    public static final String REMOVING_CLASS = "__removing__";

    /** How far off the track a stop is dragged before letting go removes it, in px. */
    private static final float REMOVE_DISTANCE = 28f;

    private final StyleProperty<?> property;
    private final Property<Integer> selected;
    private final UIElement ramp = new UIElement();
    private final UIElement fill = new UIElement();
    private final UIElement track = new UIElement();
    private final ChildList<Handle> handles = new ChildList<>(track, this::handle);

    /** One stop's handle: the pointer at the position it marks, and the swatch of its color under it. */
    private static final class Handle extends UIElement {
        final UIElement swatch = new UIElement();
    }

    /** @param property what the ramp draws the gradient with — {@code background} or {@code overlay} */
    public GradientBar(String id, StyleProperty<?> property, Property<Integer> selected) {
        super(NAME, ConfigDescriptor.text(id, ""), Gradient.DEFAULT);
        this.property = property;
        this.selected = selected;
        addClass(BAR_CLASS);
        ramp.addClass(RAMP_CLASS);
        fill.addClass(FILL_CLASS);
        track.addClass(TRACK_CLASS);
        ramp.append(fill);
        append(ramp);
        append(track);
        onConnected(() -> StyleGroup.inlinePipeline(ramp.getStyle().getGeneralGroup(), group -> group.background(
                new CgUiColorField().setMode(CgUiColorField.Mode.GRADIENT).setGradient(0, 0).setCornerRadius(3f, 3f))));

        // A PRESS ON THE RAMP OR THE EMPTY TRACK ADDS A STOP where it lands, as Figma's does: a stop's own press is
        // claimed by the stop, so only an empty place reaches here.
        for (UIElement strip : new UIElement[] {ramp, track}) {
            strip.setHitTest(true);
            strip.onMouseDown.attachListener((element, event) -> {
                if (!(event instanceof MouseEvent.Down down) || down.getButtonId() != CgMouseCodes.LEFT_BUTTON
                        || down.isDefaultPrevented()) {
                    return;
                }
                float where = StyleGizmos.fractionAt(strip, down.getPosition().x(), down.getPosition().y());
                Gradient now = gradient();
                commitAndShow(now.withStopAt(where));
                selected.set(now.indexAt(where));
                event.preventDefault();
            }, false, true);
        }

        setFocusPolicy(FocusPolicy.CLICK);
        onKeyDown.attachListener((element, event) -> {
            if (!(event instanceof KeyboardEvent.Down down)) return;
            int key = down.getKeyCode();
            int at = picked();
            if (key == CgKeyCodes.KEY_LEFT || key == CgKeyCodes.KEY_RIGHT) {
                boolean fine = CgModifiers.hasCtrl(CgPlatform.input().getCurrentModifiers());
                float step = (fine ? 0.001f : 0.01f) * (key == CgKeyCodes.KEY_LEFT ? -1 : 1);
                Gradient now = gradient();
                float moved = Math.max(0f, Math.min(1f, Math.round((now.position(at) + step) * 1000f) / 1000f));
                commitAndShow(now.withStopMoved(at, moved));
                selected.set(now.indexAfterMove(at, moved));
                event.preventDefault();
            } else if (key == CgKeyCodes.KEY_DELETE || key == CgKeyCodes.KEY_BACK) {
                commitAndShow(gradient().withoutStop(at));
                selected.set(Math.max(0, at - 1));
                event.preventDefault();
            }
        }, false, true);
        PropertyWatch.follow(this, selected, index -> paintSelection());
    }

    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    protected void writeToWidgets(@Nullable Gradient value) {
        Gradient gradient = value == null ? Gradient.DEFAULT : value;
        LiveEdits.setInline(fill, property, gradient.withDirection("90deg").toString());
        handles.resize(gradient.stops().size());
        for (int i = 0; i < gradient.stops().size(); i++) {
            Handle handle = handles.get(i);
            LiveEdits.setInline(handle, LayoutProperties.LEFT, CssValues.write(gradient.position(i) * 100f) + "%");
            StyleChip.paintColor(handle.swatch, gradient.stops().get(i).argb());
        }
        paintSelection();
    }

    private void paintSelection() {
        int at = picked();
        for (int i = 0; i < handles.size(); i++) handles.get(i).toggleClass(ACTIVE_CLASS, i == at);
    }

    private int picked() {
        int size = gradient().stops().size();
        return Math.max(0, Math.min(selected.get() == null ? 0 : selected.get(), size - 1));
    }

    private Handle handle(int index) {
        Handle handle = new Handle();
        handle.addClass(STOP_CLASS);
        UIElement pointer = new UIElement();
        pointer.addClass(POINTER_CLASS);
        handle.swatch.addClass(SWATCH_CLASS);
        handle.append(pointer);
        handle.append(handle.swatch);
        float[] from = new float[1];
        boolean[] off = new boolean[1];
        // WHERE THE DRAGGED STOP IS NOW: passing a neighbour reorders the stops, so it leaves this handle's index.
        int[] at = new int[1];
        StyleGizmos.drag(handle, () -> {
            at[0] = index;
            from[0] = gradient().position(index);
            off[0] = false;
            selected.set(index);
            beginInteraction();
        }, (dx, dy) -> {
            float width = ramp.box() == null ? 1f : Math.max(1f, ramp.box().width());
            float moved = Math.max(0f, Math.min(1f, from[0] + dx / width));
            // DRAGGED OFF THE TRACK, as a gradient editor's stop is: it goes on release, and coming back cancels it.
            off[0] = Math.abs(dy) > REMOVE_DISTANCE && gradient().stops().size() > 2;
            Gradient now = gradient();
            if (at[0] < now.stops().size()) {
                int next = now.indexAfterMove(at[0], moved);
                commitAndShow(now.withStopMoved(at[0], moved));
                at[0] = next;
                selected.set(next);
            }
            for (int i = 0; i < handles.size(); i++) handles.get(i).toggleClass(REMOVING_CLASS, off[0] && i == at[0]);
        }, () -> {
            for (int i = 0; i < handles.size(); i++) handles.get(i).removeClass(REMOVING_CLASS);
            if (off[0]) {
                commitAndShow(gradient().withoutStop(at[0]));
                selected.set(Math.max(0, at[0] - 1));
            }
            endInteraction();
        });
        return handle;
    }

    private Gradient gradient() {
        Gradient now = getValue();
        return now == null ? Gradient.DEFAULT : now;
    }
}
