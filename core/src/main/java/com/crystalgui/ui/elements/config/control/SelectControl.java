package com.crystalgui.ui.elements.config.control;

import com.crystalgui.ui.elements.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.elements.Dropdown;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.ValueControl;

import javax.annotation.Nullable;
import java.util.List;

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

    /** A discrete choice from a list. */
    public static final WidgetContract<SelectControl> CONTRACT = ConfigControlContracts.register(
            SelectControl.class, "selectcontrol", StateTypes.STRING, "", RatePolicy.IMMEDIATE);


    private final Dropdown dropdown = new Dropdown("");
    private final List<String> options;

    public SelectControl(ConfigDescriptor descriptor, String defaultValue) {
        super(descriptor, resolve(descriptor.options(), defaultValue));
        this.options = descriptor.options();
        addClass("__select__");
        markAsInternal();
        addInternalChild(dropdown);

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
