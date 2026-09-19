package com.crystalgui.widget.config;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.control.InfoControl;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import dev.vfyjxf.taffy.style.FlexDirection;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Set;

/**
 * <b>One field: a label and a control</b> — a row of an inspector, or a cell of a toolbar.
 *
 * <p>Built by a form rather than by hand; {@link ConfigForm#prop} and {@link ConfigForm#row} return one.</p>
 *
 * <pre>{@code
 * Configurator row  = new Configurator(descriptor, control);                           // Rotation [ 45° ]
 * Configurator cell = new Configurator(Arrangement.COMPACT, descriptor, control);      // R [45°]
 * }</pre>
 *
 * <p>Unity reference: {@code docs/research/unity-inspector/01-inspector-property.png} for the row;
 * Photoshop's options bar for the cell. Ported from LDLib2's {@code Configurator}, with one change: it
 * wraps a {@link ConfigControl} rather than being its superclass, because a node's unconnected input hosts
 * the bare control with no label at all.</p>
 *
 * <ul>
 *   <li><b>The control is offered the label</b> ({@link ConfigControl#adoptLabel}), so the word beside a
 *       number is its scrub handle in either arrangement. A label nothing adopts ignores the pointer.</li>
 *   <li><b>A self-labelling control gets no label</b> — only the control knows whether it says what it is
 *       by being what it is. A colour swatch does; a number does not.</li>
 *   <li>The hint is the descriptor's tooltip. A compact cell with none shows its full label, since the
 *       cell shows only the short one; it hangs from the whole cell so it clears the bar it sits in.</li>
 * </ul>
 *
 * <h3>A row's label column is fixed-width and LEFT-aligned</h3>
 * <p>Measured off the reference, and it is the property that makes a stack of unlike controls read as a
 * form: every control starts on a common left edge regardless of how long its label is.</p>
 */
public class Configurator extends UIElement implements DataProvider {

    public static final Name NAME = Name.of("configurator");

    public static final String ROW_CLASS = "__configurator__";
    public static final String LABEL_CLASS = "__label__";
    public static final String INLINE_CLASS = "__inline__";

    @Nullable
    private UndoStack history;

    /**
     * The history this row's edits go into, so Ctrl+Z pressed in it reaches them.
     *
     * <pre>{@code
     * form.prop(descriptor, value).editedIn(sheet.buffer().history());   // not the document's
     * }</pre>
     *
     * <p>Usually unnecessary: a row bound to a property that names its history with
     * {@link Property#editedIn} answers with that one.</p>
     *
     * <p>A command resolves outward from focus, so without this a row in a panel beside the editor answers
     * with whatever the panel is describing — which is right until a row edits something else, as a rule's
     * row does: the declaration is in the stylesheet's buffer and the undo belongs with it.</p>
     */
    public Configurator editedIn(@Nullable UndoStack history) {
        this.history = history;
        return this;
    }

    /** The history named here, else the one the bound property's edits go into. @see Property#editedIn */
    @Override
    @Nullable
    public Object getData(DataKey<?> key) {
        if (key != UiDataKeys.UNDO_STACK) return null;
        if (history != null) return history;
        return control instanceof ValueControl<?> value ? value.property().history() : null;
    }

    /** On a row holding an inline list, beside {@link #ROW_CLASS}. @see ConfigDescriptor#inlineList */
    public static final String LIST_ROW_CLASS = "__list-row__";

    /** On a {@link Arrangement#COMPACT} field, beside {@link #ROW_CLASS}. */
    public static final String COMPACT_CLASS = "__compact__";

    /** How a field lays its label and control out. */
    public enum Arrangement {
        /** A label column beside the control — a panel's row. */
        ROW,
        /** The short label in front of the control, the full one on hover — a toolbar's cell. */
        COMPACT
    }

    private final UIText label;

    /** @see #labelText */
    private final String labelText;
    private final UIElement inline = new UIElement();
    private final ConfigControl control;

    @Nullable
    private final Tooltip hint;

    /**
     * The no-argument constructor the registry's factory needs.
     *
     * <p>Over an {@link InfoControl}, not {@code null}: every method here asks the control something —
     * {@code selfLabelling()} on the very first pass.</p>
     */
    public Configurator() {
        this("", new InfoControl());
    }

    /** A row whose label is not the descriptor's — a node field labelled by its own declaration. */
    public Configurator(String labelText, ConfigControl control) {
        this(Arrangement.ROW, labelText, control);
    }

    /** A field whose label is not the descriptor's. */
    public Configurator(Arrangement arrangement, String labelText, ConfigControl control) {
        this(arrangement, labelText, control.descriptor().tooltip(), control);
    }

    public Configurator(ConfigDescriptor descriptor, ConfigControl control) {
        this(Arrangement.ROW, descriptor, control);
    }

    public Configurator(Arrangement arrangement, ConfigDescriptor descriptor, ConfigControl control) {
        this(arrangement, textFor(arrangement, descriptor), hintFor(arrangement, descriptor), control);
    }

    private Configurator(Arrangement arrangement, String labelText, @Nullable String hintText,
                         ConfigControl control) {
        super(NAME);
        this.control = control;
        addClass(ROW_CLASS);
        // A ROW AS TALL AS A LIST: its label sits against the first entry rather than centred across all of them.
        if (control.descriptor().kind() == ConfigDescriptor.Kind.ARRAY && control.descriptor().inlineList()) {
            addClass(LIST_ROW_CLASS);
        }
        if (arrangement == Arrangement.COMPACT) {
            addClass(COMPACT_CLASS);
            // The letter in front of its number with no sheet, as the toolbar it sits in is a row.
            StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));
        }

        boolean labelled = !control.selfLabelling() && labelText != null && !labelText.isEmpty();
        this.labelText = labelText == null ? "" : labelText;
        label = new UIText(labelled ? labelText : "");
        label.addClass(LABEL_CLASS);
        if (!labelled || !control.adoptLabel(label)) label.setHitTest(false);

        inline.addClass(INLINE_CLASS);
        inline.append(control);

        if (labelled) append(label);
        append(inline);

        // THE WHOLE ROW, label and control: the label is where a reader looks for what a field means, and a
        // hint only over the control answered nobody pointing at the name. A toolbar's cell is the same shape
        // for its own reason -- its control is shorter than the bar, and a hint under it would sit over the bar.
        hint = hintText == null || hintText.isEmpty() ? null : Tooltip.attach(this, hintText);
        if (hint != null) {
            // A FORM'S ROWS ARE CROSSED, not aimed at: the pointer passes over them on its way to one, and a hint
            // arriving at once lands over the next row down. A toolbar's cell is aimed at, and answers at once.
            // @see Tooltip#WAIT_CLASS
            if (arrangement != Arrangement.COMPACT) hint.addClass(Tooltip.WAIT_CLASS);
            hint.setDescription(control.descriptor().description());
        }
        builtClasses = Set.copyOf(classes());
    }

    // ── Across refills ──────────────────────────────────────────────────────
    //
    // A refilled panel hands a row it built last time back to the next fill. What the row was BUILT with is its
    // own; what a fill did to it afterwards belonged to that fill, and must not survive into the next.

    /** The classes the row was built with — the ones {@link #reclaim} keeps. */
    private final Set<String> builtClasses;

    private final ConnectionGroup decorations = new ConnectionGroup();

    /**
     * Where a fill hangs what it attaches to this row, so the next refill can take it off again.
     *
     * <pre>{@code
     * Configurator row = form.prop(descriptor, value);
     * row.decorations().add(row.label().onMouseDown.subscribe(this::scrub, false, true));
     * row.decorations().add(row.whileConnected(() -> source.changed.connect(this::follow)));
     * }</pre>
     *
     * <p>A class or a display state set on the row is undone for you. A listener, a watch or a hook is not —
     * the row cannot know what it was — so it goes in here, or it stays on a row the next fill reuses and acts
     * on whatever the previous one was describing.</p>
     */
    public ConnectionGroup decorations() {
        return decorations;
    }

    /**
     * Undoes what the last fill did to this row, before a refill hands it out again: its decorations, the classes
     * it added, a hidden or inert state and a history it named.
     */
    void reclaim() {
        decorations.disconnectAll();
        for (String added : new ArrayList<>(classes())) {
            if (!builtClasses.contains(added)) removeClass(added);
        }
        setDisplayed(true);
        control.set(Attribute.INERT, false);
        history = null;
    }

    private static String textFor(Arrangement arrangement, ConfigDescriptor descriptor) {
        String shortLabel = descriptor.shortLabel();
        return arrangement == Arrangement.COMPACT && shortLabel != null ? shortLabel : descriptor.label();
    }

    @Nullable
    private static String hintFor(Arrangement arrangement, ConfigDescriptor descriptor) {
        if (descriptor.tooltip() != null) return descriptor.tooltip();
        return arrangement == Arrangement.COMPACT && descriptor.shortLabel() != null ? descriptor.label() : null;
    }

    public ConfigControl control() {
        return control;
    }

    /** The label this row was built with — shown beside the control unless the control labels itself. */
    public String labelText() {
        return labelText;
    }

    /** Null on a self-labelling field — there is no label element, not merely an empty one. */
    @Nullable
    public UIText label() {
        return label.parent() == null ? null : label;
    }

    public UIElement inline() {
        return inline;
    }

    /** The hover hint, or null when the field has none. */
    @Nullable
    public Tooltip hint() {
        return hint;
    }

    /**
     * Keeps the hint saying what {@code text} holds, for as long as this field is on screen.
     *
     * <pre>{@code
     * form.prop(number("w", "Width").shortLabel("W").unit("%"), scaleX)
     *         .describeWith(Property.derived(() -> "Width: " + Math.round(box.width()) + " px"));
     * }</pre>
     *
     * @throws IllegalStateException on a field with no hint — give its descriptor a tooltip or a short label
     */
    public Configurator describeWith(Property<String> text) {
        Tooltip describing = hint;
        if (describing == null) throw new IllegalStateException("this field has no hint to describe with");
        describing.setText(text.get());
        PropertyWatch watch = new PropertyWatch(this, text,
                (was, now) -> describing.setText(now == null ? "" : now));
        decorations.add(whileConnected(watch::start));
        return this;
    }
}
