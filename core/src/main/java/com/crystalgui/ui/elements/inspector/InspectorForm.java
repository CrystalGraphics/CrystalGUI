package com.crystalgui.ui.elements.inspector;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.Configurator;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;

import javax.annotation.Nullable;

/**
 * The one way a section says what it wants shown — Blender's {@code layout}.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Because without it every section invents its own arrangement. {@code ConfiguratorPanel} offers
 * {@code add}, {@code addTo}, {@code addRow}, {@code build} and {@code group}, and the shader node
 * inspector used four of them — sometimes with a {@code null} value argument, sometimes threading a
 * parent element through as a method parameter, sometimes building a control by hand first. Five ways to
 * say "a labelled row" is not a style problem; it is why one section cannot be read against another.</p>
 *
 * <p>Blender's answer is that a panel's {@code draw} has exactly one verb — {@code layout.prop(data,
 * "name")} — and nesting is {@code layout.row()} / {@code column()} / {@code box()}, which return another
 * layout. That is what this is: one verb, and {@link #group} returns another form.</p>
 *
 * <h3>Sections fill a shared form; they do not return a widget</h3>
 *
 * <p>Two sections in one tab write into <b>one</b> panel, which is what "two features share a tab" should
 * look like. Returning an element each would stack two independently scrolling panels with two sets of
 * group headers, and the seam would be visible.</p>
 *
 * <p>It also keeps the engine owning the parts that are engine-shaped: the panel, its scrolling, its
 * group collapse state, and when to clear it.</p>
 */
public final class InspectorForm {

    private final ConfiguratorPanel panel;

    /** Where rows land — the panel itself, or a group inside it. Null means the panel's own body. */
    @Nullable
    private final UIElement parent;

    /**
     * How many rows have been written through this form and every group opened from it.
     *
     * <p>Shared by reference with sub-forms, because "did anything get written" is a question about the
     * whole panel. The panel cannot answer it: {@code ConfiguratorPanel} is a {@code ScrollerView}, so it
     * has internal children whether or not a single row was added.</p>
     */
    private final int[] written;

    InspectorForm(ConfiguratorPanel panel) {
        this(panel, null, new int[1]);
    }

    private InspectorForm(ConfiguratorPanel panel, @Nullable UIElement parent, int[] written) {
        this.panel = panel;
        this.parent = parent;
        this.written = written;
    }

    /** Whether anything was written — what decides if this tab exists at all. */
    boolean wroteAnything() {
        return written[0] > 0;
    }

    /**
     * A collapsible group, and the form that writes into it — Blender's {@code layout.box()}.
     *
     * <p>Group collapse state is remembered by the panel across rebuilds, so a group the user closed
     * stays closed when the subject changes. That works because {@code Inspector} keeps one panel per tab
     * and refills it — the memo lives on the panel, so a panel thrown away each build would remember
     * nothing however carefully this asked.</p>
     */
    public InspectorForm group(String title) {
        return group(title, false);
    }

    /** As {@link #group(String)}, starting collapsed — for facts you look up rather than read. */
    public InspectorForm group(String title, boolean collapsed) {
        ConfiguratorGroup group = panel.group(title, collapsed);
        // ATTACHED HERE. panel.group() deliberately does not add it -- "a group may belong inside another
        // group, and only the caller knows" -- and this form is that caller.
        if (parent == null) {
            panel.addChild(group);
        } else {
            parent.addChild(group);
        }
        written[0]++;
        // Rows go in the group's CONTENT, not the group: a ConfiguratorGroup refuses public children,
        // which is its own statement about its parts and is right. Handing back the wrong parent threw
        // out of the middle of a build and left the whole inspector blank.
        return new InspectorForm(panel, group.content(), written);
    }

    /** A heading with no control — a section's own title, or a divider between concerns. */
    public Configurator header(String label) {
        return row(ConfigDescriptor.header(label), null);
    }

    /** The verb. A descriptor describes the field; the engine picks the widget. */
    public Configurator row(ConfigDescriptor descriptor) {
        return row(descriptor, null);
    }

    /** The verb, with the value the field currently holds. */
    public Configurator row(ConfigDescriptor descriptor, @Nullable Object value) {
        written[0]++;
        return parent == null ? panel.add(descriptor, value) : panel.addTo(parent, descriptor, value);
    }

    /**
     * A row whose control is already built.
     *
     * <p>The escape hatch, and it should stay one. A field whose widget cannot be derived from a
     * descriptor is a gap in {@link ConfigDescriptor}, not a reason for a section to lay out its own —
     * that is the difference between extending the reflective half and going around it.</p>
     */
    public Configurator control(String id, String label, ConfigControl control) {
        written[0]++;
        return panel.addRow(parent == null ? panel : parent, label, id, control);
    }

    /** The panel being filled, for the few things that genuinely need it — reading a live control. */
    public ConfiguratorPanel panel() {
        return panel;
    }
}
