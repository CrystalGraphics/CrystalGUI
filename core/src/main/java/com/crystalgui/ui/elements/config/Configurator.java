package com.crystalgui.ui.elements.config;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;

import com.crystalgui.core.config.ConfigDescriptor;
import javax.annotation.Nullable;

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

    public static final String ROW_CLASS = "__configurator__";
    public static final String LABEL_CLASS = "__label__";
    public static final String INLINE_CLASS = "__inline__";

    private final UIText label;
    private final UIElement inline = new UIElement();
    private final ConfigControl control;

    public Configurator(String labelText, ConfigControl control) {
        this.control = control;
        addClass(ROW_CLASS);
        markAsInternal();

        boolean labelled = !control.selfLabelling() && labelText != null && !labelText.isEmpty();
        label = new UIText(labelled ? labelText : "");
        label.addClass(LABEL_CLASS);
        // Scenery. A label that took the pointer would make the left third of every row dead, and on a
        // row whose control is a checkbox that is most of the row.
        label.setHitTest(false);

        inline.addClass(INLINE_CLASS);
        inline.addChild(control);

        if (labelled) addInternalChild(label);
        addInternalChild(inline);
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
        return label.getParent() == null ? null : label;
    }

    public UIElement inline() {
        return inline;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
