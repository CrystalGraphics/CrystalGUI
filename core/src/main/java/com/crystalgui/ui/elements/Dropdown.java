package com.crystalgui.ui.elements;

import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.EventKind;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A select-style control: a button showing the current choice, which opens a {@link Menu} of options —
 * the web's {@code <select>}.
 *
 * <pre>
 * Dropdown quality = new Dropdown("Quality");
 * parent.addChild(quality);
 * quality.addOption("Low").addOption("Medium").addOption("High");
 * quality.select(1);
 * quality.attachSelectionListener(index -> ...);
 * </pre>
 *
 * <h3>A thin composition, on purpose</h3>
 * <p>Everything hard already exists: {@link Popover} does dismissal and placement, {@link Menu} does the
 * item list and the keyboard. This class only owns the selection and keeps the label in step, which is
 * the entire difference between a dropdown and a menu — a menu <em>does</em> things, a dropdown
 * <em>remembers</em> one.</p>
 *
 * <p>The menu is an <b>internal child</b>, promoted to the top layer when open — the same arrangement
 * {@code Dialog}'s backdrop uses. It has to be in the tree to be promoted at all, and being internal keeps
 * it out of public traversal and out of {@code UIDescriptionCodec}, which rebuilds it from the options
 * instead.</p>
 */
public class Dropdown extends Button {

    /**
     * The option labels.
     *
     * <p>Written as {@code [{label: ...}]} rather than a bare array because that is what has always
     * been on the wire, and the description is content-hashed -- changing the shape changes the hash of
     * every description holding a dropdown.</p>
     */
    public static final State<Dropdown, java.util.List<String>> OPTIONS =
            State.of("options", StateTypes.stringListUnder(EventKind.PAYLOAD_LABEL),
                    Dropdown::getOptions,
                    // Cleared and refilled, and GUARDED ON NON-EMPTY exactly as the hand-written
                    // readState was: an absent options list means "leave what is there" rather than
                    // "empty the dropdown", which is what an older peer's description looks like.
                    (dropdown, labels) -> {
                        if (labels == null || labels.isEmpty()) return;
                        dropdown.clearOptions();
                        for (String label : labels) dropdown.addOption(label);
                    },
                    java.util.List.of());

    public static final State<Dropdown, Integer> SELECTED =
            State.of("selected", StateTypes.INT, Dropdown::getSelectedIndex, Dropdown::select, -1);

    /**
     * What was chosen. {@code plan_ui_rewrite.md} M1 -- a dropdown could not tell a server ANYTHING
     * before this, which is the sharpest of the E-series findings: the one widget whose entire purpose
     * is to answer a question had no way to give the answer.
     */
    public static final Event<Dropdown, Integer> SELECTION = Event.of(EventKind.SELECT,
            (dropdown, sink) -> dropdown.onSelectionChanged.connect(sink::accept),
            new Event.Payload<Integer>() {
                @Override public <T> void write(StateMap<T> out, Integer value) {
                    out.putInt(EventKind.PAYLOAD_INDEX, value);
                }
                @Override public <T> Integer read(StateMap<T> in) {
                    return in.getInt(EventKind.PAYLOAD_INDEX, -1);
                }
            }, RatePolicy.IMMEDIATE);

    /** Options before the index, or the index is one into a list that is not there yet. */
    public static final WidgetContract<Dropdown> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Dropdown.class, "dropdown")
                    .state(OPTIONS)
                    .state(SELECTED)
                    .event(SELECTION)
                    .build());

    public static final String MENU_CLASS = "__menu__";
    public static final String CHEVRON_CLASS = "__chevron__";

    /** Fires with the newly selected index. Never fires for a re-selection of the same index. */
    public final Signal.Value<Integer> onSelectionChanged = new Signal.Value<>();

    @Getter
    private final Menu menu = new Menu();

    private final List<String> options = new ArrayList<>();
    @Getter
    private int selectedIndex = -1;

    /** Shown while nothing is selected — a {@code <select>} with no selection has to say something. */
    private final String placeholder;

    public Dropdown(String placeholder) {
        super(placeholder == null ? "" : placeholder);
        this.placeholder = placeholder == null ? "" : placeholder;

        menu.addClass(MENU_CLASS);
        addInternalChild(menu);

        // The closed-state marker, via Button's post-icon slot: `dropdown { justify-content:
        // space-between }` in default.css has had nothing to space against until now, so a dropdown
        // and a text field differed only by face colour. `overlay: shape("chevron-down")` in CSS draws
        // it — this constructor only claims the slot.
        UIElement chevron = new UIElement();
        chevron.addClass(CHEVRON_CLASS);
        setPostIcon(chevron);

        // Toggle rather than open: pressing the button of an open dropdown should shut it. Light dismiss
        // deliberately spares the invoker (see UIWindow.lightDismiss), so without this the press would be
        // ignored and the menu would look stuck.
        attachListener(() -> {
            if (menu.isOpen()) menu.hide();
            else menu.showFor(this, this);
        });
    }

    // ── Options ─────────────────────────────────────────────────────────────

    public Dropdown addOption(String label) {
        int index = options.size();
        options.add(label);
        menu.addItem(label).attachListener(() -> select(index));
        // The option list is state -- writeState says so -- so adding to it has to be announced.
        // Nothing else on this path would: applyLabel() is not called (the shown label does not
        // change when an option is appended to a non-empty list), so an option added after the
        // window opened simply never reached the far side.
        notifyStateChanged();
        return this;
    }

    public Dropdown addOptions(String... labels) {
        for (String label : labels) addOption(label);
        return this;
    }

    public void clearOptions() {
        options.clear();
        menu.clearItems();
        selectedIndex = -1;
        applyLabel();
        notifyStateChanged();
    }

    /** The options in order. Unmodifiable. */
    public List<String> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public int getOptionCount() {
        return options.size();
    }

    // ── Selection ───────────────────────────────────────────────────────────

    /** Selects by index. Out-of-range and unchanged indices are ignored, so callers need not check. */
    public Dropdown select(int index) {
        if (index < 0 || index >= options.size() || index == selectedIndex) return this;
        if (selectedIndex >= 0) menu.getItems().get(selectedIndex).setSelected(false);
        selectedIndex = index;
        menu.getItems().get(selectedIndex).setSelected(true);
        applyLabel();
        // Explicit rather than relying on applyLabel: that reaches notifyStateChanged only through
        // the LABEL changing, so two options spelled the same way would move the selection and
        // announce nothing.
        notifyStateChanged();
        onSelectionChanged.emit(index);
        return this;
    }

    /** Selects by label — the first exact match. Ignored when nothing matches. */
    public Dropdown select(String label) {
        return select(options.indexOf(label));
    }

    /** The selected label, or {@code null} while nothing is selected. */
    public String getSelectedOption() {
        return selectedIndex < 0 ? null : options.get(selectedIndex);
    }

    public Dropdown attachSelectionListener(Signal.Value.Listener<Integer> action) {
        onSelectionChanged.connect(action);
        return this;
    }

    private void applyLabel() {
        setText(selectedIndex < 0 ? placeholder : options.get(selectedIndex));
    }

}
