package com.crystalgui.app.uibuilder.style;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.text.UIText;

/**
 * A declaration's row when it has a lab: the value drawn as itself, and a press opens the lab.
 *
 * <pre>{@code
 * StyleChip chip = new StyleChip(descriptor, StylePropertyRegistry.BACKGROUND);
 * chip.onOpen(() -> GradientLab.open(chip, property, css));
 * chip.bind(fields.value("background"));
 * }</pre>
 *
 * <p>The chip is an element with the declaration applied to it — a gradient strip is the gradient, a corner
 * box is the corners — so a narrow panel still says what the value <em>is</em> rather than what it is
 * spelled. Whatever needs room to edit is the lab's job, one press away.</p>
 */
public class StyleChip extends ValueControl<String> {

    public static final Name NAME = Name.of("stylechip");

    public static final String CHIP_CLASS = "__style-chip__";
    public static final String SWATCH_CLASS = "__style-chip-swatch__";
    public static final String TEXT_CLASS = "__style-chip-text__";

    /** Changed when the lab writes: one act, like a colour picked. */
    public static final Event<StyleChip, String> CHANGED =
            ConfigControlContracts.changed(StateTypes.STRING, "", RatePolicy.IMMEDIATE);

    public static final WidgetContract<StyleChip> CONTRACT = ConfigControlContracts.register(
            StyleChip.class, "stylechip", StateTypes.STRING, "", CHANGED);

    private final UIElement swatch = new UIElement();
    private final UIText text = new UIText("");

    @Nullable
    private final StyleProperty<?> property;

    @Nullable
    private Runnable open;

    private String value = "";

    public StyleChip() {
        this(ConfigDescriptor.text("chip", ""), null);
    }

    public StyleChip(ConfigDescriptor descriptor, @Nullable StyleProperty<?> property) {
        super(NAME, descriptor, "");
        this.property = property;
        addClass(CHIP_CLASS);
        swatch.addClass(SWATCH_CLASS);
        text.addClass(TEXT_CLASS);
        append(swatch);
        append(text);
        setHitTest(true);
        onMouseDown.attachListener((element, event) -> {
            if (open != null) open.run();
            event.preventDefault();
        }, false, true);
    }

    /** What a press does — the lab this row belongs to. */
    public StyleChip onOpen(Runnable opener) {
        this.open = opener;
        return this;
    }

    @Override
    public Object getValueObject() {
        return value;
    }

    @Override
    protected void writeToWidgets(@Nullable String next) {
        value = next == null ? "" : next;
        text.setText(value.isEmpty() ? "—" : value);
        if (property == null) return;
        // THE SWATCH IS THE VALUE, applied: a gradient chip draws the gradient, a radius chip is rounded.
        if (value.isEmpty()) {
            LiveEdits.clearInline(swatch, property);
        } else {
            LiveEdits.setInline(swatch, cast(property), value);
        }
    }

    @SuppressWarnings("unchecked")
    private static StyleProperty<Object> cast(StyleProperty<?> property) {
        return (StyleProperty<Object>) property;
    }
}
