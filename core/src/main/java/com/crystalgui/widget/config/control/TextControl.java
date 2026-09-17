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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.ui.dom.ChildList;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;

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
 * <p><b>With {@link ConfigDescriptor#suggestions()}</b> it offers values under the field as it is typed in — by
 * prefix, then containing what was typed — and the arrows, Enter and Escape drive the list while the caret stays
 * in the field. Any text is still accepted. Opening the field lists everything, so the offer can be browsed.</p>
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

    /** The arrow inside a field that suggests: what says there is a list, and a press opens it. */
    public static final String SUGGEST_ARROW_CLASS = "__suggest-arrow__";

    /** On the divider between two suggestion groups. */
    public static final String SUGGESTION_SEPARATOR_CLASS = "__suggestion-separator__";

    /** A divider's key in the list, by the group it ends. */
    private record Divider(int afterGroup) {
    }

    @Nullable
    private final Supplier<? extends List<? extends Collection<String>>> suggestionSource;

    /** Built only for a field that suggests. */
    @Nullable
    private final Popover suggestions;

    /** THE LIST SCROLLS, however long the offer: a machine's installed families run to hundreds. */
    @Nullable
    private final ScrollerView suggestionScroller;

    /** Rows kept by name, so a keystroke that narrows the list rebuilds nothing it still shows. */
    @Nullable
    private final ChildList.Keyed<Object, UIElement> suggestionRows;

    private final List<String> listed = new ArrayList<>();
    private int active = -1;

    public TextControl(ConfigDescriptor descriptor, String defaultValue) {
        super(NAME, descriptor, defaultValue);
        this.validator = descriptor.validator();
        addClass("__text__");
        append(field);
        field.setText(defaultValue == null ? "" : defaultValue);
        if (descriptor.commitsWhileTyping()) field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        if (descriptor.placeholder() != null) field.setPlaceholder(descriptor.placeholder()).setPlaceholderShownUnfocused(true);

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

        suggestionSource = descriptor.suggestions();
        if (suggestionSource == null) {
            suggestions = null;
            suggestionScroller = null;
            suggestionRows = null;
            return;
        }
        // THE CHIP PROMPT'S LIST, and its look: one way to be offered names in the kit. @see ClassChips
        // AUTO, LIGHT-DISMISSED: a press on something that takes no focus never blurs the field, so closing on
        // blur alone left the list open. The field is its invoker, so a press on the field keeps it.
        // NO FOCUS RESTORE: the list never takes focus, so closing it on blur handed focus straight back to the
        // field, whose focus reopened the list -- the first click away did nothing.
        suggestions = new Popover().restoresFocus(false);
        suggestions.setMode(Popover.Mode.AUTO);
        suggestions.addClass(ClassChips.SUGGESTIONS_CLASS);
        suggestionScroller = new ScrollerView();
        UIElement rows = new UIElement();
        suggestionScroller.append(rows);
        suggestions.append(suggestionScroller);
        suggestionRows = new ChildList.Keyed<>(rows, this::suggestionRow);
        append(suggestions);
        addClass("__suggests__");
        UIElement arrow = new UIElement();
        arrow.addClass(SUGGEST_ARROW_CLASS);
        arrow.setHitTest(true);
        arrow.onMouseDown.attachListener((element, event) -> {
            event.preventDefault();
            event.stopPropagation();
            if (suggestions.isOpen()) {
                suggestions.hide();
                return;
            }
            UIDocument window = document();
            if (window != null) window.focus().requestPointerFocus(field);
            refreshSuggestions("");
        }, false, true);
        append(arrow);
        field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        field.onFocus.attachListener((element, event) -> refreshSuggestions(""), false, true);
        field.onBlur.attachListener((element, event) -> suggestions.hide(), false, true);
        field.onKeyDown.attachListener((element, event) -> {
            if (handleSuggestionKey(event)) {
                event.stopPropagation();
                event.preventDefault();
            } else if (event instanceof KeyboardEvent.Down down && isTyping(down.getKeyCode())) {
                // Filtered AFTER the keystroke lands, which the field's own listener does this same frame.
                field.document().animation().afterLayout(this, delta -> {
                    if (field.isFocused()) refreshSuggestions(field.getText());
                    return false;
                });
            }
        }, false, true);
    }

    private static boolean isTyping(int key) {
        return key != CgKeyCodes.KEY_UP && key != CgKeyCodes.KEY_DOWN && key != CgKeyCodes.KEY_ESCAPE
                && key != CgKeyCodes.KEY_RETURN && key != CgKeyCodes.KEY_TAB;
    }

    private boolean handleSuggestionKey(KeyboardEvent event) {
        if (suggestions == null) return false;
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
        if (key == CgKeyCodes.KEY_RETURN && open && active >= 0) {
            accept(listed.get(active));
            return true;
        }
        return false;
    }

    /** Takes a suggestion as the value, as typing it and pressing Enter would. */
    private void accept(String suggestion) {
        field.setText(suggestion);
        commit(suggestion);
        if (suggestions != null) suggestions.hide();
    }

    /**
     * What is offered for {@code typed}, group by group: everything for nothing typed, else by prefix and then
     * containing it. A group nothing in matches is empty.
     */
    public List<List<String>> suggestionsFor(String typed) {
        List<List<String>> out = new ArrayList<>();
        if (suggestionSource == null) return out;
        String query = typed.trim().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (Collection<String> group : suggestionSource.get()) {
            List<String> prefix = new ArrayList<>();
            List<String> contains = new ArrayList<>();
            for (String name : group) {
                if (!seen.add(name)) continue;
                String folded = name.toLowerCase(Locale.ROOT);
                if (query.isEmpty() || folded.startsWith(query)) prefix.add(name);
                else if (folded.contains(query)) contains.add(name);
            }
            prefix.addAll(contains);
            out.add(prefix);
        }
        return out;
    }

    private void refreshSuggestions(String typed) {
        if (suggestions == null || suggestionRows == null) return;
        listed.clear();
        List<Object> keys = new ArrayList<>();
        List<List<String>> groups = suggestionsFor(typed);
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g).isEmpty()) continue;
            // A DIVIDER BETWEEN TWO GROUPS THAT BOTH SHOW, never above the first or after the last.
            if (!listed.isEmpty()) keys.add(new Divider(g));
            keys.addAll(groups.get(g));
            listed.addAll(groups.get(g));
        }
        suggestionRows.show(keys);
        setActive(-1);
        if (listed.isEmpty()) {
            suggestions.hide();
        } else if (!suggestions.isOpen() && document() != null) {
            suggestions.showFor(field, field);
        }
    }

    private UIElement suggestionRow(Object key) {
        if (key instanceof Divider) {
            UIElement divider = new UIElement();
            divider.addClass(SUGGESTION_SEPARATOR_CLASS);
            return divider;
        }
        String name = (String) key;
        UIText row = new UIText(name);
        row.addClass(ClassChips.SUGGESTION_CLASS);
        row.setHitTest(true);
        row.onMouseDown.attachListener((element, event) -> {
            accept(name);
            event.preventDefault();
        }, false, true);
        return row;
    }

    private void setActive(int index) {
        if (suggestions == null || suggestionRows == null) return;
        String was = active >= 0 && active < listed.size() ? listed.get(active) : null;
        active = index;
        String now = active >= 0 && active < listed.size() ? listed.get(active) : null;
        if (was != null && suggestionRows.get(was) != null) suggestionRows.get(was).removeClass(ClassChips.ACTIVE_CLASS);
        UIElement row = now == null ? null : suggestionRows.get(now);
        if (row == null) return;
        row.addClass(ClassChips.ACTIVE_CLASS);
        // THE ARROWS KEEP THE CHOICE IN VIEW, as any list scrolls to its focused row.
        if (row.box() != null) row.box().scrollIntoView();
    }

    /** The list under a field that suggests, or null for one that does not. */
    @Nullable
    public Popover suggestionList() {
        return suggestions;
    }

    public TextField field() {
        return field;
    }

    /** Text typed and not yet landed. */
    @Override
    public boolean isEditing() {
        return field.hasPendingEdit();
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
