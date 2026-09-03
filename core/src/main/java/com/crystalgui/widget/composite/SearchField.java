package com.crystalgui.widget.composite;

import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import javax.annotation.Nullable;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.control.Button;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Attribute;

/**
 * A search box: an icon, a {@link TextField}, and a clear button that appears once there is something to
 * clear.
 *
 * <h3>General, and deliberately NOT a {@code ConfigControl}</h3>
 * <p>Worth stating because it is the obvious mistake: <b>a {@code ConfigControl} edits a value; a search
 * field filters a view.</b> They are different jobs, and the config kit's row height, label column and
 * {@code .__config-control__} cascade are all built for the first — a search box inheriting them would be
 * laid out as a form row inside things that are not forms. Using a {@code SearchField} <em>inside</em> a
 * configurator panel is completely fine; that is composition, not membership.</p>
 *
 * <p>Consumers this exists for beyond the create menu: a command palette over
 * {@code core.command.CommandRegistry}, searching a configurator's settings, and filtering a
 * {@code ListView} or {@code TableView}.</p>
 *
 * <h3>The parts are internal children</h3>
 * <p>{@code __icon__} / {@code __field__} / {@code __clear__}, so a theme draws the magnifier and the
 * clear affordance without this class naming a single colour or size. {@link #acceptsPublicChildren()} is
 * false for the same reason every other composite's is.</p>
 */
public class SearchField extends UIElement {

    public static final Name NAME = Name.of("searchfield");

    public static final State<SearchField, String> TEXT =
            State.<SearchField, String>of("text", StateTypes.STRING,
                            SearchField::getText, SearchField::setText, "")
                    .omittedWhen("");

    public static final State<SearchField, String> PLACEHOLDER =
            State.<SearchField, String>of("placeholder", StateTypes.STRING,
                            SearchField::getPlaceholder, SearchField::setPlaceholder, "")
                    .omittedWhen("");

    /** The "no results" state, which is the server's answer to a query and not the client's. */
    public static final State<SearchField, Boolean> NOT_FOUND =
            State.<SearchField, Boolean>of("notFound", StateTypes.BOOL,
                            SearchField::isNotFound, SearchField::setNotFound, false)
                    .omittedWhen(false);

    /** Every keystroke, debounced -- a search box is the archetype the policy was written for. */
    public static final Event<SearchField, String> QUERY = Event.of("text",
            // onQueryChanged is a bare Action, so the text is read at emit time rather than carried.
            (field, sink) -> field.onQueryChanged.connect(() -> sink.accept(field.getText())),
            new Event.Payload<String>() {
                @Override public <T> void write(StateMap<T> out, String value) {
                    out.putString("text", value == null ? "" : value);
                }
                @Override public <T> String read(StateMap<T> in) {
                    return in.getString("text", "");
                }
            }, RatePolicy.TYPING);

    public static final WidgetContract<SearchField> CONTRACT = WidgetContracts.register(
            WidgetContract.of(SearchField.class, "searchfield")
                    .state(PLACEHOLDER)
                    .state(TEXT)
                    .state(NOT_FOUND)
                    .event(QUERY)
                    .primary(TEXT)
                    .build());


    public static final String SEARCH_FIELD_PART = "__search-field__";
    public static final String ICON_PART = "icon";
    public static final String FIELD_PART = "field";
    public static final String CLEAR_PART = "clear";

    /**
     * The toggle strip — {@code Cc} / {@code W} / {@code .*} and whatever else a consumer mounts.
     *
     * <p><b>Inside the border, not beside it.</b> IntelliJ's focus ring encloses the magnifier, the text,
     * the clear button and the toggles as one control, and the text may only occupy what is left between
     * them. That is why this belongs to {@code SearchField} rather than to the bar around it: the box
     * already exists here, and a strip mounted outside is a second control that merely looks attached
     * until something resizes.</p>
     */
    public static final String OPTIONS_PART = "options";

    /** On the whole field while its query finds nothing — IntelliJ reds the text. */
    public static final String NOT_FOUND_CLASS = "__not-found__";

    /**
     * On the box while the caret is in it — the focus ring for the whole control.
     *
     * <p>Maintained here rather than written as {@code :focus-within}, which this engine does not have.
     * An unknown pseudo-class is not ignored: it <b>poisons the sheet</b>, and one such rule broke six
     * unrelated layout tests in panels that had never heard of a search box. The supported set is on
     * {@code PseudoClasses}; anything outside it has to be a class somebody maintains.</p>
     */
    public static final String FOCUSED_CLASS = "__focused__";

    private final ShadowRoot shadow;

    private final UIElement icon = new UIElement();
    private final TextField field = new TextField();
    private final UIElement clear = new UIElement();

    /**
     * Created on the first {@link #addOption}, never before.
     *
     * <p>Not a permanent child hidden with {@code display: none}: the row carries {@code gap-all}, and a
     * hidden child still counts for a gap, so every existing consumer — the palette, the create menu, the
     * Blackboard — silently gained a few pixels and the Blackboard's overflow tests caught it. Not
     * existing is the only spelling of "costs nothing" that is actually free.</p>
     */
    @Nullable
    private UIElement options;

    /** Fires on every keystroke — see the constructor's note on why this is not deferred to Enter. */
    public final Signal.Action onQueryChanged = new Signal.Action();

    public SearchField() {
        super(NAME);
        this.shadow = attachShadow();
        addClass(SEARCH_FIELD_PART);
        // NOT markAsInternal(). That marks THIS element internal, and an internal element is skipped by the
        // style match walk -- so every `.__search-field__` rule silently matched nothing and the box had no
        // border, no icon, no sizing, while its children (styled by their own classes) looked fine. The
        // parts are already internal individually via addInternalChild, which is the correct half of that
        // pair; ListView's constructor carries the same warning, and MenuBarView paid for it once already.

        icon.set(Attribute.PART, ICON_PART);
        // Scenery. A press on the magnifier belongs to the field beside it, and an icon that ate the
        // click would make the leftmost pixels of the box dead.
        icon.setHitTest(false);

        field.set(Attribute.PART, FIELD_PART);
        // IMMEDIATE, not on-commit: a box that only filters on Enter is not a search box, it is a filter
        // you have to submit. Same reasoning NodeCreationMenu's own field already carried.
        field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        field.attachListener(text -> {
            refreshClear();
            onQueryChanged.emit();
        });

        // THE RING BELONGS TO THE BOX, and only the inner field can take focus -- so the box watches it.
        field.onFocus.attachListener((element, event) -> addClass(FOCUSED_CLASS), false, true);
        field.onBlur.attachListener((element, event) -> removeClass(FOCUSED_CLASS), false, true);

        clear.set(Attribute.PART, CLEAR_PART);
        clear.onMouseDown.attachListener((element, event) -> {
            setText("");
            // AND PUT THE CARET BACK. `emitMouseDown` blurs the focus owner BEFORE it dispatches, and this
            // element is not focusable, so the press cleared the query and left the box dead -- you had to
            // click into it again to type the next one, which is the opposite of what a clear button is
            // for. `requestPointerFocus` rather than `requestFocus`: this is a click, and the programmatic
            // one rings `:focus-visible`.
            UIDocument window = document();
            if (window != null) window.focus().requestPointerFocus(field);
            event.stopPropagation();
        }, false, true);

        shadow.append(icon);
        shadow.append(field);
        shadow.append(clear);
        refreshClear();
    }

    /** The field itself — for focus, key handling, and a placeholder. Exposed because the owner routes
     * arrow keys through it while keeping focus in the box; see {@code NodeCreationMenu}. */
    public TextField field() {
        return field;
    }

    public String getText() {
        return field.getText();
    }

    public SearchField setText(String text) {
        field.setText(text);
        refreshClear();
        return this;
    }

    /** The prompt shown while the box is empty. @see MenuItem#getAccelerator() on why this exists. */
    public String getPlaceholder() {
        return field.getPlaceholder();
    }

    public SearchField setPlaceholder(String placeholder) {
        field.setPlaceholder(placeholder);
        return this;
    }

    /**
     * Mounts a toggle into the strip inside the border.
     *
     * <p>A consumer that wants none of this calls it never and is untouched — there is no strip until the
     * first call.</p>
     */
    public SearchField addOption(UIElement option) {
        if (option == null) return this;
        if (options == null) {
            options = new UIElement();
            options.set(Attribute.PART, OPTIONS_PART);
            // AFTER the clear button, matching IntelliJ: the one control whose presence changes with the
            // query sits next to the text it clears, rather than beyond a fixed strip.
            shadow.append(options);
        }
        options.append(option);
        return this;
    }

    /**
     * Builds one option toggle for the strip — the {@code Cc} / {@code W} / {@code .*} shape.
     *
     * <p>Shared so the two find bars build them ONE way. Six things have to be right and none is visible in
     * a screenshot: the class the sheet draws from, the tooltip naming the accelerator, an accelerator that
     * is actually bound, the {@code __on__} class (a pseudo-class is not re-evaluated when a listener flips
     * the state), no rest background, and a hit area larger than the glyph. A second builder gets some
     * subset right and the two drift within a release — which is the same reason {@code MenuBuilder} is the
     * only thing that turns commands into menu rows.</p>
     *
     * <p>The caller owns the state and the behaviour; this owns the button.</p>
     */
    public static Button optionToggle(String styleClass, String title, String accelerator) {
        Button option = new Button("");
        option.addClass(OPTION_CLASS);
        option.addClass(styleClass);
        Tooltip.attach(option, title + "  " + accelerator);
        return option;
    }

    /** Reflects an option's on/off state. @see #optionToggle */
    public static void setOptionOn(Button option, boolean on) {
        if (on) option.addClass(OPTION_ON_CLASS);
        else option.removeClass(OPTION_ON_CLASS);
    }

    /** On every option toggle, in either find bar. */
    public static final String OPTION_CLASS = "__search-option__";

    /** On a toggle that is on. */
    public static final String OPTION_ON_CLASS = "__on__";

    /** The strip, or null until something has been mounted in it. */
    @Nullable
    public UIElement options() {
        return options;
    }

    /**
     * Marks the query as finding nothing, which reds the text.
     *
     * <p>One flag rather than two: "no results" and "that pattern will not compile" are the same thing to
     * look at, and IntelliJ draws them the same way.</p>
     */
    /** Whether the box is showing its "no results" state. Stored as a class, so read back from one. */
    public boolean isNotFound() {
        return hasClass(NOT_FOUND_CLASS);
    }

    public SearchField setNotFound(boolean notFound) {
        if (notFound) addClass(NOT_FOUND_CLASS);
        else removeClass(NOT_FOUND_CLASS);
        return this;
    }

    /** Hidden with {@code display: none} rather than dimmed: an always-present clear button is a target
     * that does nothing most of the time, and one that keeps its box would leave a gap the text could
     * never use. */
    private void refreshClear() {
        boolean any = !field.getText().isEmpty();
        // INLINE, not IMPORTANT -- the new engine may not write at that origin. @see ProgressBar.
        StyleGroup.inlinePipeline(clear.getStyle().getLayoutGroup(),
                l -> l.display(any ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** The parts are this widget's own structure — see the class note. */
}
