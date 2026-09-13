package com.crystalgui.widget.config;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator.Arrangement;
import com.crystalgui.widget.layout.ContextToolbar;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link ConfigForm} that lays its fields out as compact cells along a row — a tool's page in a
 * {@link ContextToolbar}, or an editor's own toolbar.
 *
 * <pre>{@code
 * public final class BrushOptions extends UIElement {
 *     public BrushOptions(Brush brush) {
 *         ToolbarForm form = ToolbarForm.into(this);
 *         form.prop(ConfigDescriptor.number("size", "Size").shortLabel("S").unit("px"), brush.size);
 *         form.separator();
 *         form.prop(ConfigDescriptor.select("mode", "Blend mode", MODES), brush.mode);
 *     }
 * }
 *
 * surface.modes().showOptionsIn(bar);   // and a tool's options() answers the page
 * }</pre>
 *
 * <ul>
 *   <li><b>A cell shows the descriptor's {@link ConfigDescriptor#shortLabel short label}</b> — a letter per
 *       number, Photoshop's and Paint.NET's — and the full label on hover, from under the bar.</li>
 *   <li>{@link #group} starts a new cluster behind a separator; a toolbar has nowhere to fold.</li>
 *   <li>Fields keep their order, and a {@link ContextToolbar} folds from the end, so put the least-used
 *       last.</li>
 * </ul>
 */
public final class ToolbarForm implements ConfigForm {

    /** On the page a toolbar form fills. */
    public static final String FORM_CLASS = "__config-toolbar__";

    /** {@code (id, newValue)} for any field on the page, as {@link ConfiguratorPanel#changed} is for a panel. */
    public final Signal.Pair<String, Object> changed = new Signal.Pair<>();

    private final UIElement page;

    private final Map<String, ConfigControl> controls = new LinkedHashMap<>();

    private int written;

    private ToolbarForm(UIElement page) {
        this.page = page;
    }

    /** A form filling {@code page}, which it lays out as a row. */
    public static ToolbarForm into(UIElement page) {
        page.addClass(FORM_CLASS);
        // A ROW WITH NO SHEET, as a Button is: in a column the bar overflows after its first cell.
        StyleGroup.defaultPipeline(page.getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));
        return new ToolbarForm(page);
    }

    @Override
    public <T> Configurator prop(ConfigDescriptor descriptor, Property<T> value) {
        return place(descriptor.id(), new Configurator(Arrangement.COMPACT, descriptor,
                ConfigControls.bound(descriptor, value)));
    }

    @Override
    public Configurator row(ConfigDescriptor descriptor, @Nullable Object value) {
        return place(descriptor.id(), new Configurator(Arrangement.COMPACT, descriptor,
                ConfigControls.require(descriptor, value)));
    }

    @Override
    public Configurator control(String id, String label, ConfigControl control) {
        return place(id, new Configurator(Arrangement.COMPACT, label, control));
    }

    @Override
    public Configurator header(String label) {
        return row(ConfigDescriptor.header(label), null);
    }

    @Override
    public Configurator note(String text) {
        return row(ConfigDescriptor.note(text), text);
    }

    @Override
    public ToolbarForm group(String title, boolean collapsed) {
        if (written > 0) separator();
        return this;
    }

    @Override
    public void separator() {
        page.append(ContextToolbar.separator());
        written++;
    }

    @Override
    public <E extends UIElement> E custom(E element) {
        page.append(element);
        written++;
        return element;
    }

    @Override
    public boolean isEmpty() {
        return written == 0;
    }

    /** The control for an id, or null. */
    @Nullable
    public ConfigControl control(String id) {
        return controls.get(id);
    }

    public Map<String, ConfigControl> controls() {
        return Collections.unmodifiableMap(controls);
    }

    private Configurator place(String id, Configurator cell) {
        ConfigControl control = cell.control();
        controls.put(id, control);
        control.changed.connect(value -> changed.emit(id, value));
        page.append(cell);
        written++;
        return cell;
    }
}
