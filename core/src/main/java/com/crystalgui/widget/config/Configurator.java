package com.crystalgui.widget.config;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

import com.crystalgui.core.config.ConfigDescriptor;
import javax.annotation.Nullable;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.widget.config.control.InfoControl;

/**
 * <b>One row of an inspector: a label column, a control, and room for a tip.</b>
 *
 * <p>Unity reference: {@code docs/research/unity-inspector/01-inspector-property.png} — four rows, four
 * different control kinds, one rhythm.</p>
 *
 * <p>Ported from LDLib2's {@code Configurator}, with the one change this engine needs: <b>it wraps a
 * {@link ConfigControl} rather than being its superclass.</b> See {@link ConfigControl} for why —
 * briefly, a node's unconnected input hosts the bare control with no row at all, and a control welded
 * to a row cannot go there.</p>
 *
 * <h3>The label column is fixed-width and LEFT-aligned</h3>
 * <p>Measured off the reference, and it is the property that makes a stack of unlike controls read as a
 * form: every control starts on a common left edge regardless of how long its label is. Right-aligning
 * the labels — which several toolkits do — ragged-edges the controls instead, and a column of controls
 * that do not line up reads as a pile.</p>
 *
 * <h3>A self-labelling control gets no label</h3>
 * <p>{@link ConfigControl#selfLabelling()} is the control's call, not the row's, because only the
 * control knows whether it says what it is by being what it is. A colour swatch does; a number does
 * not.</p>
 */
public class Configurator extends UIElement {

    public static final Name NAME = Name.of("configurator");

    public static final String ROW_CLASS = "__configurator__";
    public static final String LABEL_CLASS = "__label__";
    public static final String INLINE_CLASS = "__inline__";

    private final UIText label;
    private final UIElement inline = new UIElement();
    private final ConfigControl control;

    /**
     * The no-argument constructor the registry's factory needs.
     *
     * <p>Over an {@link InfoControl}, not {@code null}: a Configurator is a LABEL and a CONTROL, and
     * every method on it asks the control something — {@code selfLabelling()} on the very first pass.
     * A null one is not an empty configurator, it is an NPE on the frame that lays it out.</p>
     */
    public Configurator() {
        this("", new InfoControl());
    }

    public Configurator(String labelText, ConfigControl control) {
        super(NAME);
        this.control = control;
        addClass(ROW_CLASS);
        boolean labelled = !control.selfLabelling() && labelText != null && !labelText.isEmpty();
        label = new UIText(labelled ? labelText : "");
        label.addClass(LABEL_CLASS);
        // Scenery. A label that took the pointer would make the left third of every row dead, and on a
        // row whose control is a checkbox that is most of the row.
        label.setHitTest(false);

        inline.addClass(INLINE_CLASS);
        inline.append(control);

        if (labelled) append(label);
        append(inline);
    }

    public Configurator(ConfigDescriptor descriptor, ConfigControl control) {
        this(descriptor.label(), control);
    }

    public ConfigControl control() {
        return control;
    }

    /** Null on a self-labelling row — there is no label element, not merely an empty one. */
    @Nullable
    public UIText label() {
        return label.parent() == null ? null : label;
    }

    public UIElement inline() {
        return inline;
    }
}
