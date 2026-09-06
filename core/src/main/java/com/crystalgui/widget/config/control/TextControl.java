package com.crystalgui.widget.config.control;

import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.ValueControl;

import javax.annotation.Nullable;
import java.util.function.Predicate;
import com.crystalgui.ui.dom.Name;

/**
 * Free text, optionally constrained.
 *
 * <p>Unity reference: the {@code Reference} row in
 * {@code docs/research/unity-inspector/01-inspector-property.png}, and Swizzle's mask in
 * {@code docs/research/unity-nodes/08-validated-text.png}.</p>
 *
 * <h3>A rejected keystroke is not an erased one</h3>
 * <p>When a {@link ConfigDescriptor#validator()} refuses the text, this control declines to
 * <em>commit</em> and leaves what was typed on screen. It does not rewrite the field. Swizzle's mask is
 * the case that proves it: every prefix of a valid mask is itself valid, but reaching {@code "xy"} from
 * {@code "zw"} passes through states that are not — and a control that erased them would make the mask
 * uneditable except by deleting it entirely.</p>
 *
 * <p>The invalid state is CSS's to show, through {@code :invalid} — which is a pseudo-class this engine
 * already resolves from {@code isInvalid()}, so a theme styles it without any Java saying what invalid
 * looks like.</p>
 */
public class TextControl extends ValueControl<String> {

    public static final Name NAME = Name.of("textcontrol");

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code textcontrol } by tag. */
    public TextControl() {
        this(ConfigDescriptor.text("", ""), "");
    }

    /** Debounced, and committed on blur or Enter. */
    public static final Event<TextControl, String> CHANGED =
            ConfigControlContracts.changed(StateTypes.STRING, "", RatePolicy.TYPING);

    public static final WidgetContract<TextControl> CONTRACT = ConfigControlContracts.register(
            TextControl.class, "textcontrol", StateTypes.STRING, "", CHANGED);


    private final TextField field = new TextField();

    @Nullable
    private final Predicate<String> validator;

    private boolean invalid;

    public TextControl(ConfigDescriptor descriptor, String defaultValue) {
        super(NAME, descriptor, defaultValue);
        this.validator = descriptor.validator();
        addClass("__text__");
        append(field);
        field.setText(defaultValue == null ? "" : defaultValue);

        field.attachListener(text -> {
            boolean ok = validator == null || validator.test(text);
            if (ok != !invalid) {
                invalid = !ok;
                // The pseudo-class is re-evaluated on demand, not observed — without this the ring
                // never appears and the rule in the sheet looks like it does nothing.
                invalidateStyleMatch();
            }
            if (ok) commit(text);
        });
    }

    public TextField field() {
        return field;
    }

    @Override
    public boolean isInvalid() {
        return invalid;
    }

    @Override
    protected void writeToWidgets(@Nullable String value) {
        field.setText(value == null ? "" : value);
        if (invalid) {
            invalid = false;
            invalidateStyleMatch();
        }
    }
}
