package com.crystalgui.widget.config.control;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.StateType;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.overlay.Popover;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Any number of {@link ConfigDescriptor#options()}, toggled independently.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/07-mask-dropdown.png} — a closed-state button
 * reading a summary of the selection, opening a panel of checkboxes rather than a single-choice list.</p>
 *
 * <h3>Not built on {@link com.crystalgui.ui.elements.Dropdown}</h3>
 * <p>{@link com.crystalgui.ui.elements.Menu} closes on every item activation unless the item owns a
 * submenu (see {@code Menu.addItemAt}) — correct for a menu, wrong for a panel the user is meant to
 * tick several boxes in without it vanishing after the first. So this composes {@link Popover} directly
 * (the same {@code Mode.AUTO} light-dismiss/Escape behaviour, none of {@code Menu}'s
 * activation-closes-it wiring) with a plain column of real {@link Checkbox} rows — nothing here needed
 * to fight what {@code Menu} already does on purpose for everyone else.</p>
 *
 * <h3>The summary line is read back OUT of the checkboxes, never kept as a separate source of truth</h3>
 * <p>Recomputed from whichever boxes are checked whenever any one of them changes — the same reason
 * {@code NodePort.typeColor()} reads a colour back out of the cascade rather than duplicating it: two
 * places that are supposed to agree will eventually disagree, and only one of them can be at fault
 * quietly.</p>
 */
public class MaskControl extends ValueControl<Set<String>> {

    public static final Name NAME = Name.of("maskcontrol");

    /**
     * The wire form of a mask is a LIST, because a wire format has no set -- and it re-reads into one,
     * so two descriptions of the same mask hash the same only if the control iterates deterministically.
     * That is why it keeps a {@code LinkedHashSet}.
     */
    private static final StateType<Set<String>> FLAGS =
            new StateType<Set<String>>() {
                @Override public <T> void put(StateMap<T> out, String key,
                                              Set<String> value) {
                    StateTypes.stringListUnder("f").put(out, key,
                            new ArrayList<>(value == null ? Set.of() : value));
                }
                @Override public <T> Set<String> get(StateMap<T> in,
                                                               String key, Set<String> fallback) {
                    List<String> read =
                            StateTypes.stringListUnder("f").get(in, key, List.of());
                    return read.isEmpty() ? fallback : new LinkedHashSet<>(read);
                }
            };

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code maskcontrol } by tag. */
    public MaskControl() {
        this(ConfigDescriptor.mask("", "", List.of()), null);
    }

    /** A discrete set of flags: every change travels. */
    public static final Event<MaskControl, Set<String>> CHANGED =
            ConfigControlContracts.changed(FLAGS, Set.of(), RatePolicy.IMMEDIATE);

    public static final WidgetContract<MaskControl> CONTRACT = ConfigControlContracts.register(
            MaskControl.class, "maskcontrol", FLAGS, Set.of(), CHANGED);


    public static final String TOGGLE_CLASS = "__toggle__";
    public static final String CHEVRON_CLASS = "__chevron__";
    public static final String PANEL_CLASS = "__mask-panel__";
    public static final String ROW_CLASS = "__mask-row__";

    private final List<String> options;
    private final Button toggle;
    private final Popover panel = new Popover();
    private final Map<String, Checkbox> boxes = new LinkedHashMap<>();

    public MaskControl(ConfigDescriptor descriptor, @Nullable Set<String> defaultValue) {
        super(NAME, descriptor, defaultValue == null ? Set.of() : Set.copyOf(defaultValue));
        this.options = descriptor.options();
        addClass("__mask__");
        toggle = new Button("");
        toggle.addClass(TOGGLE_CLASS);
        UINode chevron = new UINode();
        chevron.addClass(CHEVRON_CLASS);
        toggle.setPostIcon(chevron);
        toggle.attachListener(() -> {
            if (panel.isOpen()) {
                panel.hide();
            } else {
                UIDocument window = document();
                if (window == null) return;
                panel.showFor(toggle, toggle);
            }
        });

        panel.addClass(PANEL_CLASS);
        for (String option : options) {
            Checkbox box = new Checkbox(option);
            box.addClass(ROW_CLASS);
            box.attachListener(checked -> onBoxToggled());
            boxes.put(option, box);
            panel.append(box);
        }
        append(panel);
        append(toggle);

        writeToWidgets(getValue());
    }

    private void onBoxToggled() {
        Set<String> selected = new LinkedHashSet<>();
        for (Map.Entry<String, Checkbox> entry : boxes.entrySet()) {
            if (entry.getValue().isChecked()) selected.add(entry.getKey());
        }
        applySummary(selected);
        commit(selected);
    }

    @Override
    protected void writeToWidgets(@Nullable Set<String> value) {
        Set<String> resolved = value == null ? Set.of() : value;
        for (Map.Entry<String, Checkbox> entry : boxes.entrySet()) {
            entry.getValue().setChecked(resolved.contains(entry.getKey()));
        }
        applySummary(resolved);
    }

    /** Unity's own three cases: nothing, everything, or a comma list — read back from the boxes rather
     * than trusted as whatever was last written, so a stale summary can never survive a real toggle. */
    private void applySummary(Set<String> selected) {
        String summary;
        if (selected.isEmpty()) {
            summary = "Nothing";
        } else if (!options.isEmpty() && selected.size() == options.size()) {
            summary = "Everything";
        } else {
            summary = String.join(", ", selected);
        }
        toggle.setText(summary);
    }

    /** The closed-state button, for a host that needs to reach the widget directly. */
    public Button toggle() {
        return toggle;
    }

    /** The panel of checkboxes, for a test that needs to reach past the summary text. */
    public Popover panel() {
        return panel;
    }
}
