package com.crystalgui.widget.config.control;

import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.ValueControl;

import javax.annotation.Nullable;
import java.util.List;
import com.crystalgui.ui.dom.Name;

/**
 * One of a fixed list.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/06-dropdown-*.png} — four of them, because this
 * is the workhorse of the whole catalogue. {@code 06-dropdown-22-options.png} is Blend's twenty-two
 * modes, which is the case that decides the menu must scroll rather than grow.</p>
 *
 * <h3>An unknown stored value resolves to the default</h3>
 * <p>A document may name an option this build no longer has — a renamed blend mode, a removed space.
 * Falling back leaves the dropdown showing something true. The alternative, a blank dropdown over a
 * value the compiler is quietly still using, is the version where the UI and the output disagree and
 * nothing says so.</p>
 */
public class SelectControl extends ValueControl<String> {

    public static final Name NAME = Name.of("selectcontrol");

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code selectcontrol } by tag. */
    public SelectControl() {
        this(ConfigDescriptor.select("", "", List.of("")), "");
    }

    /** A discrete choice from a list. */
    public static final Event<SelectControl, String> CHANGED =
            ConfigControlContracts.changed(StateTypes.STRING, "", RatePolicy.IMMEDIATE);

    public static final WidgetContract<SelectControl> CONTRACT = ConfigControlContracts.register(
            SelectControl.class, "selectcontrol", StateTypes.STRING, "", CHANGED);


    private final Dropdown dropdown = new Dropdown("");
    private final List<String> options;

    public SelectControl(ConfigDescriptor descriptor, String defaultValue) {
        super(NAME, descriptor, resolve(descriptor.options(), defaultValue));
        this.options = descriptor.options();
        addClass("__select__");
        append(dropdown);

        for (String option : options) dropdown.addOption(option);
        dropdown.select(resolve(options, defaultValue));
        dropdown.attachSelectionListener(index -> {
            if (index >= 0 && index < options.size()) commit(options.get(index));
        });
    }

    private static String resolve(List<String> options, @Nullable String value) {
        if (value != null && options.contains(value)) return value;
        return options.isEmpty() ? "" : options.get(0);
    }

    public Dropdown dropdown() {
        return dropdown;
    }

    @Override
    protected void writeToWidgets(@Nullable String value) {
        dropdown.select(resolve(options, value));
    }
}
