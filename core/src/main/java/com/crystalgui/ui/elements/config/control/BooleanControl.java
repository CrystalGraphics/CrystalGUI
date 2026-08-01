package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;

import javax.annotation.Nullable;

/**
 * A toggle.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/05-toggle.png}, and the {@code Exposed} row in
 * {@code docs/research/unity-inspector/01-inspector-property.png}.</p>
 *
 * <p><b>The one control in the kit that does not fill its column.</b> A checkbox is square — its width
 * is a function of its height, not of the space available — so it sits at the left of the control
 * column and leaves the rest empty. Stretching it is the single most obvious way to make a form look
 * wrong, and the reason the kit's sizing rule carves it out by name.</p>
 */
public class BooleanControl extends ValueControl<Boolean> {

    private final Checkbox checkbox = new Checkbox("");

    public BooleanControl(ConfigDescriptor descriptor, boolean defaultValue) {
        super(descriptor, defaultValue);
        addClass("__boolean__");
        markAsInternal();
        addInternalChild(checkbox);
        checkbox.setChecked(defaultValue);
        checkbox.attachListener(this::commit);
    }

    public Checkbox checkbox() {
        return checkbox;
    }

    @Override
    protected void writeToWidgets(@Nullable Boolean value) {
        checkbox.setChecked(Boolean.TRUE.equals(value));
    }
}
