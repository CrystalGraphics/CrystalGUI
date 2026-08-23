package com.crystalgui.ui.elements;

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

    /**
     * The selection, not the label.
     *
     * <p>{@link Button#writeState} would record the <em>text</em>, which here is derived from the
     * selection — restoring it would put the right words on a control that still believes nothing is
     * selected. So the selection travels as an INDEX.</p>
     *
     * <p><b>And the options travel with it.</b> This used to say the option list was "structure, rebuilt
     * by the caller", which is true of a caller that constructs the widget in Java and false of the one
     * that matters here: {@code UIDescriptionCodec} carries tag, id, classes, style, flags, focus, state
     * and children, and an option list is <em>none of those</em> — it is a field. So a dropdown crossing
     * the wire arrived with zero options, at which point {@link #select} refuses every index as
     * out-of-range and the control renders empty and unselectable. Nothing threw; it just did not
     * work.</p>
     *
     * <p>Order is load-bearing on the way back in, for the same reason it is on {@code Slider}: the
     * options must exist before an index into them can mean anything.</p>
     */
    @Override
    protected <T> void writeState(StateMap<T> out) {
        out.putList("options", options, (entry, label) -> entry.putString("label", label));
        out.putInt("selected", selectedIndex);
    }

    @Override
    protected <T> void readState(StateMap<T> in) {
        List<String> restored = in.getList("options", entry -> entry.getString("label", ""));
        if (!restored.isEmpty()) {
            clearOptions();
            for (String label : restored) addOption(label);
        }
        select(in.getInt("selected", -1));
    }
}
