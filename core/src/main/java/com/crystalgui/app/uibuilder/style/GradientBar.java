package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;

/**
 * A gradient drawn as itself, with a handle on each stop: drag a handle along the bar to move its stop,
 * press one to pick it, double-click the bar to add one.
 *
 * <pre>{@code
 * Property<Integer> selected = Property.of(0);
 * GradientBar bar = new GradientBar("ramp", BACKGROUND, selected);
 * bar.bind(css.map(Gradient::parse, Gradient::toString));
 * }</pre>
 */
public final class GradientBar extends ValueControl<Gradient> {

    public static final Name NAME = Name.of("gradientbar");

    public static final String BAR_CLASS = "__gradient-bar__";
    public static final String STOP_CLASS = "__gradient-stop__";
    public static final String ACTIVE_CLASS = "__active__";

    private final StyleProperty<?> property;
    private final Property<Integer> selected;
    private final ChildList<UIElement> handles = new ChildList<>(this, this::handle);

    /** @param property what the bar draws the gradient with — {@code background} or {@code overlay} */
    public GradientBar(String id, StyleProperty<?> property, Property<Integer> selected) {
        super(NAME, ConfigDescriptor.text(id, ""), Gradient.DEFAULT);
        this.property = property;
        this.selected = selected;
        addClass(BAR_CLASS);
        setHitTest(true);
        // A DOUBLE PRESS ON THE BAR ADDS A STOP where it lands, as Figma's and DevTools' gradient bars do.
        onMouseDown.attachListener((element, event) -> {
            // NOT ON A HANDLE: a double press there is two picks of that stop, and the handle claims it.
            if (!(event instanceof MouseEvent.Down down) || down.getDetail() < 2 || down.isDefaultPrevented()) return;
            float where = StyleGizmos.fractionAt(this, down.getPosition().x(), down.getPosition().y());
            Gradient now = gradient();
            commit(now.withStopAt(where));
            selected.set(now.indexAt(where));
            event.preventDefault();
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
        LiveEdits.setInline(this, property, gradient.toString());
        handles.resize(gradient.stops().size());
        for (int i = 0; i < gradient.stops().size(); i++) {
            LiveEdits.setInline(handles.get(i), LayoutProperties.LEFT,
                    CssValues.write(gradient.position(i) * 100f) + "%");
        }
        paintSelection();
    }

    private void paintSelection() {
        int at = selected.get() == null ? 0 : selected.get();
        for (int i = 0; i < handles.size(); i++) handles.get(i).toggleClass(ACTIVE_CLASS, i == at);
    }

    private UIElement handle(int index) {
        UIElement handle = new UIElement();
        handle.addClass(STOP_CLASS);
        float[] from = new float[1];
        StyleGizmos.drag(handle, () -> {
            from[0] = gradient().position(index);
            selected.set(index);
            beginInteraction();
        }, (dx, dy) -> {
            float width = box() == null ? 1f : Math.max(1f, box().width());
            float moved = Math.max(0f, Math.min(1f, from[0] + dx / width));
            Gradient now = gradient();
            if (index < now.stops().size()) {
                commit(now.withStop(index, now.stops().get(index).withPosition(moved)));
            }
        }, this::endInteraction);
        return handle;
    }

    private Gradient gradient() {
        Gradient now = getValue();
        return now == null ? Gradient.DEFAULT : now;
    }
}
