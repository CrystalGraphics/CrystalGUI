package com.crystalgui.widget.config.control;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.ConfigControls;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.widget.config.ValueControl;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Name;

/**
 * A repeated value: a header, a body of rows, and {@code +} / {@code −} at the bottom right.
 *
 * <p>Unity reference: the {@code Entries} list in
 * {@code docs/research/unity-inspector/03-inspector-keyword-enum.png}, the {@code Active Targets} list
 * in {@code 07-full-window.png}, and the empty-state placeholder in {@code 04-inspector-custom-function.png}.</p>
 *
 * <h3>Built directly, NOT on {@code ListView} — a deliberate change from the plan</h3>
 * <p>{@code ListView} is virtualised: it realises only the visible rows and <b>recycles</b> them
 * through {@code bind(item, index, template)}. That is exactly right for ten thousand log lines and
 * exactly wrong here, for two reasons that compound:</p>
 * <ul>
 *   <li>A config array is short — a keyword's entries, a node's ports. Virtualisation buys nothing and
 *       costs the ability to let the list size itself to its contents.</li>
 *   <li><b>A recycled row carries live controls with listeners.</b> A listener may only be attached
 *       once, so it must read its row index at call time rather than capturing it — and a control that
 *       captured its index would edit whatever entry its slot was last used for. That is the pooled
 *       gutter-arrow trap from {@code AGENTS.md}, and it keeps working right up until someone
 *       scrolls.</li>
 * </ul>
 * <p>A plain column has neither problem. If a genuinely long config array ever turns up, that is the
 * moment to reach for {@code ListView} — not before.</p>
 *
 * <h3>An empty list shows a placeholder rather than collapsing</h3>
 * <p>Unity's {@code List is Empty} row. A list that collapsed to nothing when emptied would leave the
 * {@code +} button floating with no indication of what it adds to, and would make the difference
 * between "no entries" and "no list" invisible.</p>
 */
public class ArrayControl extends ValueControl<List<Object>> {

    public static final Name NAME = Name.of("arraycontrol");

    public static final String HEAD_CLASS = "__head__";
    public static final String BODY_CLASS = "__body__";
    public static final String FOOT_CLASS = "__foot__";
    public static final String ENTRY_CLASS = "__entry__";
    public static final String EMPTY_CLASS = "__empty__";

    /**
     * The entries, each as the text its own control shows.
     *
     * <p>Text and not a typed list, because this is the one control whose value type is not fixed by
     * its class: a {@link ConfigDescriptor#element() element} descriptor says what one entry is, and a
     * {@link State} slot is declared once for the whole kind. So an entry crosses as what it reads as
     * and is coerced back by the element's own kind on arrival — faithful for the kinds a config array
     * actually holds (text, numbers, booleans, a choice, an asset path), and for anything else exactly
     * the string the entry's control was showing.</p>
     */
    public static final State<ArrayControl, List<String>> ENTRIES = State.of("entries",
            StateTypes.stringListUnder("v"),
            ArrayControl::entriesAsText, ArrayControl::setEntriesFromText, List.of());

    /** An entry was added, removed or edited. Immediate: each is a discrete action. */
    public static final Event<ArrayControl, List<String>> CHANGED = Event.of("change",
            (control, sink) -> control.changed.connect(raw -> sink.accept(control.entriesAsText())),
            new Event.Payload<List<String>>() {
                @Override public <T> void write(StateMap<T> out, List<String> raw) {
                    StateTypes.stringListUnder("v").put(out, "value", raw);
                }
                @Override public <T> List<String> read(StateMap<T> in) {
                    return StateTypes.stringListUnder("v").get(in, "value", List.of());
                }
            }, RatePolicy.IMMEDIATE);

    public static final WidgetContract<ArrayControl> CONTRACT = WidgetContracts.register(
            WidgetContract.of(ArrayControl.class, "arraycontrol")
                    .state(ENTRIES)
                    .event(CHANGED)
                    .build());

    private final UIElement body = new UIElement();
    private final UIElement foot = new UIElement();
    private final ConfigDescriptor element;
    private final List<Object> values = new ArrayList<>();

    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code arraycontrol } by tag. */
    public ArrayControl() {
        this(ConfigDescriptor.of("", "", ConfigDescriptor.Kind.ARRAY), null);
    }

    public ArrayControl(ConfigDescriptor descriptor, @Nullable List<Object> defaultValue) {
        super(NAME, descriptor, defaultValue == null ? List.of() : List.copyOf(defaultValue));
        this.element = descriptor.element() == null
                ? ConfigDescriptor.text(descriptor.id() + ".entry", "")
                : descriptor.element();
        addClass("__array__");
        UIElement head = new UIElement();
        head.addClass(HEAD_CLASS);
        UIText title = new UIText(descriptor.label());
        title.setHitTest(false);
        head.append(title);

        body.addClass(BODY_CLASS);

        Button add = new Button("+");
        add.addClass("__add__");
        add.attachListener(() -> {
            values.add(null);
            rebuild();
            commit(List.copyOf(values));
        });
        Button remove = new Button("-");
        remove.addClass("__remove__");
        remove.attachListener(() -> {
            if (values.isEmpty()) return;
            values.remove(values.size() - 1);
            rebuild();
            commit(List.copyOf(values));
        });
        foot.addClass(FOOT_CLASS);
        foot.append(add);
        foot.append(remove);

        append(head);
        append(body);
        append(foot);

        if (defaultValue != null) values.addAll(defaultValue);
        rebuild();
    }

    /** Self-labelling: the header carries the name, so a row must not add a second one. */
    @Override
    public boolean selfLabelling() {
        return true;
    }

    @Override
    protected void writeToWidgets(@Nullable List<Object> value) {
        values.clear();
        if (value != null) values.addAll(value);
        rebuild();
    }

    /**
     * Rebuilds every entry row.
     *
     * <p>Wholesale rather than incrementally, and the reason is the same one that kept this off
     * {@code ListView}: an entry's control owns a listener bound to its index, so a row that survived a
     * removal would go on editing the slot it used to be. Rebuilding is O(n) on a list that is short by
     * construction, and it cannot be wrong.</p>
     *
     * <p><b>Never called from inside an entry's own change handler</b> — only from add, remove and a
     * programmatic write. Rebuilding under a live edit would detach the control being typed into, which
     * is the widget-rebuild trap {@code AGENTS.md} records against the table header.</p>
     */
    private void rebuild() {
        body.removeAll();
        if (values.isEmpty()) {
            UIText empty = new UIText("List is Empty");
            empty.addClass(EMPTY_CLASS);
            empty.setHitTest(false);
            body.append(empty);
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            final int index = i;
            ConfigControl control = ConfigControls.create(element, values.get(i));
            if (control == null) continue;
            control.changed.connect(v -> {
                // Read the index from the capture, which is safe ONLY because this row is discarded
                // rather than recycled — see rebuild()'s note.
                if (index < values.size()) {
                    values.set(index, v);
                    commit(List.copyOf(values));
                }
            });
            UIElement entry = new UIElement();
            entry.addClass(ENTRY_CLASS);
            entry.append(control);
            body.append(entry);
        }
    }

    public int size() {
        return values.size();
    }

    /** Each entry as the text its own control shows. @see #ENTRIES */
    public List<String> entriesAsText() {
        List<String> out = new ArrayList<>(values.size());
        for (Object value : values) out.add(value == null ? "" : String.valueOf(value));
        return out;
    }

    /**
     * Replaces the entries, coercing each by the element descriptor's kind.
     *
     * <p>The coercion is here rather than in {@code ConfigControls} because only this control knows
     * what one of its entries IS. Handing a number entry's text straight to that factory yields
     * {@code 0} — silently, since it takes anything that is not a {@code Number} as zero — which is the
     * shape a wire format is worst at: a list that arrives the right length and the wrong values.</p>
     */
    public void setEntriesFromText(@Nullable List<String> entries) {
        List<Object> coerced = new ArrayList<>(entries == null ? 0 : entries.size());
        if (entries != null) for (String entry : entries) coerced.add(coerce(entry));
        setValue(coerced);
    }

    @Nullable
    private Object coerce(@Nullable String entry) {
        if (entry == null) return null;
        switch (element.kind()) {
            case NUMBER:
                try {
                    return Double.valueOf(entry);
                } catch (NumberFormatException notANumber) {
                    // NOT A FAILURE. The far side may be a version that wrote this entry as something
                    // else, and a list that is one entry odd beats a window that refuses to open.
                    return Double.valueOf(0d);
                }
            case BOOLEAN:
                return Boolean.valueOf(entry);
            default:
                return entry;
        }
    }
}
