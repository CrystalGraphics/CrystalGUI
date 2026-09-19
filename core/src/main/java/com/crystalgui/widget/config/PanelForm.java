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
 *
 * <p>A form from {@link ConfiguratorPanel#refill} keeps what the last fill placed wherever it places the same thing;
 * one from {@link ConfiguratorPanel#form} only appends.</p>
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

    /** The refill this form is part of, or null for a form that only appends. */
    @Nullable
    private final Refill refill;

    PanelForm(ConfiguratorPanel panel, @Nullable Refill refill) {
        this(panel, null, new int[1], refill);
    }

    private PanelForm(ConfiguratorPanel panel, @Nullable UIElement parent, int[] written, @Nullable Refill refill) {
        this.panel = panel;
        this.parent = parent;
        this.written = written;
        this.refill = refill;
    }

    /** The panel being filled. */
    public ConfiguratorPanel panel() {
        return panel;
    }

    @Override
    public <T> Configurator prop(ConfigDescriptor descriptor, Property<T> value) {
        written[0]++;
        return panel.propInto(host(), descriptor, value, refill);
    }

    @Override
    public Configurator row(ConfigDescriptor descriptor, @Nullable Object value) {
        Configurator row = panel.rowInto(host(), descriptor, value, refill);
        if (row == null) throw new IllegalArgumentException("no control is registered for " + descriptor.kind());
        written[0]++;
        return row;
    }

    @Override
    public Configurator control(String id, String label, ConfigControl control) {
        written[0]++;
        return panel.controlInto(host(), label, id, control, refill);
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
        ConfiguratorGroup group = panel.groupInto(host(), title, collapsed, refill);
        written[0]++;
        // Rows go in the group's CONTENT: a ConfiguratorGroup refuses public children.
        return new PanelForm(panel, group.content(), written, refill);
    }

    @Override
    public void separator() {
        panel.separatorInto(host(), refill);
        written[0]++;
    }

    @Override
    public <E extends UIElement> E custom(E element) {
        written[0]++;
        return panel.customInto(host(), element, refill);
    }

    @Override
    public boolean isEmpty() {
        return written[0] == 0;
    }

    private UIElement host() {
        return parent == null ? panel : parent;
    }
}
