package com.crystalgui.widget.config.control;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgKeyCodes;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/**
 * A list of names as chips, with a prompt that adds more — a node's classes, a set of tags.
 *
 * <pre>{@code
 * ClassChips classes = new ClassChips(ConfigDescriptor.of("classes", "classes", Kind.ARRAY), List.of("title"));
 * classes.setSuggestions(() -> sheetClassNames());     // what the prompt completes from
 * classes.setFlagged(name -> !sheetClassNames().contains(name));   // drawn as a warning
 * classes.bind(fields.classes(node));
 * }</pre>
 *
 * <p>The prompt is Chromium DevTools' {@code .cls} pane ({@code ClassesPaneWidget.ts}): it completes from
 * the suggestions by prefix, several names separated by spaces go in at once, Enter or Tab takes the
 * highlighted suggestion or else what was typed. A chip's cross removes it, and Backspace in an empty prompt
 * removes the last one — Unity UI Builder's class list.</p>
 *
 * <ul>
 *   <li>A name the {@linkplain #setAccepts acceptance test} refuses is never added, typed or suggested.</li>
 *   <li>The value is the whole list in order; a name already present is not added twice.</li>
 * </ul>
 */
public class ClassChips extends ValueControl<List<String>> {

    public static final Name NAME = Name.of("classchips");

    public static final String CHIPS_CLASS = "__chips__";
    public static final String CHIP_CLASS = "__chip__";
    public static final String CHIP_LABEL_CLASS = "__chip-label__";
    public static final String REMOVE_CLASS = "__chip-remove__";
    public static final String FLAGGED_CLASS = "__flagged__";
    public static final String PROMPT_CLASS = "__chip-prompt__";
    public static final String SUGGESTIONS_CLASS = "__chip-suggestions__";
    public static final String SUGGESTION_CLASS = "__chip-suggestion__";
    public static final String ACTIVE_CLASS = "__active__";
    public static final String HINT_CLASS = "__chip-hint__";

    /** How many suggestions are listed at once. */
    private static final int MAX_SUGGESTIONS = 12;

    /** Every change travels as it happens: an add or a remove is one discrete act. */
    public static final Event<ClassChips, List<String>> CHANGED =
            ConfigControlContracts.changed(StateTypes.stringListUnder("c"), List.of(), RatePolicy.IMMEDIATE);

    public static final WidgetContract<ClassChips> CONTRACT = ConfigControlContracts.register(
            ClassChips.class, "classchips", StateTypes.stringListUnder("c"), List.of(), CHANGED);

    private final UIElement chips = new UIElement();
    private final TextField prompt = new TextField();
    private final Popover suggestions = new Popover();
    /** What an empty well says. A field's own placeholder draws only while it has focus, which is too late to invite typing. */
    private final UIText hint = new UIText("Add class…");
    private final List<String> listed = new ArrayList<>();
    private int active = -1;

    private Supplier<? extends Collection<String>> suggestionSource = List::of;
    private Predicate<String> flagged = name -> false;
    private Predicate<String> accepts = name -> true;

    /** The no-argument constructor the registry's factory needs. */
    public ClassChips() {
        this(ConfigDescriptor.of("", "", ConfigDescriptor.Kind.ARRAY), List.of());
    }

    public ClassChips(ConfigDescriptor descriptor, @Nullable List<String> initial) {
        super(NAME, descriptor, initial == null ? List.of() : List.copyOf(initial));
        addClass("__classchips__");
        chips.addClass(CHIPS_CLASS);
        prompt.addClass(PROMPT_CLASS);
        prompt.setPlaceholder("Add class");
        prompt.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        prompt.attachListener(text -> refreshSuggestions());
        prompt.onKeyDown.attachListener((element, event) -> {
            if (handleKey(event)) {
                event.stopPropagation();
                event.preventDefault();
            }
        }, false, true);
        prompt.onBlur.attachListener((element, event) -> {
            suggestions.hide();
            showHint();
        }, false, true);
        prompt.onFocus.attachListener((element, event) -> showHint(), false, true);
        hint.addClass(HINT_CLASS);
        hint.set(Attribute.HIT_TEST, false);

        suggestions.setMode(Popover.Mode.MANUAL);
        suggestions.addClass(SUGGESTIONS_CLASS);

        chips.append(prompt);
        chips.append(hint);
        append(chips);
        append(suggestions);
        writeToWidgets(getValue());
    }

    /** What the prompt completes from, asked afresh on each keystroke. */
    public ClassChips setSuggestions(Supplier<? extends Collection<String>> source) {
        this.suggestionSource = source == null ? List::of : source;
        return this;
    }

    /** Which chips are drawn as a warning — a class no sheet mentions. */
    public ClassChips setFlagged(Predicate<String> flagged) {
        this.flagged = flagged == null ? name -> false : flagged;
        writeToWidgets(getValue());
        return this;
    }

    /** Which names may be added at all. */
    public ClassChips setAccepts(Predicate<String> accepts) {
        this.accepts = accepts == null ? name -> true : accepts;
        return this;
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    /** Adds every name in {@code text}, split on whitespace, that is accepted and not already present. */
    public void add(String text) {
        List<String> next = new ArrayList<>(current());
        for (String name : text.trim().split("\\s+")) {
            if (name.isEmpty() || next.contains(name) || !accepts.test(name)) continue;
            next.add(name);
        }
        if (!next.equals(current())) change(next);
    }

    public void remove(String name) {
        List<String> next = new ArrayList<>(current());
        if (next.remove(name)) change(next);
    }

    /** Commits {@code next} and redraws: a commit leaves the widgets alone, and the chips are drawn from the value. */
    private void change(List<String> next) {
        commit(List.copyOf(next));
        writeToWidgets(getValue());
    }

    private boolean handleKey(KeyboardEvent event) {
        int key = event.getKeyCode();
        boolean open = suggestions.isOpen() && !listed.isEmpty();
        if (key == CgKeyCodes.KEY_DOWN && open) {
            setActive((active + 1) % listed.size());
            return true;
        }
        if (key == CgKeyCodes.KEY_UP && open) {
            setActive(active <= 0 ? listed.size() - 1 : active - 1);
            return true;
        }
        if (key == CgKeyCodes.KEY_ESCAPE && suggestions.isOpen()) {
            suggestions.hide();
            return true;
        }
        if (key == CgKeyCodes.KEY_RETURN || (key == CgKeyCodes.KEY_TAB && open && active >= 0)) {
            if (open && active >= 0) {
                accept(listed.get(active));
            } else if (!prompt.getText().isBlank()) {
                add(prompt.getText());
                prompt.setText("");
                suggestions.hide();
            } else {
                return false;
            }
            return true;
        }
        if (key == CgKeyCodes.KEY_BACK && prompt.getText().isEmpty() && !current().isEmpty()) {
            List<String> names = current();
            remove(names.get(names.size() - 1));
            return true;
        }
        return false;
    }

    /** Takes a suggestion in place of the last word typed. */
    private void accept(String suggestion) {
        String text = prompt.getText();
        int lastSpace = text.lastIndexOf(' ');
        add((lastSpace < 0 ? "" : text.substring(0, lastSpace + 1)) + suggestion);
        prompt.setText("");
        suggestions.hide();
    }

    // ── Suggestions ─────────────────────────────────────────────────────────

    /** The suggestions for what is being typed: by prefix of the last word, then containing it. */
    public List<String> suggestionsFor(String text) {
        String word = text.substring(text.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
        List<String> prefix = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        if (word.isEmpty()) return prefix;
        List<String> present = current();
        for (String name : suggestionSource.get()) {
            if (present.contains(name) || !accepts.test(name) || prefix.contains(name) || contains.contains(name)) continue;
            String folded = name.toLowerCase(Locale.ROOT);
            if (folded.equals(word)) continue;
            if (folded.startsWith(word)) prefix.add(name);
            else if (folded.contains(word)) contains.add(name);
        }
        prefix.sort(String::compareTo);
        contains.sort(String::compareTo);
        prefix.addAll(contains);
        return prefix.size() > MAX_SUGGESTIONS ? new ArrayList<>(prefix.subList(0, MAX_SUGGESTIONS)) : prefix;
    }

    private void refreshSuggestions() {
        listed.clear();
        listed.addAll(suggestionsFor(prompt.getText()));
        suggestions.removeAll();
        active = listed.isEmpty() ? -1 : 0;
        for (int i = 0; i < listed.size(); i++) {
            String name = listed.get(i);
            UIText row = new UIText(name);
            row.addClass(SUGGESTION_CLASS);
            if (i == active) row.addClass(ACTIVE_CLASS);
            row.onMouseDown.attachListener((element, event) -> {
                accept(name);
                event.preventDefault();
            }, false, true);
            suggestions.append(row);
        }
        if (listed.isEmpty()) {
            suggestions.hide();
        } else if (!suggestions.isOpen() && document() != null) {
            suggestions.showFor(prompt, prompt);
        }
    }

    private void setActive(int index) {
        active = index;
        List<UIElement> rows = suggestions.children();
        for (int i = 0; i < rows.size(); i++) {
            if (i == active) rows.get(i).addClass(ACTIVE_CLASS);
            else rows.get(i).removeClass(ACTIVE_CLASS);
        }
    }

    // ── Display ─────────────────────────────────────────────────────────────

    private List<String> current() {
        List<String> value = getValue();
        return value == null ? List.of() : value;
    }

    /**
     * Brings the chips in line with {@code value}, KEEPING the ones that stay: a chip is removed by a press on
     * its cross, and rebuilding every chip would replace the elements under the pointer.
     */
    @Override
    protected void writeToWidgets(@Nullable List<String> value) {
        List<String> names = value == null ? List.of() : value;
        for (UIElement child : new ArrayList<>(chips.children())) {
            if (child != prompt && child != hint && !names.contains(nameOf(child))) chips.remove(child);
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            UIElement chip = chipNamed(name);
            if (chip == null) {
                chip = chip(name);
                chips.insertAt(i, chip);
            } else if (chips.indexOf(chip) != i) {
                chip.moveTo(chips, i);
            }
            if (flagged.test(name)) chip.addClass(FLAGGED_CLASS);
            else chip.removeClass(FLAGGED_CLASS);
        }
        showHint();
    }

    private void showHint() {
        List<String> names = getValue();
        hint.setDisplayed((names == null || names.isEmpty()) && !prompt.isFocused() && prompt.getText().isEmpty());
    }

    @Nullable
    private UIElement chipNamed(String name) {
        for (UIElement child : chips.children()) {
            if (child != prompt && child != hint && name.equals(nameOf(child))) return child;
        }
        return null;
    }

    @Nullable
    private static String nameOf(UIElement chip) {
        return chip.hasClass(CHIP_CLASS) && chip.children().get(0) instanceof UIText label ? label.getText() : null;
    }

    private UIElement chip(String name) {
        UIElement chip = new UIElement();
        chip.addClass(CHIP_CLASS);
        UIText label = new UIText(name);
        label.addClass(CHIP_LABEL_CLASS);
        UIElement cross = new UIElement();
        cross.addClass(REMOVE_CLASS);
        cross.onMouseDown.attachListener((element, event) -> {
            remove(name);
            event.preventDefault();
        }, false, true);
        chip.append(label);
        chip.append(cross);
        return chip;
    }

    /** The add prompt, for focus and for a test. */
    public TextField prompt() {
        return prompt;
    }

    /** The suggestion list. */
    public Popover suggestionList() {
        return suggestions;
    }

    /** The chips' names in order, as drawn. */
    public List<String> chipNames() {
        List<String> out = new ArrayList<>();
        for (UIElement child : chips.children()) {
            String name = nameOf(child);
            if (name != null) out.add(name);
        }
        return out;
    }

    /** Whether the chip for {@code name} is drawn flagged. */
    public boolean isFlagged(String name) {
        UIElement chip = chipNamed(name);
        return chip != null && chip.hasClass(FLAGGED_CLASS);
    }
}
