package com.crystalgui.widget.config.control;

import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.ValueControl;

import javax.annotation.Nullable;
import com.crystalgui.ui.dom.Name;

/**
 * On or off — a checkbox, or a button that stays pressed while it is on.
 *
 * <pre>{@code
 * form.prop(ConfigDescriptor.bool("exposed", "Exposed"), exposed);                       // [x] Exposed
 * form.prop(ConfigDescriptor.bool("link", "Maintain aspect ratio").toggle(true), linked);  // a chain button
 * }</pre>
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

    public static final Name NAME = Name.of("booleancontrol");

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code booleancontrol } by tag. */
    public BooleanControl() {
        this(ConfigDescriptor.bool("", ""), false);
    }

    /** A checkbox: discrete, so every flip travels. */
    public static final Event<BooleanControl, Boolean> CHANGED =
            ConfigControlContracts.changed(StateTypes.BOOL, Boolean.FALSE, RatePolicy.IMMEDIATE);

    public static final WidgetContract<BooleanControl> CONTRACT = ConfigControlContracts.register(
            BooleanControl.class, "booleancontrol", StateTypes.BOOL, Boolean.FALSE, CHANGED);


    /** On a {@linkplain ConfigDescriptor#toggle toggle}'s button. */
    public static final String TOGGLE_CLASS = "__toggle__";

    /** On a toggle's button while it is on. */
    public static final String ON_CLASS = "__on__";

    @Nullable
    private final Checkbox checkbox;

    @Nullable
    private final Button toggle;

    public BooleanControl(ConfigDescriptor descriptor, boolean defaultValue) {
        super(NAME, descriptor, defaultValue);
        addClass("__boolean__");
        if (descriptor.toggle()) {
            checkbox = null;
            // THE NAME IS ON THE BUTTON, as Blender draws a toggled property -- the short label when there
            // is one, so an icon toggle can say nothing and let the sheet draw it.
            String shortLabel = descriptor.shortLabel();
            toggle = new Button(shortLabel != null ? shortLabel : descriptor.label());
            toggle.addClass(TOGGLE_CLASS);
            // PAINTED BEFORE IT IS COMMITTED: a button keeps no state of its own, so nothing else would
            // show the press -- unlike a checkbox, which is already checked by the time it reports.
            toggle.onPressed.connect(() -> {
                boolean next = !Boolean.TRUE.equals(getValue());
                writeToWidgets(next);
                commit(next);
            });
            append(toggle);
        } else {
            toggle = null;
            checkbox = new Checkbox("");
            checkbox.attachListener(this::commit);
            append(checkbox);
        }
        quietly(() -> writeToWidgets(defaultValue));
    }

    /** A toggle's button carries its own name, so a row adds no label beside it. */
    @Override
    public boolean selfLabelling() {
        return toggle != null;
    }

    /** The checkbox, or null when this is a {@linkplain ConfigDescriptor#toggle toggle}. */
    @Nullable
    public Checkbox checkbox() {
        return checkbox;
    }

    /** The toggle's button, or null when this is a checkbox. */
    @Nullable
    public Button toggleButton() {
        return toggle;
    }

    @Override
    protected void writeToWidgets(@Nullable Boolean value) {
        boolean on = Boolean.TRUE.equals(value);
        if (checkbox != null) checkbox.setChecked(on);
        if (toggle != null && toggle.hasClass(ON_CLASS) != on) toggle.toggleClass(ON_CLASS, on);
    }
}
