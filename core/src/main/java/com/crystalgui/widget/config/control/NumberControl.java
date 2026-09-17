package com.crystalgui.widget.config.control;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.config.ConfigControlContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.widget.config.PropertyWatch;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.ui.input.DragScrub;

import javax.annotation.Nullable;
import java.util.Locale;
import com.crystalgui.ui.dom.Name;

/**
 * One number, typed into a field.
 *
 * <p>Unity reference: {@code docs/research/unity-nodes/03-scalar-float.png}.</p>
 *
 * <h3>The value is not the text</h3>
 * <p>A field being typed into passes through states that are not numbers — {@code ""}, {@code "-"},
 * {@code "1."} are all on the way to a number and none of them parses. Committing only what parses,
 * and leaving the text alone otherwise, is what lets someone clear the field and start again. Rejecting
 * or rewriting the text mid-edit is the behaviour that makes a field impossible to type a negative
 * number into, because the minus sign is deleted before the digit arrives.</p>
 *
 * <h3>Formatting is one-way</h3>
 * <p>{@link #writeToWidgets} formats, {@link #parse} reads, and the two are deliberately not inverses:
 * {@code 0.30000001} is written as {@code 0.3}. Re-formatting on every keystroke would fight the
 * caret — type {@code 0.10} and the field would rewrite it to {@code 0.1} with the caret adrift — so
 * the text is only ever written on a programmatic set.</p>
 *
 * <h3>Scrubbing lives here, not in whatever is hosting the number</h3>
 * <p>{@link #scrubWith} makes any element a drag handle for this value — see {@link DragScrub} for what
 * the gesture is and where it comes from. It is on <b>this</b> class rather than on the graph's port
 * editor because scrubbing belongs to <i>a number</i>: put it here and {@link VectorControl}'s
 * {@code X Y Z W} cells, {@link MatrixControl}'s grid, the node port editors and every configurator row
 * get it in one move. Put it in the port editor and the graph gets it, nothing else does, and the second
 * consumer writes it again.</p>
 */
public class NumberControl extends ValueControl<Double> {

    public static final Name NAME = Name.of("numbercontrol");

    /** Typed into, and scrubbed. Debounced like any field; the scrub commits on release. */
    public static final Event<NumberControl, Double> CHANGED =
            ConfigControlContracts.changed(StateTypes.DOUBLE, 0d, RatePolicy.TYPING);

    public static final WidgetContract<NumberControl> CONTRACT = ConfigControlContracts.register(
            NumberControl.class, "numbercontrol", StateTypes.DOUBLE, 0d, CHANGED);


    /** Marks an element that has been made a scrub handle — the hook {@code default.css} hangs the
     * cursor on, so no Java here names a cursor. */
    public static final String SCRUB_HANDLE_CLASS = "__scrub-handle__";

    /**
     * On the control for the duration of a scrub — a hook for a theme that wants to show the gesture.
     *
     * <p>Deliberately <b>not</b> load-bearing for the cursor, unlike {@code splitview.__dragging__} which
     * it otherwise resembles. That one exists because the divider's drag captures the pointer on the
     * SplitView <em>root</em>, so the cursor resolves from an element with no {@code cursor} of its own. A
     * scrub captures on the handle itself, which already carries {@link #SCRUB_HANDLE_CLASS}, so the
     * cursor holds for the whole gesture with no help.</p>
     */
    public static final String SCRUBBING_CLASS = "__scrubbing__";

    private final TextField field = new TextField();
    private final boolean integral;

    /** Decimal places shown, or -1 for up to four. @see ConfigDescriptor#decimals */
    private final int decimals;


    /** The no-argument constructor the registry's factory needs, over a NEUTRAL
     * descriptor -- an unlabelled control of this kind, which is a real thing rather than a
     * placeholder. Nothing decodes one: the kit is {@code localOnly}, and the registration
     * exists so a theme can address {@code numbercontrol } by tag. */
    public NumberControl() {
        this(ConfigDescriptor.number("", ""), 0d);
    }

    public NumberControl(ConfigDescriptor descriptor, double defaultValue) {
        super(NAME, descriptor, defaultValue);
        this.integral = descriptor.integral();
        this.decimals = descriptor.decimals();
        addClass("__number__");
        append(field);
        quietly(() -> writeToWidgets(defaultValue));
        if (descriptor.commitsWhileTyping()) field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);

        // A UNIT THAT FOLLOWS ANOTHER VALUE is re-shown when it moves, since the text is only written on a
        // programmatic set and would otherwise keep the old suffix until the number itself changed.
        if (descriptor.live()) {
            PropertyWatch unitWatch = new PropertyWatch(this, Property.derived(descriptor::unit), (was, now) -> {
                if (!isEditing()) quietly(() -> writeToWidgets(getValue()));
            });
            whileConnected(unitWatch::start);
        }

        field.attachListener(text -> {
            Double parsed = parse(text);
            if (parsed == null) return; // mid-edit: "", "-", "1." — leave the text alone
            commit(clamp(parsed));
        });
    }

    /** The field itself, for a host that needs to reach the widget — sizing, focus, a max length. */
    public TextField field() {
        return field;
    }

    /** The label beside the box is its scrub handle. @see #scrubWith */
    @Override
    public boolean adoptLabel(UIElement label) {
        scrubWith(label);
        return true;
    }

    /** Text typed and not yet landed — the field publishes on Enter, Tab or a click away. */
    @Override
    public boolean isEditing() {
        return field.hasPendingEdit();
    }

    /**
     * Makes {@code handle} drag this value: press it and slide, right/up to increase.
     *
     * <p>Typically the label beside the box — {@code X} on a vector component, the row label in an
     * inspector. The handle is usually not this control's own child, which is why it is passed in rather
     * than assumed.</p>
     *
     * <h3>A press that does not travel is still a click</h3>
     * <p>Below {@link DragScrub#DEFAULT_THRESHOLD_PX} nothing is committed and the field takes focus
     * instead, so a label remains a way <em>into</em> the box rather than only a way to change it. The
     * threshold is enforced here rather than through {@code UIDragController}'s: that one is tied to the
     * payload overloads, and a payload turns this into a drag-and-drop that would dispatch drag events at
     * every element the pointer crosses — for a gesture with no payload and nowhere to drop.</p>
     *
     * <h3>Deltas are converted to physical pixels first</h3>
     * <p>A {@code DragListener} reports movement in the <b>source's local space</b>, and in a node graph
     * the handle is inside a zoomable plane — so raw local deltas would make the scrub rate depend on the
     * canvas zoom, and the same hand movement would mean different things at different zooms. The
     * conversion samples the handle's own transform rather than asking anything about canvases, so it
     * holds for {@code uiScale}, a zoom, or any other transform in the chain.</p>
     */
    public NumberControl scrubWith(UIElement handle) {
        handle.addClass(SCRUB_HANDLE_CLASS);
        Drag.scrub(handle, field, new DragScrub.Target() {
            @Override
            public boolean scrubbable() {
                return isEnabled();
            }

            @Override
            public double start() {
                Double held = getValue();
                return held == null ? 0d : held;
            }

            @Override
            public DragScrub.Spec spec() {
                return scrubSpec();
            }

            @Override
            public void apply(double value) {
                // setValue repaints the box, which a scrub is not typing into; commit tells the host.
                setValue(value);
                commit(value);
            }

            @Override
            public void began() {
                addClass(SCRUBBING_CLASS);
                beginInteraction();
            }

            @Override
            public void ended() {
                removeClass(SCRUBBING_CLASS);
                endInteraction();
            }
        });
        return this;
    }

    private DragScrub.Spec scrubSpec() {
        // A LENGTH IN PIXELS scrubs as one with nothing declared: a whole pixel every three or so, a tenth with Ctrl.
        DragScrub.Spec spec = ("px".equals(descriptor().unit()) ? DragScrub.Spec.PIXELS : DragScrub.Spec.FLOAT)
                .withIntegral(integral);
        ConfigDescriptor.Range range = descriptor().range();
        if (range != null) spec = spec.withRange(range.min(), range.max());
        // ASKED PER DRAG, since a rate may follow another value -- a slider's span. @see ConfigDescriptor#scrubRate
        double scrubRate = descriptor().scrubRate();
        if (!Double.isNaN(scrubRate)) spec = spec.withRate(scrubRate);
        // A step quantises the gesture: that is what one is for. @see ConfigDescriptor#step
        return descriptor().step() > 0f ? spec.withStep(descriptor().step()) : spec;
    }


    @Override
    protected void writeToWidgets(@Nullable Double value) {
        field.setText(format(value == null ? 0d : value));
    }

    private double clamp(double v) {
        ConfigDescriptor.Range range = descriptor().range();
        if (range == null) return v;
        return Math.max(range.min(), Math.min(range.max(), v));
    }

    /** The number, then the descriptor's unit if it names one: {@code 45°}, {@code 100%}. */
    private String format(double v) {
        String unit = descriptor().unit();
        return formatNumber(v) + (unit == null ? "" : unit);
    }

    private String formatNumber(double v) {
        if (integral) return String.valueOf(Math.round(v));
        if (decimals >= 0) return String.format(Locale.ROOT, "%." + decimals + "f", v);
        // Trailing zeros stripped, so 0.5 is "0.5" and 1.0 is "1" — Unity's own presentation, and the
        // difference between a readable node and one that is all decimal points.
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** A number, with or without the unit typed after it. */
    @Nullable
    private Double parse(String text) {
        String trimmed = text.trim();
        String unit = descriptor().unit();
        if (unit != null && trimmed.endsWith(unit)) {
            trimmed = trimmed.substring(0, trimmed.length() - unit.length()).trim();
        }
        if (trimmed.isEmpty()) return null;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException incomplete) {
            return null;
        }
    }
}
