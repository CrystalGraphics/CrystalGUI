package com.crystalgui.widget.config;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

/**
 * A {@link ConfigForm} that writes rows into a {@link ConfiguratorPanel} — an inspector tab, a
 * preferences page, a tuning window.
 *
 * <pre>{@code
 * ConfiguratorPanel panel = new ConfiguratorPanel();
 * PanelForm form = panel.form();
 * form.prop(ConfigDescriptor.number("blur", "Blur").range(0f, 40f), blur);
 * }</pre>
 *
 * <p>Groups are the panel's, so a group the user closed stays closed when the panel is refilled — see
 * {@link ConfiguratorPanel#group}. A form opened from a group writes into that group's content and shares
 * this form's {@link #isEmpty}, since "was anything written" is a question about the whole panel.</p>
 */
public final class PanelForm implements ConfigForm {

    /** A {@link #separator()} between rows. */
    public static final String SEPARATOR_CLASS = "__configurator-separator__";

    private final ConfiguratorPanel panel;

    /** Where rows land — a group's content, or null for the panel's own body. */
    @Nullable
    private final UIElement parent;

    /** Shared by reference with the forms of groups opened from this one. */
    private final int[] written;

    public PanelForm(ConfiguratorPanel panel) {
        this(panel, null, new int[1]);
    }

    private PanelForm(ConfiguratorPanel panel, @Nullable UIElement parent, int[] written) {
        this.panel = panel;
        this.parent = parent;
        this.written = written;
    }

    /** The panel being filled. */
    public ConfiguratorPanel panel() {
        return panel;
    }

    @Override
    public <T> Configurator prop(ConfigDescriptor descriptor, Property<T> value) {
        written[0]++;
        return panel.propTo(host(), descriptor, value);
    }

    @Override
    public Configurator row(ConfigDescriptor descriptor, @Nullable Object value) {
        Configurator row = panel.addTo(host(), descriptor, value);
        if (row == null) throw new IllegalArgumentException("no control is registered for " + descriptor.kind());
        written[0]++;
        return row;
    }

    @Override
    public Configurator control(String id, String label, ConfigControl control) {
        written[0]++;
        return panel.addRow(host(), label, id, control);
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
    public PanelForm group(String title, boolean collapsed) {
        ConfiguratorGroup group = panel.group(title, collapsed);
        // ATTACHED HERE: panel.group() leaves that to its caller, since a group may sit inside another.
        host().append(group);
        written[0]++;
        // Rows go in the group's CONTENT: a ConfiguratorGroup refuses public children.
        return new PanelForm(panel, group.content(), written);
    }

    @Override
    public void separator() {
        host().append(new UIElement().addClass(SEPARATOR_CLASS));
        written[0]++;
    }

    @Override
    public <E extends UIElement> E custom(E element) {
        host().append(element);
        written[0]++;
        return element;
    }

    @Override
    public boolean isEmpty() {
        return written[0] == 0;
    }

    private UIElement host() {
        return parent == null ? panel : parent;
    }
}
