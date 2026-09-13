package com.crystalgui.widget.config;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

/**
 * <b>The one way anything says what it wants shown</b> — Blender's {@code layout}.
 *
 * <p>A descriptor says what a field is, a {@link Property} says where its value lives, and the form
 * builds the control and binds it. The same calls fill an inspector panel ({@link PanelForm}) and a
 * toolbar ({@link ToolbarForm}); what differs is only how the fields are arranged.</p>
 *
 * <pre>{@code
 * void describe(ConfigForm form) {
 *     form.header("Transform");
 *     form.prop(ConfigDescriptor.number("rotation", "Rotation").unit("°"), rotation);
 *     form.prop(ConfigDescriptor.bool("visible", "Visible"), visible);
 *
 *     ConfigForm about = form.group("About", true);
 *     about.prop(ConfigDescriptor.info("id", "Id"), Property.derived(node::id));
 * }
 * }</pre>
 *
 * <p>A field needs nothing wired: an edit writes the property, a change made elsewhere shows up, and a
 * scrub is one step of the property's {@linkplain Property#editedIn history}. A host that wants to react
 * to the edit reacts in the property's writer.</p>
 *
 * <ul>
 *   <li><b>Prefer {@link #prop}.</b> {@link #row} starts at a value and follows nothing — right for a fact
 *       read once, wrong for anything that can change while it is on screen.</li>
 *   <li>{@link #control} is the escape hatch for a control that cannot come from a descriptor. A field
 *       that could is a gap in {@link ConfigDescriptor}, not a reason to build one by hand.</li>
 *   <li>A kind with no registered control throws: a blank field is the failure the registry exists to
 *       prevent.</li>
 * </ul>
 */
public interface ConfigForm {

    /**
     * A field for {@code descriptor}, bound to {@code value}.
     *
     * @throws IllegalArgumentException when the descriptor's kind edits no value, or has no control
     * @see ValueControl#bind the property's type for each kind
     */
    <T> Configurator prop(ConfigDescriptor descriptor, Property<T> value);

    /** A field starting at {@code value} and bound to nothing. @see #prop */
    Configurator row(ConfigDescriptor descriptor, @Nullable Object value);

    /** A field around a control that is already built. */
    Configurator control(String id, String label, ConfigControl control);

    /** A heading — a section's title, or a break between concerns. */
    Configurator header(String label);

    /**
     * A sentence of guidance across the whole field.
     *
     * <pre>{@code
     * form.note("Rim is the hairline at the boundary; glow is the broad falloff.");
     * form.prop(ConfigDescriptor.note("css"), Property.derived(this::css));   // a live readout
     * }</pre>
     */
    Configurator note(String text);

    /**
     * A group, and the form that writes into it — Blender's {@code layout.box()}.
     *
     * <p>A panel draws a foldout; a toolbar has nowhere to fold, so it starts a new cluster.</p>
     */
    ConfigForm group(String title, boolean collapsed);

    /** As {@link #group(String, boolean)}, open. */
    default ConfigForm group(String title) {
        return group(title, false);
    }

    /** A hairline between two groups of fields. */
    void separator();

    /** Places something that is not a field at all, in field order. */
    <E extends UIElement> E custom(E element);

    /** Whether nothing has been written — what decides if a panel is worth showing. */
    boolean isEmpty();
}
